package net.bettleclient.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bettleclient.launcher.ProgressListener;
import net.bettleclient.util.HttpUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * Puts the Bettle HUD mod and its Fabric API dependency into an instance's
 * {@code mods} folder.
 *
 * <p>The HUD mod jar is compiled by the Gradle module and embedded in the
 * launcher jar as a classpath resource at
 * {@value #BUNDLED_MOD_RESOURCE}, so a user who downloads only the installer
 * gets the mod with it. Fabric API cannot be redistributed inside the installer
 * on the same terms, so it is fetched from Modrinth at first launch for the
 * exact Minecraft version being started.</p>
 *
 * <p>Every method is a no-op when the work is already done, so calling this
 * before each launch is cheap.</p>
 */
public final class ModInstaller {

    /** Classpath location of the HUD mod jar embedded at build time. */
    public static final String BUNDLED_MOD_RESOURCE =
            "/net/bettleclient/bundled/bettle-hud-mod.jar";

    /** File name the HUD mod is written under inside {@code mods/}. */
    public static final String BUNDLED_MOD_FILE_NAME = "bettle-hud-mod.jar";

    private static final String MODRINTH_API = "https://api.modrinth.com/v2";
    private static final String FABRIC_API_PROJECT = "fabric-api";
    private static final String FABRIC_API_PREFIX = "fabric-api-";

    // ------------------------------------------------------------------
    // Bundled HUD mod
    // ------------------------------------------------------------------

    /** True when this launcher build actually embeds the HUD mod jar. */
    public boolean isModBundled() {
        try (InputStream in = ModInstaller.class.getResourceAsStream(BUNDLED_MOD_RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Copies the embedded HUD mod into {@code <gameDirectory>/mods}.
     *
     * <p>The copy is refreshed whenever the embedded jar differs in size from
     * the installed one, so upgrading the launcher upgrades the mod.</p>
     *
     * @return the installed path, or empty when this build has no mod embedded
     */
    public Optional<Path> installBundledMod(Path gameDirectory, ProgressListener progress)
            throws IOException {

        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;

        byte[] bundled;
        try (InputStream in = ModInstaller.class.getResourceAsStream(BUNDLED_MOD_RESOURCE)) {
            if (in == null) {
                listener.onLog("No HUD mod is embedded in this build; skipping mod install.");
                return Optional.empty();
            }
            bundled = in.readAllBytes();
        }

        Path modsDirectory = gameDirectory.resolve("mods");
        Files.createDirectories(modsDirectory);
        Path target = modsDirectory.resolve(BUNDLED_MOD_FILE_NAME);

        if (Files.isRegularFile(target) && Files.size(target) == bundled.length) {
            listener.onLog("Bettle HUD mod already present and up to date.");
            return Optional.of(target);
        }

        listener.onStage("Installing Bettle HUD mod");
        Path temporary = modsDirectory.resolve(BUNDLED_MOD_FILE_NAME + ".part");
        Files.write(temporary, bundled);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);

        listener.onLog("Installed " + BUNDLED_MOD_FILE_NAME
                + " (" + (bundled.length / 1024) + " KB) into " + modsDirectory);
        return Optional.of(target);
    }

    // ------------------------------------------------------------------
    // Fabric API dependency
    // ------------------------------------------------------------------

    /**
     * Downloads the Fabric API build matching a Minecraft version into
     * {@code mods}, replacing any Fabric API jar left over from a different
     * version.
     *
     * @return the installed path, or empty when Modrinth publishes no build for
     *         this version yet
     */
    public Optional<Path> installFabricApi(Path gameDirectory,
                                           String gameVersion,
                                           ProgressListener progress) throws IOException {

        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;

        listener.onStage("Resolving Fabric API for " + gameVersion);
        listener.onProgress(-1);

        FabricApiFile file = findFabricApi(gameVersion);
        if (file == null) {
            listener.onLog("No Fabric API build published for Minecraft " + gameVersion
                    + " yet - the HUD mod will not load until one is.");
            return Optional.empty();
        }

        Path modsDirectory = gameDirectory.resolve("mods");
        Files.createDirectories(modsDirectory);
        Path target = modsDirectory.resolve(file.fileName);

        if (HttpUtil.isValid(target, file.sha1, file.size)) {
            listener.onLog("Fabric API already present: " + file.fileName);
            removeStaleFabricApi(modsDirectory, file.fileName, listener);
            return Optional.of(target);
        }

        listener.onStage("Downloading Fabric API " + file.versionNumber);
        HttpUtil.download(file.url, target, file.sha1, file.size, null);
        listener.onLog("Installed " + file.fileName);

        removeStaleFabricApi(modsDirectory, file.fileName, listener);
        listener.onProgress(1.0);
        return Optional.of(target);
    }

    /**
     * Queries Modrinth for the newest Fabric API release matching a Minecraft
     * version.
     */
    private FabricApiFile findFabricApi(String gameVersion) throws IOException {
        String url = MODRINTH_API + "/project/" + FABRIC_API_PROJECT + "/version"
                + "?loaders=" + encode("[\"fabric\"]")
                + "&game_versions=" + encode("[\"" + gameVersion + "\"]");

        JsonElement root = JsonParser.parseString(HttpUtil.getString(url));
        if (!root.isJsonArray()) {
            return null;
        }

        JsonArray versions = root.getAsJsonArray();
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject version = element.getAsJsonObject();
            if (!version.has("files") || !version.get("files").isJsonArray()) {
                continue;
            }

            String versionNumber = version.has("version_number")
                    ? version.get("version_number").getAsString() : "unknown";

            JsonArray files = version.getAsJsonArray("files");
            JsonObject chosen = null;
            for (JsonElement fileElement : files) {
                if (!fileElement.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = fileElement.getAsJsonObject();
                boolean primary = candidate.has("primary") && candidate.get("primary").getAsBoolean();
                if (primary) {
                    chosen = candidate;
                    break;
                }
                if (chosen == null) {
                    chosen = candidate;
                }
            }
            if (chosen == null || !chosen.has("url") || !chosen.has("filename")) {
                continue;
            }

            String sha1 = null;
            if (chosen.has("hashes") && chosen.get("hashes").isJsonObject()) {
                JsonObject hashes = chosen.getAsJsonObject("hashes");
                if (hashes.has("sha1")) {
                    sha1 = hashes.get("sha1").getAsString();
                }
            }

            return new FabricApiFile(
                    chosen.get("url").getAsString(),
                    chosen.get("filename").getAsString(),
                    sha1,
                    chosen.has("size") ? chosen.get("size").getAsLong() : -1L,
                    versionNumber);
        }
        return null;
    }

    /**
     * Removes Fabric API jars for other Minecraft versions. Two copies in
     * {@code mods} make Fabric refuse to start, and a stale one is the usual
     * cause after switching versions.
     */
    private void removeStaleFabricApi(Path modsDirectory, String keep, ProgressListener listener) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(modsDirectory, "*.jar")) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (name.equals(keep)) {
                    continue;
                }
                if (name.startsWith(FABRIC_API_PREFIX)) {
                    Files.deleteIfExists(entry);
                    listener.onLog("Removed outdated " + name);
                }
            }
        } catch (IOException e) {
            listener.onLog("Could not tidy old Fabric API jars: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Combined
    // ------------------------------------------------------------------

    /**
     * Installs everything the HUD needs: the bundled mod plus Fabric API.
     *
     * <p>A missing Fabric API is reported but not fatal - the game still
     * starts, just without the HUD - so a brand-new Minecraft release does not
     * block launching.</p>
     */
    public void installAll(Path gameDirectory, String gameVersion, ProgressListener progress)
            throws IOException {

        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;
        installBundledMod(gameDirectory, listener);
        try {
            installFabricApi(gameDirectory, gameVersion, listener);
        } catch (IOException e) {
            listener.onLog("Fabric API could not be installed (" + e.getMessage()
                    + "). The game will start, but the Bettle HUD will not load.");
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** One downloadable Fabric API artifact as described by Modrinth. */
    private static final class FabricApiFile {

        final String url;
        final String fileName;
        final String sha1;
        final long size;
        final String versionNumber;

        FabricApiFile(String url, String fileName, String sha1, long size, String versionNumber) {
            this.url = url;
            this.fileName = fileName;
            this.sha1 = sha1;
            this.size = size;
            this.versionNumber = versionNumber;
        }
    }
}
