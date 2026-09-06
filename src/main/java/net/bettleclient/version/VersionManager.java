package net.bettleclient.version;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bettleclient.util.HttpUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dynamic version fetching engine.
 *
 * <p>Reads Mojang's official version manifest and exposes the version list to
 * the rest of the launcher. Nothing about the supported version range is
 * hardcoded as a list: the manifest is the single source of truth, and the
 * supported window is expressed as an inclusive lower bound
 * ({@value #MIN_SUPPORTED_VERSION}) and an inclusive major-version ceiling
 * ({@value #MAX_SUPPORTED_MAJOR}). Versions Mojang publishes in the future
 * appear automatically the next time the manifest is refreshed.</p>
 *
 * <p>The manifest is cached on disk so the launcher still populates its
 * dropdown when the machine is offline.</p>
 */
public final class VersionManager {

    /** Mojang's official manifest endpoint (v2 adds sha1 + complianceLevel). */
    public static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json";

    /** Piston mirror, used when the primary host cannot be reached. */
    public static final String MANIFEST_MIRROR_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    /** Oldest release the launcher advertises as supported. */
    public static final String MIN_SUPPORTED_VERSION = "1.20.1";

    /** Newest major series the launcher advertises as supported (the "26.x" ceiling). */
    public static final int MAX_SUPPORTED_MAJOR = 26;

    /** Re-download the manifest when the cached copy is older than this. */
    private static final long CACHE_TTL_MILLIS = 6L * 60L * 60L * 1000L;

    private static final Pattern NUMERIC_COMPONENT = Pattern.compile("\\d+");

    private final Path cacheFile;

    private final AtomicReference<List<VersionEntry>> versions = new AtomicReference<>(List.of());
    private final AtomicReference<String> latestRelease = new AtomicReference<>(null);
    private final AtomicReference<String> latestSnapshot = new AtomicReference<>(null);
    private volatile boolean loadedFromCache;

    /**
     * @param cacheDirectory directory in which {@code version_manifest_v2.json}
     *                       is cached; may be {@code null} to disable caching
     */
    public VersionManager(Path cacheDirectory) {
        this.cacheFile = cacheDirectory == null
                ? null
                : cacheDirectory.resolve("version_manifest_v2.json");
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /**
     * Loads the manifest, preferring a fresh network copy and falling back to
     * the on-disk cache.
     *
     * @return every version in the manifest, newest first
     */
    public List<VersionEntry> load() throws IOException {
        String body = null;
        IOException networkFailure = null;

        if (!isCacheFresh()) {
            try {
                body = fetchManifestBody();
                writeCache(body);
                loadedFromCache = false;
            } catch (IOException e) {
                networkFailure = e;
            }
        }

        if (body == null) {
            body = readCache();
            loadedFromCache = body != null;
        }

        if (body == null) {
            // No cache and no network: try one more time so the caller sees the
            // real transport error rather than a generic "no versions" state.
            if (networkFailure != null) {
                throw networkFailure;
            }
            body = fetchManifestBody();
            writeCache(body);
            loadedFromCache = false;
        }

        parse(body);
        return getAllVersions();
    }

    /** Loads the manifest off the JavaFX application thread. */
    public CompletableFuture<List<VersionEntry>> loadAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return load();
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
    }

    /** Forces a network refresh, ignoring cache freshness. */
    public List<VersionEntry> refresh() throws IOException {
        String body = fetchManifestBody();
        writeCache(body);
        loadedFromCache = false;
        parse(body);
        return getAllVersions();
    }

    private String fetchManifestBody() throws IOException {
        try {
            return HttpUtil.getString(MANIFEST_URL);
        } catch (IOException primaryFailure) {
            try {
                return HttpUtil.getString(MANIFEST_MIRROR_URL);
            } catch (IOException mirrorFailure) {
                primaryFailure.addSuppressed(mirrorFailure);
                throw primaryFailure;
            }
        }
    }

    private void parse(String body) throws IOException {
        JsonElement root = JsonParser.parseString(body);
        if (!root.isJsonObject()) {
            throw new IOException("Malformed version manifest: root is not an object");
        }
        JsonObject json = root.getAsJsonObject();

        if (json.has("latest") && json.get("latest").isJsonObject()) {
            JsonObject latest = json.getAsJsonObject("latest");
            latestRelease.set(latest.has("release") ? latest.get("release").getAsString() : null);
            latestSnapshot.set(latest.has("snapshot") ? latest.get("snapshot").getAsString() : null);
        }

        if (!json.has("versions") || !json.get("versions").isJsonArray()) {
            throw new IOException("Malformed version manifest: missing 'versions' array");
        }

        JsonArray array = json.getAsJsonArray("versions");
        List<VersionEntry> parsed = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            if (!object.has("id")) {
                continue;
            }
            parsed.add(VersionEntry.fromJson(object));
        }
        Collections.sort(parsed); // newest first
        versions.set(Collections.unmodifiableList(parsed));
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** Every version in the manifest, newest first. */
    public List<VersionEntry> getAllVersions() {
        return versions.get();
    }

    /** All {@code type: release} versions, newest first. */
    public List<VersionEntry> getReleases() {
        return versions.get().stream()
                .filter(VersionEntry::isRelease)
                .collect(Collectors.toUnmodifiableList());
    }

    /** All {@code type: snapshot} versions, newest first. */
    public List<VersionEntry> getSnapshots() {
        return versions.get().stream()
                .filter(VersionEntry::isSnapshot)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Releases inside the supported window: everything from
     * {@value #MIN_SUPPORTED_VERSION} up to and including the
     * {@value #MAX_SUPPORTED_MAJOR}.x series, newest first.
     *
     * <p>This is what populates the launcher's version dropdown. It is derived
     * from the live manifest, so a future 1.21.x, 1.22, or 26.x release shows
     * up without any code change.</p>
     */
    public List<VersionEntry> getSupportedReleases() {
        return getVersionsInRange(MIN_SUPPORTED_VERSION, MAX_SUPPORTED_MAJOR, false);
    }

    /** Supported releases plus snapshots in the same window. */
    public List<VersionEntry> getSupportedReleasesAndSnapshots() {
        return getVersionsInRange(MIN_SUPPORTED_VERSION, MAX_SUPPORTED_MAJOR, true);
    }

    /**
     * Filters the manifest to a version window.
     *
     * @param minimumInclusive  lowest version id to keep, e.g. {@code "1.20.1"}
     * @param maximumMajor      inclusive ceiling on the leading numeric component
     * @param includeSnapshots  whether snapshots are kept alongside releases
     */
    public List<VersionEntry> getVersionsInRange(String minimumInclusive,
                                                 int maximumMajor,
                                                 boolean includeSnapshots) {
        List<VersionEntry> result = new ArrayList<>();
        for (VersionEntry entry : versions.get()) {
            if (!entry.isRelease() && !(includeSnapshots && entry.isSnapshot())) {
                continue;
            }
            if (!isNumericVersion(entry.getId())) {
                continue;
            }
            if (compareVersionIds(entry.getId(), minimumInclusive) < 0) {
                continue;
            }
            if (majorOf(entry.getId()) > maximumMajor) {
                continue;
            }
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    /** Just the ids of {@link #getSupportedReleases()}, for logging or CLI use. */
    public List<String> getSupportedReleaseIds() {
        return getSupportedReleases().stream()
                .map(VersionEntry::getId)
                .collect(Collectors.toUnmodifiableList());
    }

    /** Looks up a specific version id in the loaded manifest. */
    public Optional<VersionEntry> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return versions.get().stream()
                .filter(entry -> entry.getId().equalsIgnoreCase(id))
                .findFirst();
    }

    /** The manifest's advertised latest release id, or {@code null}. */
    public String getLatestReleaseId() {
        return latestRelease.get();
    }

    /** The manifest's advertised latest snapshot id, or {@code null}. */
    public String getLatestSnapshotId() {
        return latestSnapshot.get();
    }

    /**
     * Best default selection for the dropdown: the newest supported release,
     * falling back to whatever the manifest calls the latest release.
     */
    public Optional<VersionEntry> getDefaultSelection() {
        List<VersionEntry> supported = getSupportedReleases();
        if (!supported.isEmpty()) {
            return Optional.of(supported.get(0));
        }
        return findById(latestRelease.get());
    }

    /** True when the current data came from the disk cache rather than the network. */
    public boolean isLoadedFromCache() {
        return loadedFromCache;
    }

    // ------------------------------------------------------------------
    // Version id arithmetic
    // ------------------------------------------------------------------

    /**
     * Compares two Minecraft version ids numerically component by component.
     *
     * <p>Handles both the {@code 1.MAJOR.MINOR} scheme used today and a bare
     * {@code MAJOR.MINOR} scheme (the hypothetical 26.x series), because the
     * comparison only ever looks at the numeric components in order.</p>
     *
     * <pre>
     *   compareVersionIds("1.20.1", "1.20")   &gt; 0
     *   compareVersionIds("1.21",   "1.20.4") &gt; 0
     *   compareVersionIds("26.1",   "1.21.4") &gt; 0
     * </pre>
     *
     * @return negative, zero or positive as {@code a} is older, equal or newer
     */
    public static int compareVersionIds(String a, String b) {
        int[] left = numericComponents(a);
        int[] right = numericComponents(b);
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int l = i < left.length ? left[i] : 0;
            int r = i < right.length ? right[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        return 0;
    }

    /** Leading numeric component of a version id, or {@link Integer#MAX_VALUE}. */
    public static int majorOf(String id) {
        int[] parts = numericComponents(id);
        return parts.length == 0 ? Integer.MAX_VALUE : parts[0];
    }

    /**
     * True for plain numeric release ids like {@code 1.20.1} or {@code 26.3},
     * false for snapshot and pre-release ids like {@code 24w14a} or
     * {@code 1.21-pre2}.
     */
    public static boolean isNumericVersion(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return id.toLowerCase(Locale.ROOT).matches("\\d+(\\.\\d+)*");
    }

    private static int[] numericComponents(String id) {
        if (id == null) {
            return new int[0];
        }
        List<Integer> parts = new ArrayList<>(4);
        Matcher matcher = NUMERIC_COMPONENT.matcher(id);
        while (matcher.find()) {
            try {
                parts.add(Integer.parseInt(matcher.group()));
            } catch (NumberFormatException e) {
                parts.add(0);
            }
        }
        int[] result = new int[parts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = parts.get(i);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Cache
    // ------------------------------------------------------------------

    private boolean isCacheFresh() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return false;
        }
        try {
            long age = System.currentTimeMillis() - Files.getLastModifiedTime(cacheFile).toMillis();
            return age >= 0 && age < CACHE_TTL_MILLIS;
        } catch (IOException e) {
            return false;
        }
    }

    private String readCache() {
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return null;
        }
        try {
            return Files.readString(cacheFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String body) {
        if (cacheFile == null) {
            return;
        }
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A failed cache write must never break launching.
        }
    }
}
