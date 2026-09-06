package net.bettleclient.util;

import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Host platform detection using exactly the identifiers that Mojang's version
 * manifests use inside {@code rules[].os} blocks.
 *
 * <p>Mojang names operating systems {@code windows}, {@code linux} and
 * {@code osx}; architectures are named {@code x86}, {@code x64}, {@code arm32}
 * and {@code arm64}.</p>
 */
public enum OperatingSystem {

    WINDOWS("windows", "dll"),
    LINUX("linux", "so"),
    OSX("osx", "dylib"),
    UNKNOWN("unknown", "");

    private static final OperatingSystem CURRENT = detect();
    private static final String ARCH = detectArch();

    private final String mojangName;
    private final String nativeExtension;

    OperatingSystem(String mojangName, String nativeExtension) {
        this.mojangName = mojangName;
        this.nativeExtension = nativeExtension;
    }

    /** The identifier Mojang uses for this OS in version JSON rules. */
    public String getMojangName() {
        return mojangName;
    }

    /** File extension used by native shared libraries on this platform. */
    public String getNativeExtension() {
        return nativeExtension;
    }

    public boolean isWindows() {
        return this == WINDOWS;
    }

    public boolean isOsx() {
        return this == OSX;
    }

    public boolean isLinux() {
        return this == LINUX;
    }

    // ------------------------------------------------------------------
    // Static accessors
    // ------------------------------------------------------------------

    public static OperatingSystem current() {
        return CURRENT;
    }

    /** Mojang-style architecture identifier: x86, x64, arm32 or arm64. */
    public static String currentArch() {
        return ARCH;
    }

    /** Raw {@code os.version} value, matched against rule regexes. */
    public static String osVersion() {
        return System.getProperty("os.version", "");
    }

    /** Path separator used when building the {@code -cp} argument. */
    public static String classpathSeparator() {
        return File.pathSeparator;
    }

    /**
     * Root directory for launcher data:
     * {@code %APPDATA%\.bettleclient} on Windows,
     * {@code ~/Library/Application Support/bettleclient} on macOS,
     * {@code ~/.bettleclient} elsewhere.
     */
    public static Path defaultLauncherRoot() {
        String home = SystemUtils.getUserHome().getAbsolutePath();
        switch (CURRENT) {
            case WINDOWS: {
                String appData = System.getenv("APPDATA");
                Path base = (appData != null && !appData.isBlank()) ? Paths.get(appData) : Paths.get(home);
                return base.resolve(".bettleclient");
            }
            case OSX:
                return Paths.get(home, "Library", "Application Support", "bettleclient");
            default:
                return Paths.get(home, ".bettleclient");
        }
    }

    /**
     * Locates the {@code java} executable belonging to a JVM installation
     * directory. Accepts either a JAVA_HOME style directory or a direct path
     * to the binary.
     */
    public static Path resolveJavaExecutable(Path javaHomeOrBinary) {
        if (javaHomeOrBinary == null) {
            return null;
        }
        if (Files.isRegularFile(javaHomeOrBinary)) {
            return javaHomeOrBinary;
        }
        Path bin = javaHomeOrBinary.resolve("bin");
        if (CURRENT.isWindows()) {
            Path javaw = bin.resolve("javaw.exe");
            if (Files.isRegularFile(javaw)) {
                return javaw;
            }
            Path java = bin.resolve("java.exe");
            if (Files.isRegularFile(java)) {
                return java;
            }
        } else {
            Path java = bin.resolve("java");
            if (Files.isRegularFile(java)) {
                return java;
            }
        }
        return null;
    }

    /** The JVM currently running the launcher, used as the default runtime. */
    public static Path currentJavaExecutable() {
        Path home = Paths.get(System.getProperty("java.home"));
        Path resolved = resolveJavaExecutable(home);
        return resolved != null ? resolved : home.resolve("bin").resolve("java");
    }

    /** Major feature version of the JVM running the launcher (e.g. 17, 21). */
    public static int currentJavaMajorVersion() {
        return Runtime.version().feature();
    }

    /** Total physical memory in megabytes, or 8192 when it cannot be probed. */
    public static long totalPhysicalMemoryMb() {
        try {
            java.lang.management.OperatingSystemMXBean bean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean) {
                long bytes = ((com.sun.management.OperatingSystemMXBean) bean).getTotalMemorySize();
                if (bytes > 0) {
                    return bytes / (1024L * 1024L);
                }
            }
        } catch (Throwable ignored) {
            // com.sun.management is not guaranteed on every JVM; fall through.
        }
        return 8192L;
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private static OperatingSystem detect() {
        String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (name.contains("win")) {
            return WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin") || name.contains("osx")) {
            return OSX;
        }
        if (name.contains("nix") || name.contains("nux") || name.contains("aix")
                || name.contains("bsd") || name.contains("sunos") || name.contains("solaris")) {
            return LINUX;
        }
        return UNKNOWN;
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "arm64";
        }
        if (arch.startsWith("arm")) {
            return "arm32";
        }
        if (arch.contains("64")) {
            return "x64";
        }
        return "x86";
    }
}
