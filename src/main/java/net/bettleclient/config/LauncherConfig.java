package net.bettleclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bettleclient.util.OperatingSystem;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Persisted launcher settings, stored as {@code config.json} in the launcher
 * root directory. Every field has a working default, so a missing or corrupt
 * config file is never fatal.
 */
public final class LauncherConfig {

    private static final String FILE_NAME = "config.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ---- persisted fields (names are the JSON keys) ----
    private String lastVersionId;
    private String lastServerAddress = "";
    private int minMemoryMb = 1024;
    private int maxMemoryMb = 4096;
    private String javaExecutable = "";
    private String gameDirectory = "";
    private String extraJvmArguments = "";
    private int windowWidth = 854;
    private int windowHeight = 480;
    private boolean fullscreen = false;
    private boolean showSnapshots = false;
    private String modLoader = "VANILLA";
    private boolean keepLauncherOpen = true;
    private boolean useSeparateInstanceDirectories = true;

    private transient Path file;

    // ------------------------------------------------------------------
    // Load / save
    // ------------------------------------------------------------------

    /** Reads {@code config.json}, returning defaults when it is absent or invalid. */
    public static LauncherConfig load(Path launcherRoot) {
        Path file = launcherRoot.resolve(FILE_NAME);
        LauncherConfig config = null;
        if (Files.isRegularFile(file)) {
            try {
                String body = Files.readString(file, StandardCharsets.UTF_8);
                config = GSON.fromJson(body, LauncherConfig.class);
            } catch (Exception ignored) {
                // Fall through to defaults.
            }
        }
        if (config == null) {
            config = new LauncherConfig();
        }
        config.file = file;
        config.applySensibleDefaults();
        return config;
    }

    /** Writes the current values back to disk. Failures are swallowed. */
    public void save() {
        if (file == null) {
            return;
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // A settings write failure must never block launching.
        }
    }

    /** Clamps memory to the machine's real capacity and fixes empty fields. */
    private void applySensibleDefaults() {
        long physicalMb = OperatingSystem.totalPhysicalMemoryMb();
        int ceiling = (int) Math.max(2048L, Math.min(physicalMb, 65536L));

        if (maxMemoryMb <= 0) {
            maxMemoryMb = (int) Math.min(4096L, Math.max(2048L, physicalMb / 2));
        }
        maxMemoryMb = Math.max(1024, Math.min(maxMemoryMb, ceiling));

        if (minMemoryMb <= 0) {
            minMemoryMb = Math.min(1024, maxMemoryMb);
        }
        minMemoryMb = Math.max(256, Math.min(minMemoryMb, maxMemoryMb));

        if (windowWidth <= 0) {
            windowWidth = 854;
        }
        if (windowHeight <= 0) {
            windowHeight = 480;
        }
        if (lastServerAddress == null) {
            lastServerAddress = "";
        }
        if (javaExecutable == null) {
            javaExecutable = "";
        }
        if (gameDirectory == null) {
            gameDirectory = "";
        }
        if (extraJvmArguments == null) {
            extraJvmArguments = "";
        }
        if (modLoader == null || modLoader.isBlank()) {
            modLoader = "VANILLA";
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public String getLastVersionId() {
        return lastVersionId;
    }

    public void setLastVersionId(String lastVersionId) {
        this.lastVersionId = lastVersionId;
    }

    public String getLastServerAddress() {
        return lastServerAddress == null ? "" : lastServerAddress;
    }

    public void setLastServerAddress(String lastServerAddress) {
        this.lastServerAddress = lastServerAddress == null ? "" : lastServerAddress;
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = Math.max(1024, maxMemoryMb);
        if (this.minMemoryMb > this.maxMemoryMb) {
            this.minMemoryMb = this.maxMemoryMb;
        }
    }

    /** Configured JVM path, or {@code null} to use the launcher's own JVM. */
    public Path getJavaExecutablePath() {
        return StringUtils.isBlank(javaExecutable) ? null : Path.of(javaExecutable);
    }

    public String getJavaExecutable() {
        return javaExecutable == null ? "" : javaExecutable;
    }

    public void setJavaExecutable(String javaExecutable) {
        this.javaExecutable = javaExecutable == null ? "" : javaExecutable.trim();
    }

    /** Configured game directory override, or {@code null} for per-version instances. */
    public Path getGameDirectoryPath() {
        return StringUtils.isBlank(gameDirectory) ? null : Path.of(gameDirectory);
    }

    public String getGameDirectory() {
        return gameDirectory == null ? "" : gameDirectory;
    }

    public void setGameDirectory(String gameDirectory) {
        this.gameDirectory = gameDirectory == null ? "" : gameDirectory.trim();
    }

    public String getExtraJvmArguments() {
        return extraJvmArguments == null ? "" : extraJvmArguments;
    }

    public void setExtraJvmArguments(String extraJvmArguments) {
        this.extraJvmArguments = extraJvmArguments == null ? "" : extraJvmArguments;
    }

    /** The extra JVM argument string split into individual tokens. */
    public List<String> getExtraJvmArgumentList() {
        if (StringUtils.isBlank(extraJvmArguments)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(StringUtils.split(extraJvmArguments.trim())));
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public void setWindowWidth(int windowWidth) {
        this.windowWidth = windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    public void setWindowHeight(int windowHeight) {
        this.windowHeight = windowHeight;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    /** Persisted {@link net.bettleclient.install.ModLoader} name. */
    public String getModLoader() {
        return modLoader == null ? "VANILLA" : modLoader;
    }

    public void setModLoader(String modLoader) {
        this.modLoader = (modLoader == null || modLoader.isBlank()) ? "VANILLA" : modLoader;
    }

    public boolean isShowSnapshots() {
        return showSnapshots;
    }

    public void setShowSnapshots(boolean showSnapshots) {
        this.showSnapshots = showSnapshots;
    }

    public boolean isKeepLauncherOpen() {
        return keepLauncherOpen;
    }

    public void setKeepLauncherOpen(boolean keepLauncherOpen) {
        this.keepLauncherOpen = keepLauncherOpen;
    }

    public boolean isUseSeparateInstanceDirectories() {
        return useSeparateInstanceDirectories;
    }

    public void setUseSeparateInstanceDirectories(boolean useSeparateInstanceDirectories) {
        this.useSeparateInstanceDirectories = useSeparateInstanceDirectories;
    }

    /** Upper bound offered by the RAM slider, in megabytes. */
    public int getMemoryCeilingMb() {
        long physicalMb = OperatingSystem.totalPhysicalMemoryMb();
        return (int) Math.max(4096L, Math.min(physicalMb, 65536L));
    }
}
