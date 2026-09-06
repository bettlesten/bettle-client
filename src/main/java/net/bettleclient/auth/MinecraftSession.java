package net.bettleclient.auth;

import java.util.Objects;
import java.util.UUID;

/**
 * An authenticated, online-capable Minecraft session.
 *
 * <p>These are exactly the four credentials the game needs in order to
 * complete the {@code joinServer} / {@code hasJoined} handshake against
 * Mojang's session service, which is what allows the player to connect to
 * official online-mode multiplayer servers:</p>
 *
 * <ul>
 *   <li>{@code accessToken} - the Minecraft services bearer token</li>
 *   <li>{@code uuid} - the profile UUID</li>
 *   <li>{@code username} - the profile name</li>
 *   <li>{@code xuid} - the Xbox user id, required by 1.19+ chat signing</li>
 * </ul>
 *
 * <p>Instances are immutable. {@link #toString()} deliberately never prints the
 * access token.</p>
 */
public final class MinecraftSession {

    /** Mojang's own launcher client id; sent as {@code --clientId}. */
    public static final String DEFAULT_CLIENT_ID = "00000000402b5328";

    private final String username;
    private final UUID uuid;
    private final String accessToken;
    private final String xuid;
    private final String clientId;
    private final String userType;
    private final String skinUrl;
    private final long expiresAtMillis;

    public MinecraftSession(String username,
                            UUID uuid,
                            String accessToken,
                            String xuid,
                            String clientId,
                            String userType,
                            String skinUrl,
                            long expiresAtMillis) {
        this.username = Objects.requireNonNull(username, "username");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
        this.accessToken = Objects.requireNonNull(accessToken, "accessToken");
        this.xuid = xuid == null ? "" : xuid;
        this.clientId = (clientId == null || clientId.isBlank()) ? DEFAULT_CLIENT_ID : clientId;
        this.userType = (userType == null || userType.isBlank()) ? "msa" : userType;
        this.skinUrl = skinUrl;
        this.expiresAtMillis = expiresAtMillis;
    }

    /** Value for {@code --username}. */
    public String getUsername() {
        return username;
    }

    public UUID getUuid() {
        return uuid;
    }

    /**
     * Value for {@code --uuid}. The vanilla launcher passes the undashed form,
     * so Bettle Client does too.
     */
    public String getUuidUndashed() {
        return uuid.toString().replace("-", "");
    }

    /** Value for {@code --accessToken}. */
    public String getAccessToken() {
        return accessToken;
    }

    /** Value for {@code --xuid}; required for signed chat on 1.19+. */
    public String getXuid() {
        return xuid;
    }

    /** Value for {@code --clientId}. */
    public String getClientId() {
        return clientId;
    }

    /** Value for {@code --userType}; always {@code msa} for Microsoft accounts. */
    public String getUserType() {
        return userType;
    }

    /** URL of the profile skin, used to render the avatar in the UI. */
    public String getSkinUrl() {
        return skinUrl;
    }

    /** Epoch millis at which the access token stops being accepted. */
    public long getExpiresAtMillis() {
        return expiresAtMillis;
    }

    public boolean isExpired() {
        return expiresAtMillis > 0 && System.currentTimeMillis() >= expiresAtMillis;
    }

    /** Minutes until expiry, clamped at zero. */
    public long getMinutesUntilExpiry() {
        if (expiresAtMillis <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (expiresAtMillis - System.currentTimeMillis()) / 60_000L);
    }

    /** Legacy {@code ${auth_session}} placeholder used by pre-1.6 argument strings. */
    public String getLegacySessionToken() {
        return "token:" + accessToken + ":" + getUuidUndashed();
    }

    @Override
    public String toString() {
        return "MinecraftSession{username=" + username
                + ", uuid=" + uuid
                + ", xuid=" + (xuid.isEmpty() ? "<none>" : xuid)
                + ", userType=" + userType
                + ", accessToken=<redacted>"
                + ", expiresInMinutes=" + (expiresAtMillis > 0 ? getMinutesUntilExpiry() : -1)
                + '}';
    }
}
