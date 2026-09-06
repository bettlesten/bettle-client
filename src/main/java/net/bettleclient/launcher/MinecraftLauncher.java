package net.bettleclient.launcher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bettleclient.auth.MinecraftSession;
import net.bettleclient.util.HttpUtil;
import net.bettleclient.util.OperatingSystem;
import net.bettleclient.util.RuleEvaluator;
import net.bettleclient.version.VersionEntry;
import net.bettleclient.version.VersionManager;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Multi-version launch engine.
 *
 * <p>Given a {@link VersionEntry} from the manifest and an authenticated
 * {@link MinecraftSession}, this class performs the complete vanilla install
 * and launch sequence:</p>
 *
 * <ol>
 *   <li>Download and cache the version JSON, resolving {@code inheritsFrom}
 *       chains so Fabric / Quilt / Forge profiles work as well as vanilla.</li>
 *   <li>Download the client jar.</li>
 *   <li>Resolve every library against the platform rules, download the
 *       artifacts and extract native binaries into a per-version natives
 *       directory.</li>
 *   <li>Download the asset index and every asset object referenced by it,
 *       in parallel, honouring the legacy / virtual asset layouts.</li>
 *   <li>Assemble the JVM arguments, classpath, main class and game arguments,
 *       substituting every {@code ${...}} placeholder Mojang defines.</li>
 *   <li>Spawn the process with {@link ProcessBuilder} and pump its output to
 *       the listener.</li>
 * </ol>
 *
 * <p>Directory layout, rooted at the launcher directory:</p>
 * <pre>
 *   versions/&lt;id&gt;/&lt;id&gt;.json
 *   versions/&lt;id&gt;/&lt;id&gt;.jar
 *   versions/&lt;id&gt;/natives/
 *   libraries/&lt;maven path&gt;
 *   assets/indexes/&lt;index&gt;.json
 *   assets/objects/&lt;xx&gt;/&lt;sha1&gt;
 *   instances/&lt;id&gt;/                (the per-version game directory)
 * </pre>
 */
public final class MinecraftLauncher {

    /** CDN that serves asset objects by hash. */
    public static final String ASSET_BASE_URL = "https://resources.download.minecraft.net/";

    /** Fallback Maven host for libraries whose entry has no explicit URL. */
    public static final String DEFAULT_LIBRARY_BASE_URL = "https://libraries.minecraft.net/";

    public static final String LAUNCHER_BRAND = "BettleClient";
    public static final String LAUNCHER_VERSION = "1.0.0";

    /** Guards against a malformed manifest sending resolution into a loop. */
    private static final int MAX_INHERITANCE_DEPTH = 16;

