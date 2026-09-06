package net.bettleclient.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.LongConsumer;

/**
 * Small HTTP helper built on the JDK 11+ {@link HttpClient}.
 *
 * <p>Every download is optionally verified against the SHA-1 digest published
 * in Mojang's manifests and retried a bounded number of times. A partially
 * written file is staged next to its destination and moved into place only
 * after verification succeeds, so an interrupted download can never leave a
 * corrupt artifact behind.</p>
 */
public final class HttpUtil {

    public static final String USER_AGENT = "BettleClient/1.0.0 (+https://github.com/bettleclient)";

    private static final int MAX_ATTEMPTS = 4;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private HttpUtil() {
    }

    // ------------------------------------------------------------------
    // Simple GETs
    // ------------------------------------------------------------------

    /** Fetches a URL as a UTF-8 string, retrying transient failures. */
    public static String getString(String url) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json, text/plain, */*")
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int code = response.statusCode();
                if (code / 100 == 2) {
                    return response.body();
                }
                last = new IOException("HTTP " + code + " for " + url);
                if (code / 100 == 4 && code != 408 && code != 429) {
                    throw last; // client error: retrying will not help
                }
            } catch (IOException e) {
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while fetching " + url, e);
            }
            backoff(attempt);
        }
        throw last != null ? last : new IOException("Failed to fetch " + url);
    }

    /** Fetches a URL and parses the body as a JSON object. */
    public static JsonObject getJsonObject(String url) throws IOException {
        JsonElement element = JsonParser.parseString(getString(url));
        if (!element.isJsonObject()) {
            throw new IOException("Expected a JSON object from " + url);
        }
        return element.getAsJsonObject();
    }

    /** Fetches a URL as raw bytes (used for skin/avatar images). */
    public static byte[] getBytes(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<byte[]> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    // ------------------------------------------------------------------
    // Verified downloads
    // ------------------------------------------------------------------

    /**
     * Downloads {@code url} to {@code destination}, skipping the transfer when
     * a valid copy already exists.
     *
     * @param expectedSha1  SHA-1 from the manifest, or {@code null} to skip verification
     * @param expectedSize  size in bytes from the manifest, or {@code -1} if unknown
     * @param bytesConsumer receives the number of bytes transferred for each chunk
     * @return {@code true} when bytes were actually transferred
     */
    public static boolean download(String url,
                                   Path destination,
                                   String expectedSha1,
                                   long expectedSize,
                                   LongConsumer bytesConsumer) throws IOException {

        if (isValid(destination, expectedSha1, expectedSize)) {
            if (bytesConsumer != null && expectedSize > 0) {
                bytesConsumer.accept(expectedSize);
            }
            return false;
        }

        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Path temp = destination.resolveSibling(destination.getFileName() + ".part");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<InputStream> response =
                        CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() / 100 != 2) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }

                try (InputStream in = response.body();
                     java.io.OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (bytesConsumer != null) {
                            bytesConsumer.accept(read);
                        }
                    }
                }

                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    String actual = sha1(temp);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        throw new IOException("SHA-1 mismatch for " + url
                                + " (expected " + expectedSha1 + ", got " + actual + ")");
                    }
                }

                Files.move(temp, destination,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return true;

            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(temp);
            } catch (InterruptedException e) {
                Files.deleteIfExists(temp);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while downloading " + url, e);
            } catch (UnsupportedOperationException e) {
                // ATOMIC_MOVE is not supported across some filesystems.
                try {
                    Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
                    return true;
                } catch (IOException moveFailure) {
                    last = moveFailure;
                }
            }
            backoff(attempt);
        }
        throw last != null ? last : new IOException("Failed to download " + url);
    }

    /** Convenience overload without progress reporting. */
    public static boolean download(String url, Path destination, String expectedSha1) throws IOException {
        return download(url, destination, expectedSha1, -1L, null);
    }

    // ------------------------------------------------------------------
    // Integrity helpers
    // ------------------------------------------------------------------

    /** True when the file exists and matches the expected digest and/or size. */
    public static boolean isValid(Path file, String expectedSha1, long expectedSize) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            if (expectedSize > 0 && Files.size(file) != expectedSize) {
                return false;
            }
            if (expectedSha1 != null && !expectedSha1.isBlank()) {
                return sha1(file).equalsIgnoreCase(expectedSha1);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Computes the lowercase hexadecimal SHA-1 digest of a file. */
    public static String sha1(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException("SHA-1 unavailable on this JVM", e));
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4000L, 250L * (1L << Math.min(attempt, 4))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
