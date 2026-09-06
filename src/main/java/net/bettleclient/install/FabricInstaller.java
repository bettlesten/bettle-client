package net.bettleclient.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.bettleclient.launcher.ProgressListener;
import net.bettleclient.util.HttpUtil;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Installs Fabric Loader by writing the launcher profile that FabricMC's meta
 * service publishes.
 *
 * <p>Fabric does not need a separate installer program. Its meta service
 * returns a ready-made version profile - the same shape as a Mojang version
 * JSON, with {@code inheritsFrom} pointing at the vanilla version and the
 * Fabric libraries listed with their own Maven base URLs. Writing that file to
 * {@code versions/<id>/<id>.json} is the entire installation; the existing
 * launch engine already resolves {@code inheritsFrom} chains and libraries that
 * carry a {@code url} base instead of a {@code downloads} block.</p>
 *
 * <pre>{@code
 * FabricInstaller installer = new FabricInstaller(launcher.getVersionsDirectory());
 * String profileId = installer.installLatest("1.21.4", listener);
 * // profileId == "fabric-loader-0.16.9-1.21.4"
 * }</pre>
 */
public final class FabricInstaller {

    /** FabricMC's public metadata service. */
    public static final String META_BASE_URL = "https://meta.fabricmc.net/v2";

    private final Path versionsDirectory;

    public FabricInstaller(Path versionsDirectory) {
        this.versionsDirectory = versionsDirectory;
    }

    // ------------------------------------------------------------------
    // Loader discovery
    // ------------------------------------------------------------------

    /**
     * Lists the Fabric Loader versions compatible with a Minecraft version,
     * newest first.
     *
     * @param gameVersion    a Minecraft version id such as {@code 1.21.4}
     * @param stableOnly     when true, release-quality loaders only
     */
    public List<String> getLoaderVersions(String gameVersion, boolean stableOnly) throws IOException {
        String url = META_BASE_URL + "/versions/loader/" + encode(gameVersion);
        JsonElement root = JsonParser.parseString(HttpUtil.getString(url));

        if (!root.isJsonArray()) {
            throw new IOException("Unexpected response from Fabric meta for " + gameVersion);
        }

        JsonArray array = root.getAsJsonArray();
        List<String> versions = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("loader") || !entry.get("loader").isJsonObject()) {
                continue;
            }
            JsonObject loader = entry.getAsJsonObject("loader");
            if (!loader.has("version")) {
                continue;
            }
            boolean stable = loader.has("stable") && loader.get("stable").getAsBoolean();
            if (stableOnly && !stable) {
                continue;
            }
            versions.add(loader.get("version").getAsString());
        }
        return versions;
    }

    /**
     * The newest Fabric Loader for a Minecraft version, preferring a stable
     * build and falling back to the newest of any quality.
     *
     * @throws IOException when Fabric publishes no loader for this version,
     *                     which is the normal answer for a brand-new snapshot
     */
    public String getLatestLoaderVersion(String gameVersion) throws IOException {
        List<String> stable = getLoaderVersions(gameVersion, true);
        if (!stable.isEmpty()) {
            return stable.get(0);
        }
        List<String> any = getLoaderVersions(gameVersion, false);
        if (!any.isEmpty()) {
            return any.get(0);
        }
        throw new IOException("Fabric Loader is not available for Minecraft " + gameVersion
                + " yet. Choose Vanilla, or pick a version Fabric supports.");
    }

    // ------------------------------------------------------------------
    // Installation
    // ------------------------------------------------------------------

    /** Installs the newest available loader for a Minecraft version. */
    public String installLatest(String gameVersion, ProgressListener progress) throws IOException {
        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;
        listener.onStage("Checking Fabric Loader for " + gameVersion);
        listener.onProgress(-1);
        String loaderVersion = getLatestLoaderVersion(gameVersion);
        listener.onLog("Fabric Loader " + loaderVersion + " selected for " + gameVersion);
        return install(gameVersion, loaderVersion, listener);
    }

    /**
     * Writes the Fabric profile JSON for a specific loader version.
     *
     * @return the profile's version id, e.g. {@code fabric-loader-0.16.9-1.21.4},
     *         which is what should be launched
     */
    public String install(String gameVersion, String loaderVersion, ProgressListener progress)
            throws IOException {

        ProgressListener listener = progress == null ? ProgressListener.NOOP : progress;

        String profileId = buildProfileId(loaderVersion, gameVersion);
        Path target = versionsDirectory.resolve(profileId).resolve(profileId + ".json");

        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            listener.onLog("Fabric profile already installed: " + profileId);
            return profileId;
        }

        listener.onStage("Installing Fabric Loader " + loaderVersion);
        listener.onProgress(-1);

        String url = META_BASE_URL + "/versions/loader/"
                + encode(gameVersion) + "/" + encode(loaderVersion) + "/profile/json";

        String body = HttpUtil.getString(url);

        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IOException("Fabric meta returned a malformed profile for "
                    + gameVersion + " / " + loaderVersion);
        }

        JsonObject profile = parsed.getAsJsonObject();
        // Trust the id the service reports over the one we derived, in case
        // Fabric ever changes its naming convention.
        String actualId = profile.has("id") ? profile.get("id").getAsString() : profileId;
        Path actualTarget = versionsDirectory.resolve(actualId).resolve(actualId + ".json");

        Files.createDirectories(actualTarget.getParent());
        Files.writeString(actualTarget, body, StandardCharsets.UTF_8);

        listener.onLog("Wrote Fabric profile " + actualId);
        listener.onProgress(1.0);
        return actualId;
    }

    /** True when a profile for this loader/game pair is already on disk. */
    public boolean isInstalled(String gameVersion, String loaderVersion) {
        String profileId = buildProfileId(loaderVersion, gameVersion);
        Path target = versionsDirectory.resolve(profileId).resolve(profileId + ".json");
        try {
            return Files.isRegularFile(target) && Files.size(target) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** Fabric's profile id convention: {@code fabric-loader-<loader>-<game>}. */
    public static String buildProfileId(String loaderVersion, String gameVersion) {
        return "fabric-loader-" + loaderVersion + "-" + gameVersion;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
