package net.bettleclient.version;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * One entry from Mojang's {@code version_manifest_v2.json} {@code versions}
 * array.
 *
 * <pre>{@code
 * {
 *   "id": "1.20.1",
 *   "type": "release",
 *   "url": "https://piston-meta.mojang.com/v1/packages/<sha1>/1.20.1.json",
 *   "time": "2023-06-12T09:00:00+00:00",
 *   "releaseTime": "2023-06-12T08:54:53+00:00",
 *   "sha1": "<sha1 of the version json>",
 *   "complianceLevel": 1
 * }
 * }</pre>
 */
public final class VersionEntry implements Comparable<VersionEntry> {

    private final String id;
    private final String type;
    private final String url;
    private final String sha1;
    private final String time;
    private final String releaseTime;
    private final int complianceLevel;

    public VersionEntry(String id, String type, String url, String sha1,
                        String time, String releaseTime, int complianceLevel) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = type == null ? "unknown" : type;
        this.url = url;
        this.sha1 = sha1;
        this.time = time;
        this.releaseTime = releaseTime;
        this.complianceLevel = complianceLevel;
    }

    /** Parses one element of the manifest {@code versions} array. */
    public static VersionEntry fromJson(JsonObject json) {
        return new VersionEntry(
                json.get("id").getAsString(),
                json.has("type") ? json.get("type").getAsString() : "unknown",
                json.has("url") ? json.get("url").getAsString() : null,
                json.has("sha1") ? json.get("sha1").getAsString() : null,
                json.has("time") ? json.get("time").getAsString() : null,
                json.has("releaseTime") ? json.get("releaseTime").getAsString() : null,
                json.has("complianceLevel") ? json.get("complianceLevel").getAsInt() : 0
        );
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    /** URL of this version's own JSON descriptor. */
    public String getUrl() {
        return url;
    }

    /** SHA-1 of the version JSON, used to validate the cached copy. */
    public String getSha1() {
        return sha1;
    }

    public String getTime() {
        return time;
    }

    public String getReleaseTime() {
        return releaseTime;
    }

    public int getComplianceLevel() {
        return complianceLevel;
    }

    public boolean isRelease() {
        return "release".equalsIgnoreCase(type);
    }

    public boolean isSnapshot() {
        return "snapshot".equalsIgnoreCase(type);
    }

    /** True for old_alpha / old_beta entries. */
    public boolean isLegacy() {
        return "old_alpha".equalsIgnoreCase(type) || "old_beta".equalsIgnoreCase(type);
    }

    /** Newest first, so a sorted list is already in display order. */
    @Override
    public int compareTo(VersionEntry other) {
        int cmp = VersionManager.compareVersionIds(other.id, this.id);
        if (cmp != 0) {
            return cmp;
        }
        return other.id.compareTo(this.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VersionEntry)) {
            return false;
        }
        return id.equals(((VersionEntry) o).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Rendered directly in the JavaFX version dropdown. */
    @Override
    public String toString() {
        return id;
    }
}