    private static final int DOWNLOAD_THREADS =
            Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));

    private final Path rootDirectory;
    private final Path versionsDirectory;
    private final Path librariesDirectory;
    private final Path assetsDirectory;
    private final Path instancesDirectory;
    private final VersionManager versionManager;

    /**
     * @param rootDirectory  launcher data directory, e.g. {@code ~/.bettleclient}
     * @param versionManager used to resolve {@code inheritsFrom} parents that are
     *                       not yet cached on disk
     */
    public MinecraftLauncher(Path rootDirectory, VersionManager versionManager) {
        this.rootDirectory = rootDirectory;
        this.versionsDirectory = rootDirectory.resolve("versions");
        this.librariesDirectory = rootDirectory.resolve("libraries");
        this.assetsDirectory = rootDirectory.resolve("assets");
        this.instancesDirectory = rootDirectory.resolve("instances");
        this.versionManager = versionManager;
    }

    public Path getRootDirectory() {
        return rootDirectory;
    }

    public Path getVersionsDirectory() {
        return versionsDirectory;
    }

    public Path getLibrariesDirectory() {
        return librariesDirectory;
    }

    public Path getAssetsDirectory() {
        return assetsDirectory;
    }

    /** Default game directory for a version: {@code instances/<id>}. */
    public Path getInstanceDirectory(String versionId) {
        return instancesDirectory.resolve(versionId);
    }

    // ==================================================================
    // Public entry point
    // ==================================================================

    /**
     * Installs the version if needed and starts the game.
     *
     * @return the running game process
     */
    public Process launch(VersionEntry version,
                          MinecraftSession session,
                          LaunchOptions options,
                          ProgressListener listener) throws LaunchException {

        ProgressListener progress = listener == null ? ProgressListener.NOOP : listener;

        try {
            progress.onStage("Resolving " + version.getId());
            progress.onProgress(-1);

            JsonObject versionJson = resolveVersionJson(version, progress);
            String versionId = versionJson.has("id")
                    ? versionJson.get("id").getAsString()
                    : version.getId();

            Path gameDirectory = options.getGameDirectory() != null
                    ? options.getGameDirectory()
                    : getInstanceDirectory(versionId);
            Files.createDirectories(gameDirectory);

            Path nativesDirectory = versionsDirectory.resolve(versionId).resolve("natives");
            Files.createDirectories(nativesDirectory);

            progress.onStage("Downloading client jar");
            Path clientJar = downloadClientJar(versionJson, versionId, progress);

            progress.onStage("Resolving libraries");
            List<Path> classpath = resolveLibraries(versionJson, nativesDirectory, progress);
            classpath.add(clientJar);

            progress.onStage("Downloading assets");
            String assetIndexName = downloadAssets(versionJson, gameDirectory, progress);

            progress.onStage("Building launch command");
            progress.onProgress(-1);
            List<String> command = buildCommand(
                    versionJson, versionId, session, options,
                    classpath, nativesDirectory, gameDirectory, assetIndexName);

            progress.onLog("Launch command: " + redact(command, session));

            progress.onStage("Starting Minecraft " + versionId);
            Process process = spawn(command, gameDirectory, progress);
            progress.onGameStarted(process);
            progress.onProgress(1.0);
            return process;

        } catch (LaunchException e) {
            progress.onError(e);
            throw e;
        } catch (Exception e) {
            LaunchException wrapped = new LaunchException(
                    "Failed to launch " + version.getId() + ": " + e.getMessage(), e);
            progress.onError(wrapped);
            throw wrapped;
        }
    }

    // ==================================================================
    // Step 1 - version JSON
    // ==================================================================

    /**
     * Downloads (or reads from cache) the version JSON and flattens any
     * {@code inheritsFrom} chain into a single effective descriptor.
     */
    public JsonObject resolveVersionJson(VersionEntry version, ProgressListener progress)
            throws IOException, LaunchException {
        JsonObject json = loadVersionJson(version.getId(), version.getUrl(), version.getSha1(), progress);
        return flattenInheritance(json, 0, progress);
    }

    private JsonObject loadVersionJson(String id, String url, String sha1, ProgressListener progress)
            throws IOException, LaunchException {

        Path target = versionsDirectory.resolve(id).resolve(id + ".json");

        if (!HttpUtil.isValid(target, sha1, -1)) {
            String effectiveUrl = url;
            if (effectiveUrl == null && versionManager != null) {
                effectiveUrl = versionManager.findById(id)
                        .map(VersionEntry::getUrl)
                        .orElse(null);
            }
            if (effectiveUrl == null) {
                if (Files.isRegularFile(target)) {
                    // A locally installed profile (modloader) with no manifest entry.
                    progress.onLog("Using local version profile for " + id);
                } else {
                    throw new LaunchException("No download URL known for version " + id
                            + " and no local copy exists.");
                }
            } else {
                progress.onLog("Fetching version manifest for " + id);
                Files.createDirectories(target.getParent());
                HttpUtil.download(effectiveUrl, target, sha1, -1, null);
            }
        }

        String body = Files.readString(target, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new LaunchException("Version JSON for " + id + " is not a JSON object.");
        }
        return parsed.getAsJsonObject();
    }

    /**
     * Merges a child profile onto its parent.
     *
     * <p>Modloaders publish a thin profile that names a vanilla
     * {@code inheritsFrom} parent and contributes extra libraries, a different
     * {@code mainClass} and extra arguments. The merge follows the convention
     * every launcher uses: child scalars win, child libraries are prepended so
     * they shadow the parent's, and argument arrays are concatenated
     * parent-first.</p>
     */
    private JsonObject flattenInheritance(JsonObject child, int depth, ProgressListener progress)
            throws IOException, LaunchException {

        if (!child.has("inheritsFrom") || child.get("inheritsFrom").isJsonNull()) {
            return child;
        }
        if (depth >= MAX_INHERITANCE_DEPTH) {
            throw new LaunchException("inheritsFrom chain is too deep (possible cycle).");
        }

        String parentId = child.get("inheritsFrom").getAsString();
        progress.onLog("Version inherits from " + parentId);

        String parentUrl = versionManager != null
                ? versionManager.findById(parentId).map(VersionEntry::getUrl).orElse(null)
                : null;
        String parentSha1 = versionManager != null
                ? versionManager.findById(parentId).map(VersionEntry::getSha1).orElse(null)
                : null;

        JsonObject parent = flattenInheritance(
                loadVersionJson(parentId, parentUrl, parentSha1, progress), depth + 1, progress);

        JsonObject merged = parent.deepCopy();

        // A modloader profile contributes no client jar of its own, so the jar
        // stays filed under the parent's id instead of being duplicated under
        // the loader's. Mojang's format already has a field for exactly this.
        if (!child.has("downloads")) {
            String jarOwner = parent.has("jar") && !parent.get("jar").isJsonNull()
                    ? parent.get("jar").getAsString()
                    : parentId;
            merged.addProperty("jar", jarOwner);
        }

        for (Map.Entry<String, JsonElement> entry : child.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            switch (key) {
                case "inheritsFrom":
                    break;
                case "libraries": {
                    JsonArray combined = new JsonArray();
                    if (value.isJsonArray()) {
                        combined.addAll(value.getAsJsonArray());
                    }
                    if (merged.has("libraries") && merged.get("libraries").isJsonArray()) {
                        combined.addAll(merged.getAsJsonArray("libraries"));
                    }
                    merged.add("libraries", combined);
                    break;
                }
                case "arguments": {
                    JsonObject mergedArgs = merged.has("arguments") && merged.get("arguments").isJsonObject()
                            ? merged.getAsJsonObject("arguments")
                            : new JsonObject();
                    if (value.isJsonObject()) {
                        JsonObject childArgs = value.getAsJsonObject();
                        for (String section : new String[]{"game", "jvm"}) {
                            if (!childArgs.has(section) || !childArgs.get(section).isJsonArray()) {
                                continue;
                            }
                            JsonArray combined = new JsonArray();
                            if (mergedArgs.has(section) && mergedArgs.get(section).isJsonArray()) {
                                combined.addAll(mergedArgs.getAsJsonArray(section));
                            }
                            combined.addAll(childArgs.getAsJsonArray(section));
                            mergedArgs.add(section, combined);
                        }
                    }
                    merged.add("arguments", mergedArgs);
                    break;
                }
                default:
                    merged.add(key, value);
                    break;
            }
        }
        return merged;
    }

    // ==================================================================
    // Step 2 - client jar
    // ==================================================================

    private Path downloadClientJar(JsonObject versionJson, String versionId, ProgressListener progress)
            throws IOException, LaunchException {

        // Profiles that inherit from another version keep the client jar under
        // the id named by "jar" rather than under their own id.
        String jarId = versionJson.has("jar") && !versionJson.get("jar").isJsonNull()
                ? versionJson.get("jar").getAsString()
                : versionId;
        Path target = versionsDirectory.resolve(jarId).resolve(jarId + ".jar");

        if (!versionJson.has("downloads") || !versionJson.get("downloads").isJsonObject()) {
            if (Files.isRegularFile(target)) {
                return target;
            }
            throw new LaunchException("Version JSON has no 'downloads' block and no client jar is cached.");
        }

        JsonObject downloads = versionJson.getAsJsonObject("downloads");
        if (!downloads.has("client") || !downloads.get("client").isJsonObject()) {
            if (Files.isRegularFile(target)) {
                return target;
            }
            throw new LaunchException("Version JSON has no client download entry.");
        }

        JsonObject client = downloads.getAsJsonObject("client");
        String url = client.get("url").getAsString();
        String sha1 = client.has("sha1") ? client.get("sha1").getAsString() : null;
        long size = client.has("size") ? client.get("size").getAsLong() : -1L;

        AtomicLong transferred = new AtomicLong();
        long total = Math.max(size, 1);
        boolean fetched = HttpUtil.download(url, target, sha1, size,
                delta -> progress.onProgress(Math.min(1.0, transferred.addAndGet(delta) / (double) total)));

        if (fetched) {
            progress.onLog("Downloaded client jar " + jarId + ".jar");
        }
        progress.onProgress(1.0);
        return target;
    }

    // ==================================================================
    // Step 3 - libraries and natives
    // ==================================================================

    /**
     * Resolves, downloads and returns the classpath entries for a version, and
     * extracts any native binaries into {@code nativesDirectory}.
     */
    public List<Path> resolveLibraries(JsonObject versionJson,
                                       Path nativesDirectory,
                                       ProgressListener progress) throws IOException, LaunchException {

        List<Path> classpath = new ArrayList<>();
        if (!versionJson.has("libraries") || !versionJson.get("libraries").isJsonArray()) {
            return classpath;
        }

        JsonArray libraries = versionJson.getAsJsonArray("libraries");

        // group:artifact:classifier -> chosen entry. Keeps the highest version,
        // which is what makes modloader profiles (Fabric, Forge) resolve cleanly.
        Map<String, ResolvedLibrary> selected = new LinkedHashMap<>();
        List<ResolvedLibrary> nativeJars = new ArrayList<>();

        for (JsonElement element : libraries) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject library = element.getAsJsonObject();

            JsonArray rules = library.has("rules") && library.get("rules").isJsonArray()
                    ? library.getAsJsonArray("rules") : null;
            if (!RuleEvaluator.isAllowed(rules)) {
                continue;
            }
            if (!library.has("name")) {
                continue;
            }

            MavenCoordinate coordinate = MavenCoordinate.parse(library.get("name").getAsString());

            // --- main artifact (classpath) ---
            ResolvedLibrary main = resolveArtifact(library, coordinate);
            if (main != null) {
                String key = coordinate.groupId + ":" + coordinate.artifactId + ":"
                        + (coordinate.classifier == null ? "" : coordinate.classifier);
                ResolvedLibrary existing = selected.get(key);
                if (existing == null
                        || VersionManager.compareVersionIds(coordinate.version, existing.version) > 0) {
                    selected.put(key, main);
                }
                // Modern LWJGL ships natives as classpath jars named
                // <artifact>-<version>-natives-<os>.jar. Extracting the shared
                // objects as well keeps -Djava.library.path usable.
                if (coordinate.classifier != null
                        && coordinate.classifier.startsWith("natives-")) {
                    nativeJars.add(main);
                }
            }

            // --- legacy natives block (1.16 and older) ---
            ResolvedLibrary legacyNative = resolveLegacyNative(library, coordinate);
            if (legacyNative != null) {
                nativeJars.add(legacyNative);
            }
        }

        List<ResolvedLibrary> toDownload = new ArrayList<>(selected.values());
        for (ResolvedLibrary nativeJar : nativeJars) {
            if (toDownload.stream().noneMatch(entry -> entry.target.equals(nativeJar.target))) {
                toDownload.add(nativeJar);
            }
        }

        downloadAll(toDownload, progress, "libraries");

        for (ResolvedLibrary library : selected.values()) {
            classpath.add(library.target);
        }

        progress.onStage("Extracting natives");
        progress.onProgress(-1);
        for (ResolvedLibrary nativeJar : nativeJars) {
            extractNatives(nativeJar.target, nativesDirectory, nativeJar.extractExclusions,
                    nativeJar.legacy, progress);
        }
        progress.onProgress(1.0);

        return classpath;
    }

    /** Builds the download descriptor for a library's main artifact. */
    private ResolvedLibrary resolveArtifact(JsonObject library, MavenCoordinate coordinate) {
        if (library.has("downloads") && library.get("downloads").isJsonObject()) {
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.has("path")
                        ? artifact.get("path").getAsString()
                        : coordinate.toPath();
                return new ResolvedLibrary(
                        librariesDirectory.resolve(path),
                        artifact.has("url") ? artifact.get("url").getAsString() : null,
                        artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
                        artifact.has("size") ? artifact.get("size").getAsLong() : -1L,
                        coordinate.version,
                        extractExclusions(library),
                        false);
            }
            // A downloads block with only classifiers means this entry
            // contributes natives and nothing to the classpath.
            if (downloads.has("classifiers")) {
                return null;
            }
        }

        // Modloader style entry: "name" + optional "url" base, no downloads block.
        String path = coordinate.toPath();
        String base = library.has("url") && !library.get("url").isJsonNull()
                ? library.get("url").getAsString()
                : DEFAULT_LIBRARY_BASE_URL;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        return new ResolvedLibrary(
                librariesDirectory.resolve(path),
                base + path,
                null,
                -1L,
                coordinate.version,
                extractExclusions(library),
                false);
    }

    /**
     * Handles the pre-1.17 layout where natives live under
     * {@code downloads.classifiers} and are selected by a {@code natives} map.
     */
    private ResolvedLibrary resolveLegacyNative(JsonObject library, MavenCoordinate coordinate) {
        if (!library.has("natives") || !library.get("natives").isJsonObject()) {
            return null;
        }
        JsonObject natives = library.getAsJsonObject("natives");
        String osKey = OperatingSystem.current().getMojangName();
        if (!natives.has(osKey)) {
            return null;
        }

        String classifier = natives.get(osKey).getAsString()
                .replace("${arch}", OperatingSystem.currentArch().endsWith("64") ? "64" : "32");

        if (library.has("downloads") && library.get("downloads").isJsonObject()) {
            JsonObject downloads = library.getAsJsonObject("downloads");
            if (downloads.has("classifiers") && downloads.get("classifiers").isJsonObject()) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers.has(classifier) && classifiers.get(classifier).isJsonObject()) {
                    JsonObject artifact = classifiers.getAsJsonObject(classifier);
                    String path = artifact.has("path")
                            ? artifact.get("path").getAsString()
                            : coordinate.withClassifier(classifier).toPath();
                    return new ResolvedLibrary(
                            librariesDirectory.resolve(path),
                            artifact.has("url") ? artifact.get("url").getAsString() : null,
                            artifact.has("sha1") ? artifact.get("sha1").getAsString() : null,
                            artifact.has("size") ? artifact.get("size").getAsLong() : -1L,
                            coordinate.version,
                            extractExclusions(library),
                            true);
                }
            }
        }

        String path = coordinate.withClassifier(classifier).toPath();
        return new ResolvedLibrary(
                librariesDirectory.resolve(path),
                DEFAULT_LIBRARY_BASE_URL + path,
                null,
                -1L,
                coordinate.version,
                extractExclusions(library),
                true);
    }

    private static Set<String> extractExclusions(JsonObject library) {
        Set<String> exclusions = new LinkedHashSet<>();
        exclusions.add("META-INF/");
        if (library.has("extract") && library.get("extract").isJsonObject()) {
            JsonObject extract = library.getAsJsonObject("extract");
            if (extract.has("exclude") && extract.get("exclude").isJsonArray()) {
                for (JsonElement element : extract.getAsJsonArray("exclude")) {
                    exclusions.add(element.getAsString());
                }
            }
        }
        return exclusions;
    }

    /**
     * Unpacks native binaries from a jar into the natives directory.
     *
     * @param legacyLayout when true every non-excluded entry is written out
     *                     (the pre-1.17 behaviour); otherwise only shared
     *                     libraries are extracted
     */
    private void extractNatives(Path jar,
                                Path targetDirectory,
                                Set<String> exclusions,
                                boolean legacyLayout,
                                ProgressListener progress) throws IOException {

        if (!Files.isRegularFile(jar)) {
            return;
        }
        Files.createDirectories(targetDirectory);

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();

                boolean excluded = false;
                for (String exclusion : exclusions) {
                    if (name.startsWith(exclusion)) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) {
                    continue;
                }
                if (!legacyLayout && !isNativeBinary(name)) {
                    continue;
                }

                // Flatten to the file name: LWJGL and friends expect the shared
                // objects directly on java.library.path, and this also blocks
                // any "../" traversal in a malformed archive.
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (fileName.isEmpty()) {
                    continue;
                }
                Path destination = targetDirectory.resolve(fileName);

                if (Files.exists(destination) && Files.size(destination) == entry.getSize()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
                }
                progress.onLog("Extracted native " + fileName);
            }
        }
    }

    private static boolean isNativeBinary(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".dll")
                || lower.endsWith(".so")
                || lower.endsWith(".dylib")
                || lower.endsWith(".jnilib")
                || lower.matches(".*\\.so\\.\\d+$");
    }

    // ==================================================================
    // Step 4 - assets
    // ==================================================================

    /**
     * Downloads the asset index and every object it references.
     *
     * @return the asset index name, used for {@code --assetIndex}
     */
    public String downloadAssets(JsonObject versionJson, Path gameDirectory, ProgressListener progress)
            throws IOException, LaunchException {

        if (!versionJson.has("assetIndex") || !versionJson.get("assetIndex").isJsonObject()) {
            return versionJson.has("assets") ? versionJson.get("assets").getAsString() : "legacy";
        }

        JsonObject assetIndex = versionJson.getAsJsonObject("assetIndex");
        String indexId = assetIndex.get("id").getAsString();
        String indexUrl = assetIndex.get("url").getAsString();
        String indexSha1 = assetIndex.has("sha1") ? assetIndex.get("sha1").getAsString() : null;

        Path indexFile = assetsDirectory.resolve("indexes").resolve(indexId + ".json");
        HttpUtil.download(indexUrl, indexFile, indexSha1, -1, null);

        JsonObject index = JsonParser
                .parseString(Files.readString(indexFile, StandardCharsets.UTF_8))
                .getAsJsonObject();

        if (!index.has("objects") || !index.get("objects").isJsonObject()) {
            return indexId;
        }

        boolean virtual = index.has("virtual") && index.get("virtual").getAsBoolean();
        boolean mapToResources = index.has("map_to_resources")
                && index.get("map_to_resources").getAsBoolean();

        JsonObject objects = index.getAsJsonObject("objects");
        Path objectsDirectory = assetsDirectory.resolve("objects");

        List<AssetObject> assets = new ArrayList<>(objects.size());
        for (Map.Entry<String, JsonElement> entry : objects.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            if (!object.has("hash")) {
                continue;
            }
            String hash = object.get("hash").getAsString();
            long size = object.has("size") ? object.get("size").getAsLong() : -1L;
            assets.add(new AssetObject(entry.getKey(), hash, size));
        }

        AtomicInteger completed = new AtomicInteger();
        int total = assets.size();
        progress.onLog("Verifying " + total + " asset objects for index " + indexId);

        List<Callable<Void>> tasks = new ArrayList<>(assets.size());
        for (AssetObject asset : assets) {
            tasks.add(() -> {
                String prefix = asset.hash.substring(0, 2);
                Path destination = objectsDirectory.resolve(prefix).resolve(asset.hash);
                HttpUtil.download(ASSET_BASE_URL + prefix + "/" + asset.hash,
                        destination, asset.hash, asset.size, null);
                progress.onProgress(completed.incrementAndGet() / (double) Math.max(1, total));
                return null;
            });
        }
        runInParallel(tasks, "assets");

        if (virtual || mapToResources) {
            Path virtualRoot = mapToResources
                    ? gameDirectory.resolve("resources")
                    : assetsDirectory.resolve("virtual").resolve(indexId);
            progress.onStage("Unpacking legacy assets");
            for (AssetObject asset : assets) {
                Path source = objectsDirectory.resolve(asset.hash.substring(0, 2)).resolve(asset.hash);
                Path destination = virtualRoot.resolve(asset.name);
                if (!Files.isRegularFile(source)) {
                    continue;
                }
                if (Files.exists(destination) && Files.size(destination) == Files.size(source)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        progress.onProgress(1.0);
        return indexId;
    }

    // ==================================================================
    // Step 5 - command assembly
    // ==================================================================

    /**
     * Assembles the complete process command line: JVM, JVM arguments,
     * main class, and game arguments with every placeholder substituted.
     */
    public List<String> buildCommand(JsonObject versionJson,
                                     String versionId,
                                     MinecraftSession session,
                                     LaunchOptions options,
                                     List<Path> classpath,
                                     Path nativesDirectory,
                                     Path gameDirectory,
                                     String assetIndexName) throws LaunchException {

        Path javaExecutable = options.getJavaExecutable() != null
                ? options.getJavaExecutable()
                : OperatingSystem.currentJavaExecutable();

        String classpathString = joinClasspath(classpath);

        Map<String, String> placeholders = buildPlaceholders(
                versionJson, versionId, session, options,
                classpathString, nativesDirectory, gameDirectory, assetIndexName);

        Map<String, Boolean> features = buildFeatureFlags(options);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());

        // ---- JVM arguments ----
        List<String> jvmArguments = new ArrayList<>();
        JsonObject arguments = versionJson.has("arguments") && versionJson.get("arguments").isJsonObject()
                ? versionJson.getAsJsonObject("arguments")
                : null;

        if (arguments != null && arguments.has("jvm") && arguments.get("jvm").isJsonArray()) {
            jvmArguments.addAll(collectArguments(arguments.getAsJsonArray("jvm"), features));
        } else {
            // Pre-1.13 versions have no jvm argument block.
            jvmArguments.add("-Djava.library.path=${natives_directory}");
            jvmArguments.add("-cp");
            jvmArguments.add("${classpath}");
        }

        // Heap sizing has to come before the -cp pair is consumed, but order
        // among JVM flags is otherwise irrelevant.
        List<String> prefixArguments = new ArrayList<>();
        prefixArguments.add("-Xms" + options.getMinMemoryMb() + "M");
        prefixArguments.add("-Xmx" + options.getMaxMemoryMb() + "M");
        prefixArguments.add("-Dlog4j2.formatMsgNoLookups=true");
        prefixArguments.add("-Dminecraft.launcher.brand=" + LAUNCHER_BRAND);
        prefixArguments.add("-Dminecraft.launcher.version=" + LAUNCHER_VERSION);
        prefixArguments.add("-Dfile.encoding=UTF-8");
        if (OperatingSystem.current().isOsx()) {
            prefixArguments.add("-Xdock:name=Minecraft " + versionId);
        }
        prefixArguments.addAll(options.getExtraJvmArguments());

        command.addAll(substituteAll(prefixArguments, placeholders));
        command.addAll(substituteAll(jvmArguments, placeholders));

        // Some pre-1.13 profiles omit the classpath entirely; add it defensively.
        if (!command.contains("-cp") && !command.contains("-classpath")) {
            command.add("-cp");
            command.add(classpathString);
        }

        // ---- Main class ----
        if (!versionJson.has("mainClass")) {
            throw new LaunchException("Version JSON has no mainClass.");
        }
        command.add(versionJson.get("mainClass").getAsString());

        // ---- Game arguments ----
        List<String> gameArguments = new ArrayList<>();
        if (arguments != null && arguments.has("game") && arguments.get("game").isJsonArray()) {
            gameArguments.addAll(collectArguments(arguments.getAsJsonArray("game"), features));
        } else if (versionJson.has("minecraftArguments")) {
            gameArguments.addAll(Arrays.asList(
                    StringUtils.split(versionJson.get("minecraftArguments").getAsString(), ' ')));
        }

        List<String> substitutedGameArguments = substituteAll(gameArguments, placeholders);
        command.addAll(substitutedGameArguments);

        // ---- Quick connect ----
        if (options.hasQuickConnect()) {
            boolean supportsQuickPlay =
                    VersionManager.compareVersionIds(versionId, "1.20") >= 0
                            && substitutedGameArguments.contains("--quickPlayMultiplayer");
            if (!supportsQuickPlay && !substitutedGameArguments.contains("--server")) {
                // 1.19 and older, or a profile without quick-play support.
                command.add("--server");
                command.add(options.getQuickConnectHost());
                command.add("--port");
                command.add(String.valueOf(options.getQuickConnectPort()));
            }
        }

        if (options.isFullscreen() && !command.contains("--fullscreen")) {
            command.add("--fullscreen");
        }
        command.addAll(options.getExtraGameArguments());

        // Drop anything whose placeholder could not be resolved rather than
        // handing the game a literal "${...}" token.
        List<String> cleaned = new ArrayList<>(command.size());
        for (String argument : command) {
            if (argument != null && !argument.contains("${")) {
                cleaned.add(argument);
            }
        }
        return cleaned;
    }

    private Map<String, String> buildPlaceholders(JsonObject versionJson,
                                                  String versionId,
                                                  MinecraftSession session,
                                                  LaunchOptions options,
                                                  String classpath,
                                                  Path nativesDirectory,
                                                  Path gameDirectory,
                                                  String assetIndexName) {

        Map<String, String> map = new HashMap<>();

        map.put("auth_player_name", session.getUsername());
        map.put("auth_uuid", session.getUuidUndashed());
        map.put("auth_access_token", session.getAccessToken());
        map.put("auth_session", session.getLegacySessionToken());
        map.put("auth_xuid", session.getXuid());
        map.put("clientid", session.getClientId());
        map.put("user_type", session.getUserType());
        map.put("user_properties", "{}");

        map.put("version_name", versionId);
        map.put("version_type", versionJson.has("type")
                ? versionJson.get("type").getAsString() : "release");

        map.put("game_directory", gameDirectory.toAbsolutePath().toString());
        map.put("assets_root", assetsDirectory.toAbsolutePath().toString());
        map.put("assets_index_name", assetIndexName);
        map.put("game_assets", assetsDirectory.resolve("virtual")
                .resolve(assetIndexName).toAbsolutePath().toString());

        map.put("natives_directory", nativesDirectory.toAbsolutePath().toString());
        map.put("library_directory", librariesDirectory.toAbsolutePath().toString());
        map.put("classpath", classpath);
        map.put("classpath_separator", OperatingSystem.classpathSeparator());

        map.put("launcher_name", LAUNCHER_BRAND);
        map.put("launcher_version", LAUNCHER_VERSION);

        if (options.hasCustomResolution()) {
            map.put("resolution_width", String.valueOf(options.getWindowWidth()));
            map.put("resolution_height", String.valueOf(options.getWindowHeight()));
        }
        if (options.hasQuickConnect()) {
            map.put("quickPlayMultiplayer", options.getQuickConnectAddress());
            map.put("quickPlayPath", gameDirectory.resolve("quickPlay.json")
                    .toAbsolutePath().toString());
        }
        return map;
    }

    private Map<String, Boolean> buildFeatureFlags(LaunchOptions options) {
        Map<String, Boolean> features = new HashMap<>();
        features.put(RuleEvaluator.FEATURE_DEMO_USER, options.isDemo());
        features.put(RuleEvaluator.FEATURE_CUSTOM_RESOLUTION, options.hasCustomResolution());
        features.put(RuleEvaluator.FEATURE_QUICK_PLAY_SUPPORT, options.hasQuickConnect());
        features.put(RuleEvaluator.FEATURE_QUICK_PLAY_MULTIPLAYER, options.hasQuickConnect());
        features.put(RuleEvaluator.FEATURE_QUICK_PLAY_SINGLEPLAYER, false);
        features.put(RuleEvaluator.FEATURE_QUICK_PLAY_REALMS, false);
        return features;
    }

    /**
     * Flattens a 1.13+ argument array, which mixes bare strings with
     * rule-guarded objects whose {@code value} is a string or an array.
     */
    private List<String> collectArguments(JsonArray array, Map<String, Boolean> features) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                result.add(element.getAsString());
                continue;
            }
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            JsonArray rules = object.has("rules") && object.get("rules").isJsonArray()
                    ? object.getAsJsonArray("rules") : null;
            if (!RuleEvaluator.isAllowed(rules, features)) {
                continue;
            }
            if (!object.has("value")) {
                continue;
            }
            JsonElement value = object.get("value");
            if (value.isJsonPrimitive()) {
                result.add(value.getAsString());
            } else if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    result.add(item.getAsString());
                }
            }
        }
        return result;
    }

    private static List<String> substituteAll(List<String> arguments, Map<String, String> placeholders) {
        List<String> result = new ArrayList<>(arguments.size());
        for (String argument : arguments) {
            result.add(substitute(argument, placeholders));
        }
        return result;
    }

    /** Replaces every {@code ${key}} occurrence that has a known value. */
    private static String substitute(String template, Map<String, String> placeholders) {
        if (template == null || template.indexOf('$') < 0) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String joinClasspath(List<Path> entries) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        for (Path entry : entries) {
            String absolute = entry.toAbsolutePath().toString();
            if (!seen.add(absolute)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(OperatingSystem.classpathSeparator());
            }
            builder.append(absolute);
        }
        return builder.toString();
    }

    // ==================================================================
    // Step 6 - process
    // ==================================================================

    private Process spawn(List<String> command, Path workingDirectory, ProgressListener progress)
            throws IOException {

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();

        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    progress.onLog(line);
                }
            } catch (IOException ignored) {
                // The stream closes when the game exits.
            }
        }, "Bettle-GameOutput");
        pump.setDaemon(true);
        pump.start();

        Thread watcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                progress.onGameExited(exitCode);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Bettle-GameWatcher");
        watcher.setDaemon(true);
        watcher.start();

        return process;
    }

    /** Renders the command for the log with the access token masked. */
    private static String redact(List<String> command, MinecraftSession session) {
        String joined = String.join(" ", command);
        if (session != null && session.getAccessToken() != null && !session.getAccessToken().isBlank()) {
            joined = joined.replace(session.getAccessToken(), "<accessToken redacted>");
        }
        return joined;
    }

    // ==================================================================
    // Parallel download helper
    // ==================================================================

    private void downloadAll(List<ResolvedLibrary> libraries, ProgressListener progress, String label)
            throws LaunchException {

        AtomicInteger completed = new AtomicInteger();
        int total = libraries.size();

        List<Callable<Void>> tasks = new ArrayList<>(total);
        for (ResolvedLibrary library : libraries) {
            tasks.add(() -> {
                if (library.url == null) {
                    if (!Files.isRegularFile(library.target)) {
                        throw new IOException("No URL and no local copy for " + library.target.getFileName());
                    }
                } else {
                    HttpUtil.download(library.url, library.target, library.sha1, library.size, null);
                }
                progress.onProgress(completed.incrementAndGet() / (double) Math.max(1, total));
                return null;
            });
        }
        runInParallel(tasks, label);
    }

    private void runInParallel(List<Callable<Void>> tasks, String label) throws LaunchException {
        if (tasks.isEmpty()) {
            return;
        }
        ExecutorService executor = newDownloadPool();
        try {
            List<Future<Void>> futures = new ArrayList<>(tasks.size());
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(task));
            }
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    throw new LaunchException("Failed while downloading " + label + ": "
                            + cause.getMessage(), cause);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LaunchException("Interrupted while downloading " + label, e);
                }
            }
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ExecutorService newDownloadPool() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "Bettle-Download");
            thread.setDaemon(true);
            return thread;
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                DOWNLOAD_THREADS, DOWNLOAD_THREADS,
                30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(8192),
                factory,
                new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    // ==================================================================
    // Value types
    // ==================================================================

    /** A library artifact resolved to a concrete local path and remote URL. */
    private static final class ResolvedLibrary {

        final Path target;
        final String url;
        final String sha1;
        final long size;
        final String version;
        final Set<String> extractExclusions;
        final boolean legacy;

        ResolvedLibrary(Path target, String url, String sha1, long size,
                        String version, Set<String> extractExclusions, boolean legacy) {
            this.target = target;
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
            this.version = version == null ? "0" : version;
            this.extractExclusions = extractExclusions;
            this.legacy = legacy;
        }
    }

    /** One entry of an asset index. */
    private static final class AssetObject {

        final String name;
        final String hash;
        final long size;

        AssetObject(String name, String hash, long size) {
            this.name = name;
            this.hash = hash;
            this.size = size;
        }
    }

    /** Parses and renders Maven coordinates of the form used in version JSONs. */
    static final class MavenCoordinate {

        final String groupId;
        final String artifactId;
        final String version;
        final String classifier;
        final String extension;

        private MavenCoordinate(String groupId, String artifactId, String version,
                                String classifier, String extension) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier;
            this.extension = extension;
        }

        /** Parses {@code group:artifact:version[:classifier][@extension]}. */
        static MavenCoordinate parse(String name) {
            String working = name;
            String extension = "jar";

            int at = working.indexOf('@');
            if (at >= 0) {
                extension = working.substring(at + 1);
                working = working.substring(0, at);
            }

            String[] parts = working.split(":");
            String groupId = parts.length > 0 ? parts[0] : "";
            String artifactId = parts.length > 1 ? parts[1] : "";
            String version = parts.length > 2 ? parts[2] : "0";
            String classifier = parts.length > 3 ? parts[3] : null;

            return new MavenCoordinate(groupId, artifactId, version, classifier, extension);
        }

        MavenCoordinate withClassifier(String newClassifier) {
            return new MavenCoordinate(groupId, artifactId, version, newClassifier, extension);
        }

        /** Repository-relative path, e.g. {@code org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3.jar}. */
        String toPath() {
            StringBuilder builder = new StringBuilder();
            builder.append(groupId.replace('.', '/')).append('/')
                    .append(artifactId).append('/')
                    .append(version).append('/')
                    .append(artifactId).append('-').append(version);
            if (classifier != null && !classifier.isEmpty()) {
                builder.append('-').append(classifier);
            }
            builder.append('.').append(extension);
            return builder.toString();
        }

        @Override
        public String toString() {
            return groupId + ":" + artifactId + ":" + version
                    + (classifier == null ? "" : ":" + classifier);
        }
    }

    /** Thrown when installation or launch cannot complete. */
    public static final class LaunchException extends Exception {

        private static final long serialVersionUID = 1L;

        public LaunchException(String message) {
            super(message);
        }

        public LaunchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
