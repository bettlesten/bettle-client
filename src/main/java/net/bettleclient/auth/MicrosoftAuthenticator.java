package net.bettleclient.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.lenni0451.commons.httpclient.HttpClient;
import net.raphimc.minecraftauth.MinecraftAuth;
import net.raphimc.minecraftauth.step.java.StepMCProfile;
import net.raphimc.minecraftauth.step.java.StepMCToken;
import net.raphimc.minecraftauth.step.java.session.StepFullJavaSession;
import net.raphimc.minecraftauth.step.msa.StepMsaDeviceCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Complete Microsoft OAuth2 authentication for official Minecraft accounts.
 *
 * <p>Implements the <strong>device code</strong> grant, which is the flow
 * Microsoft recommends for desktop applications that cannot host a redirect
 * listener. The full chain executed by RaphiMC's MinecraftAuth is:</p>
 *
 * <pre>
 *   MSA device code  -&gt;  MSA access token
 *                    -&gt;  Xbox Live user token   (XBL)
 *                    -&gt;  XSTS authorization     (Xbox security token)
 *                    -&gt;  Minecraft services token
 *                    -&gt;  Minecraft profile (uuid + username)
 * </pre>
 *
 * <p>The resulting {@link MinecraftSession} carries a genuine Minecraft
 * services access token, so the game can complete the session-server
 * handshake and join official online-mode servers.</p>
 *
 * <p>The full session (including the MSA refresh token) is persisted to
 * {@code session.json} so subsequent launches are silent; on startup the
 * stored session is refreshed rather than re-prompting for a device code.</p>
 *
 * <h2>Typical use</h2>
 * <pre>{@code
 * MicrosoftAuthenticator auth = new MicrosoftAuthenticator(launcherRoot);
 * Optional<MinecraftSession> cached = auth.restoreSession();
 * MinecraftSession session = cached.isPresent()
 *         ? cached.get()
 *         : auth.login(code -> {
 *               System.out.println("Visit " + code.getVerificationUri());
 *               System.out.println("Enter code " + code.getUserCode());
 *           });
 * }</pre>
 */
public final class MicrosoftAuthenticator {

    private static final String SESSION_FILE_NAME = "session.json";

    /** Refresh when fewer than this many millis of validity remain. */
    private static final long REFRESH_MARGIN_MILLIS = 5L * 60L * 1000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path sessionFile;
    private final HttpClient httpClient;

    private volatile StepFullJavaSession.FullJavaSession javaSession;

    /**
     * @param launcherRoot directory in which {@code session.json} is stored
     */
    public MicrosoftAuthenticator(Path launcherRoot) {
        this.sessionFile = launcherRoot.resolve(SESSION_FILE_NAME);
        this.httpClient = MinecraftAuth.createHttpClient();
    }

    // ------------------------------------------------------------------
    // Device code payload exposed to the UI
    // ------------------------------------------------------------------

    /**
     * The information the user needs in order to approve the login: a short
     * code plus the page to enter it on.
     */
    public static final class DeviceCode {

        private final String userCode;
        private final String verificationUri;
        private final String directVerificationUri;
        private final long expiresAtMillis;

        DeviceCode(String userCode, String verificationUri,
                   String directVerificationUri, long expiresAtMillis) {
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.directVerificationUri = directVerificationUri;
            this.expiresAtMillis = expiresAtMillis;
        }

        /** The short code the user types, e.g. {@code A1B2-C3D4}. */
        public String getUserCode() {
            return userCode;
        }

        /** Page the user opens, normally {@code https://microsoft.com/link}. */
        public String getVerificationUri() {
            return verificationUri;
        }

        /** Same page with the code pre-filled, when Microsoft supplies one. */
        public String getDirectVerificationUri() {
            return directVerificationUri != null ? directVerificationUri : verificationUri;
        }

        public long getExpiresAtMillis() {
            return expiresAtMillis;
        }

        /** Seconds remaining before the code must be requested again. */
        public long getSecondsRemaining() {
            return Math.max(0L, (expiresAtMillis - System.currentTimeMillis()) / 1000L);
        }

