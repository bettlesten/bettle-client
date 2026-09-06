package net.bettleclient.launcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-launch settings: where the game runs, which JVM runs it, how much heap it
 * gets, and what it should do on startup.
 *
 * <p>Built with a fluent builder:</p>
 * <pre>{@code
 * LaunchOptions options = LaunchOptions.builder()
 *         .gameDirectory(root.resolve("instances/1.21.4"))
 *         .memory(2048, 4096)
 *         .quickConnect("play.example.net", 25565)
 *         .build();
 * }</pre>
 */
public final class LaunchOptions {

    private final Path gameDirectory;
    private final Path javaExecutable;
    private final int minMemoryMb;
    private final int maxMemoryMb;
    private final List<String> extraJvmArguments;
    private final List<String> extraGameArguments;
    private final int windowWidth;
    private final int windowHeight;
    private final boolean fullscreen;
    private final boolean demo;
    private final String quickConnectHost;
    private final int quickConnectPort;

    private LaunchOptions(Builder builder) {
        this.gameDirectory = builder.gameDirectory;
        this.javaExecutable = builder.javaExecutable;
        this.minMemoryMb = builder.minMemoryMb;
        this.maxMemoryMb = builder.maxMemoryMb;
        this.extraJvmArguments = List.copyOf(builder.extraJvmArguments);
        this.extraGameArguments = List.copyOf(builder.extraGameArguments);
        this.windowWidth = builder.windowWidth;
        this.windowHeight = builder.windowHeight;
        this.fullscreen = builder.fullscreen;
        this.demo = builder.demo;
        this.quickConnectHost = builder.quickConnectHost;
        this.quickConnectPort = builder.quickConnectPort;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Value for {@code --gameDir}. */
    public Path getGameDirectory() {
        return gameDirectory;
    }

    /** JVM used to run the game, or {@code null} to reuse the launcher's JVM. */
    public Path getJavaExecutable() {
        return javaExecutable;
    }

    /** Value for {@code -Xms}, in megabytes. */
    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    /** Value for {@code -Xmx}, in megabytes. */
    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public List<String> getExtraJvmArguments() {
        return extraJvmArguments;
    }

    public List<String> getExtraGameArguments() {
        return extraGameArguments;
    }

    public int getWindowWidth() {
        return windowWidth;
    }

    public int getWindowHeight() {
        return windowHeight;
    }

    /** True when a non-default window size was requested. */
    public boolean hasCustomResolution() {
        return windowWidth > 0 && windowHeight > 0;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public boolean isDemo() {
        return demo;
    }

    /** Server to join straight after the game window opens, or {@code null}. */
    public String getQuickConnectHost() {
        return quickConnectHost;
    }

    public int getQuickConnectPort() {
        return quickConnectPort;
    }

    public boolean hasQuickConnect() {
        return quickConnectHost != null && !quickConnectHost.isBlank();
    }

    /** {@code host:port}, the form expected by {@code --quickPlayMultiplayer}. */
    public String getQuickConnectAddress() {
        if (!hasQuickConnect()) {
            return null;
        }
        return quickConnectPort > 0 && quickConnectPort != 25565
                ? quickConnectHost + ":" + quickConnectPort
                : quickConnectHost;
    }

    public static final class Builder {

        private Path gameDirectory;
        private Path javaExecutable;
        private int minMemoryMb = 1024;
        private int maxMemoryMb = 4096;
        private final List<String> extraJvmArguments = new ArrayList<>();
        private final List<String> extraGameArguments = new ArrayList<>();
        private int windowWidth = -1;
        private int windowHeight = -1;
        private boolean fullscreen;
        private boolean demo;
        private String quickConnectHost;
        private int quickConnectPort = 25565;

        public Builder gameDirectory(Path gameDirectory) {
            this.gameDirectory = gameDirectory;
            return this;
        }

        public Builder javaExecutable(Path javaExecutable) {
            this.javaExecutable = javaExecutable;
            return this;
        }

        public Builder memory(int minMb, int maxMb) {
            this.minMemoryMb = Math.max(256, minMb);
            this.maxMemoryMb = Math.max(this.minMemoryMb, maxMb);
            return this;
        }

        public Builder jvmArguments(List<String> arguments) {
            this.extraJvmArguments.clear();
            if (arguments != null) {
                this.extraJvmArguments.addAll(arguments);
            }
            return this;
        }

        public Builder addJvmArgument(String argument) {
            if (argument != null && !argument.isBlank()) {
                this.extraJvmArguments.add(argument);
            }
            return this;
        }

        public Builder gameArguments(List<String> arguments) {
            this.extraGameArguments.clear();
            if (arguments != null) {
                this.extraGameArguments.addAll(arguments);
            }
            return this;
        }

        public Builder resolution(int width, int height) {
            this.windowWidth = width;
            this.windowHeight = height;
            return this;
        }

        public Builder fullscreen(boolean fullscreen) {
            this.fullscreen = fullscreen;
            return this;
        }

        public Builder demo(boolean demo) {
            this.demo = demo;
            return this;
        }

        /**
         * Parses a user-entered address such as {@code play.example.net} or
         * {@code play.example.net:25566} into host and port.
         */
        public Builder quickConnect(String address) {
            if (address == null || address.isBlank()) {
                this.quickConnectHost = null;
                return this;
            }
            String trimmed = address.trim();
            int colon = trimmed.lastIndexOf(':');
            if (colon > 0 && colon < trimmed.length() - 1 && trimmed.indexOf(':') == colon) {
                try {
                    this.quickConnectPort = Integer.parseInt(trimmed.substring(colon + 1));
                    this.quickConnectHost = trimmed.substring(0, colon);
                    return this;
                } catch (NumberFormatException ignored) {
                    // Not a port suffix; treat the whole string as a host.
                }
            }
            this.quickConnectHost = trimmed;
            this.quickConnectPort = 25565;
            return this;
        }

        public Builder quickConnect(String host, int port) {
            this.quickConnectHost = host;
            this.quickConnectPort = port <= 0 ? 25565 : port;
            return this;
        }

        public LaunchOptions build() {
            if (gameDirectory == null) {
                throw new IllegalStateException("gameDirectory is required");
            }
            return new LaunchOptions(this);
        }
    }

    @Override
    public String toString() {
        return "LaunchOptions{gameDir=" + gameDirectory
                + ", java=" + (javaExecutable == null ? "<launcher jvm>" : javaExecutable)
                + ", memory=" + minMemoryMb + "M-" + maxMemoryMb + "M"
                + ", quickConnect=" + (hasQuickConnect() ? getQuickConnectAddress() : "none")
                + ", extraJvmArgs=" + (extraJvmArguments.isEmpty()
                        ? Collections.emptyList() : extraJvmArguments)
                + '}';
    }
}
