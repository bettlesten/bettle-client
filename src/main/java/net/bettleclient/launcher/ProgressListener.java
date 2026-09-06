package net.bettleclient.launcher;

/**
 * Callbacks emitted by {@link MinecraftLauncher} while it installs and starts a
 * version. All methods are default no-ops, so a caller only overrides what it
 * needs.
 *
 * <p>Every callback is invoked on the launcher's worker thread. UI code must
 * bounce updates onto the JavaFX application thread with
 * {@code Platform.runLater}.</p>
 */
public interface ProgressListener {

    /** A listener that discards everything. */
    ProgressListener NOOP = new ProgressListener() {
    };

    /** A new phase started, e.g. {@code "Downloading libraries"}. */
    default void onStage(String stage) {
    }

    /**
     * Progress within the current stage.
     *
     * @param fraction 0.0 to 1.0, or a negative value for indeterminate
     */
    default void onProgress(double fraction) {
    }

    /** A diagnostic line from the launcher or from the game's stdout. */
    default void onLog(String line) {
    }

    /** The game process was spawned successfully. */
    default void onGameStarted(Process process) {
    }

    /** The game process ended. */
    default void onGameExited(int exitCode) {
    }

    /** Installation or launch failed. */
    default void onError(Throwable error) {
    }
}