        @Override
        public String toString() {
            return "Open " + getVerificationUri() + " and enter code " + userCode;
        }
    }

    /** Receives the device code as soon as Microsoft issues it. */
    @FunctionalInterface
    public interface DeviceCodeListener {
        void onDeviceCode(DeviceCode deviceCode);
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    /**
     * Runs the interactive device-code login, blocking until the user approves
     * the request in their browser or the code expires.
     *
     * @param listener notified once, with the code and verification URL
     * @return a verified, online-capable session
     */
    public MinecraftSession login(DeviceCodeListener listener) throws AuthenticationException {
        try {
            Consumer<StepMsaDeviceCode.MsaDeviceCode> consumer = code -> {
                if (listener != null) {
                    listener.onDeviceCode(new DeviceCode(
                            code.getUserCode(),
                            code.getVerificationUri(),
                            code.getDirectVerificationUri(),
                            code.getExpireTimeMs()
                    ));
                }
            };

            StepFullJavaSession.FullJavaSession session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN
                    .getFromInput(httpClient, new StepMsaDeviceCode.MsaDeviceCodeCallback(consumer));

            this.javaSession = session;
            persist(session);
            return toMinecraftSession(session);
        } catch (Exception e) {
            throw new AuthenticationException("Microsoft device-code login failed: " + e.getMessage(), e);
        }
    }

    /** {@link #login(DeviceCodeListener)} on a background thread. */
    public CompletableFuture<MinecraftSession> loginAsync(DeviceCodeListener listener, Executor executor) {
        if (executor == null) {
            return CompletableFuture.supplyAsync(() -> login(listener));
        }
        return CompletableFuture.supplyAsync(() -> login(listener), executor);
    }

    // ------------------------------------------------------------------
    // Session restore / refresh
    // ------------------------------------------------------------------

    /** True when a stored session exists on disk. */
    public boolean hasStoredSession() {
        return Files.isRegularFile(sessionFile);
    }

