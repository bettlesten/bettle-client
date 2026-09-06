package net.bettleclient;

import javafx.application.Application;
import net.bettleclient.config.LauncherConfig;
import net.bettleclient.ui.BettleUI;
import net.bettleclient.util.OperatingSystem;
import net.bettleclient.version.VersionEntry;
import net.bettleclient.version.VersionManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Application entry point.
 *
 * <p>This class deliberately does <em>not</em> extend
 * {@link javafx.application.Application}. When a JavaFX application's main
 * class extends {@code Application}, the JVM refuses to start it from a plain
 * shaded jar because the JavaFX runtime components are on the classpath rather
 * than the module path. Bootstrapping through an ordinary class sidesteps that,
 * so {@code java -jar BettleClient-1.0.0.jar} works without any extra flags.</p>
 *
 * <h2>Command line</h2>
 * <pre>
 *   java -jar BettleClient.jar                 launch the dashboard
 *   java -jar BettleClient.jar --versions      print supported versions and exit
 *   java -jar BettleClient.jar --info          print environment info and exit
 *   java -jar BettleClient.jar --help          print usage and exit
 * </pre>
 */
public final class Main {

    public static final String NAME = "Bettle Client";
    public static final String VERSION = "1.0.0";

    private Main() {
    }

    public static void main(String[] args) {
        installExceptionHandler();

        for (String argument : args) {
            switch (argument) {
                case "--help":
                case "-h":
                    printUsage();
                    return;
                case "--versions":
                    printSupportedVersions();
                    return;
                case "--info":
                    printEnvironment();
                    return;
                default:
                    break;
            }
        }

        Path root = bootstrapDirectories();
        LauncherConfig config = LauncherConfig.load(root);
        config.save(); // Materialise defaults on first run.

        System.out.println(NAME + " " + VERSION);
        System.out.println("Launcher directory: " + root);
        System.out.println("Platform: " + OperatingSystem.current().getMojangName()
                + "/" + OperatingSystem.currentArch()
                + ", Java " + OperatingSystem.currentJavaMajorVersion());

        // Software rendering fallback keeps the launcher usable on machines
        // with broken or missing GPU drivers.
        if (System.getProperty("prism.order") == null && Boolean.getBoolean("bettle.softwareRender")) {
            System.setProperty("prism.order", "sw");
        }
        System.setProperty("prism.lcdtext", "false");

        Application.launch(BettleUI.class, args);
    }

    /** Creates the launcher directory tree on first run. */
    private static Path bootstrapDirectories() {
        Path root = OperatingSystem.defaultLauncherRoot();
        String[] children = {"versions", "libraries", "assets", "instances", "logs"};
        try {
            Files.createDirectories(root);
            for (String child : children) {
                Files.createDirectories(root.resolve(child));
            }
        } catch (IOException e) {
            System.err.println("Could not create launcher directories under " + root
                    + ": " + e.getMessage());
        }
        return root;
    }

    private static void installExceptionHandler() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            System.err.println("Uncaught exception on thread " + thread.getName() + ":");
            throwable.printStackTrace();
        });
    }

    private static void printUsage() {
        System.out.println(NAME + " " + VERSION);
        System.out.println();
        System.out.println("Usage: java -jar BettleClient.jar [option]");
        System.out.println();
        System.out.println("  (no option)   Start the launcher dashboard");
        System.out.println("  --versions    Print every supported Minecraft version and exit");
        System.out.println("  --info        Print environment information and exit");
        System.out.println("  --help, -h    Print this message and exit");
        System.out.println();
        System.out.println("System properties:");
        System.out.println("  -Dbettle.softwareRender=true   Force software rendering");
    }

    /**
     * Headless smoke test of the dynamic version engine: fetches Mojang's
     * manifest and prints the range the launcher supports.
     */
    private static void printSupportedVersions() {
        Path root = bootstrapDirectories();
        VersionManager versionManager = new VersionManager(root);
        try {
            List<VersionEntry> all = versionManager.load();
            List<VersionEntry> supported = versionManager.getSupportedReleases();

            System.out.println("Manifest: " + all.size() + " versions"
                    + (versionManager.isLoadedFromCache() ? " (cached)" : ""));
            System.out.println("Latest release: " + versionManager.getLatestReleaseId());
            System.out.println("Latest snapshot: " + versionManager.getLatestSnapshotId());
            System.out.println("Supported window: " + VersionManager.MIN_SUPPORTED_VERSION
                    + " through " + VersionManager.MAX_SUPPORTED_MAJOR + ".x");
            System.out.println("Supported releases (" + supported.size() + "):");
            for (VersionEntry entry : supported) {
                System.out.println("  " + entry.getId() + "  [" + entry.getType() + "]");
            }
        } catch (IOException e) {
            System.err.println("Failed to load the version manifest: " + e.getMessage());
        }
    }

    private static void printEnvironment() {
        Path root = OperatingSystem.defaultLauncherRoot();
        System.out.println(NAME + " " + VERSION);
        System.out.println("  OS              : " + System.getProperty("os.name")
                + " (" + OperatingSystem.current().getMojangName() + ")");
        System.out.println("  Architecture    : " + System.getProperty("os.arch")
                + " (" + OperatingSystem.currentArch() + ")");
        System.out.println("  OS version      : " + OperatingSystem.osVersion());
        System.out.println("  Java            : " + System.getProperty("java.version")
                + " at " + System.getProperty("java.home"));
        System.out.println("  Java executable : " + OperatingSystem.currentJavaExecutable());
        System.out.println("  Physical memory : " + OperatingSystem.totalPhysicalMemoryMb() + " MB");
        System.out.println("  Launcher root   : " + root);
        System.out.println("  Manifest URL    : " + VersionManager.MANIFEST_URL);
    }
}