    /**
     * Restores the stored session, refreshing it when the Minecraft token has
     * expired or is close to expiring.
     *
     * @return the session, or empty when nothing is stored or the stored
     *         refresh token is no longer accepted
     */
    public Optional<MinecraftSession> restoreSession() {
        if (!hasStoredSession()) {
            return Optional.empty();
        }
        try {
            String body = Files.readString(sessionFile, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            StepFullJavaSession.FullJavaSession session =
                    MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.fromJson(json);

            if (needsRefresh(session)) {
                session = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.refresh(httpClient, session);
                persist(session);
            }

            this.javaSession = session;
            return Optional.of(toMinecraftSession(session));
        } catch (Exception e) {
            // A stored session that can no longer be refreshed is worthless;
            // drop it so the UI falls back to a fresh device-code login.
            logout();
            return Optional.empty();
        }
    }

    /** {@link #restoreSession()} on a background thread. */
    public CompletableFuture<Optional<MinecraftSession>> restoreSessionAsync() {
        return CompletableFuture.supplyAsync(this::restoreSession);
    }

    /**
     * Forces a token refresh using the stored MSA refresh token.
     *
     * @throws AuthenticationException when no session is loaded or the refresh is rejected
     */
    public MinecraftSession refresh() throws AuthenticationException {
        StepFullJavaSession.FullJavaSession current = this.javaSession;
        if (current == null) {
            throw new AuthenticationException("No session to refresh - sign in first.");
        }
        try {
            StepFullJavaSession.FullJavaSession refreshed =
                    MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.refresh(httpClient, current);
            this.javaSession = refreshed;
            persist(refreshed);
            return toMinecraftSession(refreshed);
        } catch (Exception e) {
            throw new AuthenticationException("Failed to refresh Microsoft session: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a session that is guaranteed to be valid right now, refreshing
     * transparently if needed. Call this immediately before launching.
     */
    public MinecraftSession requireValidSession() throws AuthenticationException {
        StepFullJavaSession.FullJavaSession current = this.javaSession;
        if (current == null) {
            throw new AuthenticationException("Not signed in.");
        }
        if (needsRefresh(current)) {
            return refresh();
        }
        return toMinecraftSession(current);
    }

    /** Deletes the stored session and clears the in-memory one. */
    public void logout() {
        this.javaSession = null;
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException ignored) {
            // Nothing useful to do; the session is already cleared in memory.
        }
    }

    /** The currently authenticated session, if any. */
    public Optional<MinecraftSession> getCurrentSession() {
        StepFullJavaSession.FullJavaSession current = this.javaSession;
        return current == null ? Optional.empty() : Optional.of(toMinecraftSession(current));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private boolean needsRefresh(StepFullJavaSession.FullJavaSession session) {
        try {
            StepMCToken.MCToken token = session.getMcProfile().getMcToken();
            long expiresAt = token.getExpireTimeMs();
            return expiresAt > 0 && System.currentTimeMillis() >= (expiresAt - REFRESH_MARGIN_MILLIS);
        } catch (Exception e) {
            return true;
        }
    }

    private void persist(StepFullJavaSession.FullJavaSession session) {
        try {
            Path parent = sessionFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JsonObject json = MinecraftAuth.JAVA_DEVICE_CODE_LOGIN.toJson(session);
            Files.writeString(sessionFile, GSON.toJson(json), StandardCharsets.UTF_8);
            restrictPermissions(sessionFile);
        } catch (Exception ignored) {
            // Failing to cache the session only costs an extra login later.
        }
    }

    /** Best-effort chmod 600 so the refresh token is not world readable. */
    private static void restrictPermissions(Path file) {
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions =
                    java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (Exception ignored) {
            // Windows has no POSIX permissions; nothing to do.
        }
    }

    private static MinecraftSession toMinecraftSession(StepFullJavaSession.FullJavaSession session) {
        StepMCProfile.MCProfile profile = session.getMcProfile();
        StepMCToken.MCToken token = profile.getMcToken();

        String accessToken = token.getAccessToken();
        UUID uuid = profile.getId();
        String username = profile.getName();
        String skinUrl = safeSkinUrl(profile);
        String xuid = extractXuid(accessToken);

        return new MinecraftSession(
                username,
                uuid,
                accessToken,
                xuid,
                MinecraftSession.DEFAULT_CLIENT_ID,
                "msa",
                skinUrl,
                token.getExpireTimeMs()
        );
    }

    private static String safeSkinUrl(StepMCProfile.MCProfile profile) {
        try {
            return profile.getSkinUrl();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the Xbox user id from the Minecraft services access token.
     *
     * <p>The token is a JWT whose payload carries an {@code xuid} claim. Reading
     * it here keeps the launcher independent of MinecraftAuth's internal token
     * chain layout, which has changed between major releases.</p>
     *
     * @return the xuid, or an empty string when it cannot be determined
     */
    static String extractXuid(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            return "";
        }
        try {
            String[] parts = accessToken.split("\\.");
            if (parts.length < 2) {
                return "";
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            JsonObject payload = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (payload.has("xuid") && !payload.get("xuid").isJsonNull()) {
                return payload.get("xuid").getAsString();
            }
            // Some tokens nest the claim under "profiles": { "mc": "<uuid>" }
            // and carry the xuid as "sub"; fall back to that when present.
            if (payload.has("sub") && !payload.get("sub").isJsonNull()) {
                String sub = payload.get("sub").getAsString();
                if (sub.matches("\\d{10,}")) {
                    return sub;
                }
            }
        } catch (Exception ignored) {
            // Malformed or opaque token: chat signing simply stays unavailable.
        }
        return "";
    }

    /** JWT segments are unpadded base64url; the JDK decoder wants padding. */
    private static String padBase64(String segment) {
        int remainder = segment.length() % 4;
        if (remainder == 0) {
            return segment;
        }
        return segment + "====".substring(remainder);
    }

    /** Thrown when any stage of the Microsoft login chain fails. */
    public static final class AuthenticationException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
