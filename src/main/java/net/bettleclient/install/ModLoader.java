package net.bettleclient.install;

/**
 * Which profile the launcher should build and start for a given Minecraft
 * version.
 */
public enum ModLoader {

    /** Unmodified Minecraft, launched straight from the Mojang manifest. */
    VANILLA("Vanilla", false),

    /**
     * Fabric Loader plus the bundled Bettle HUD mod and its Fabric API
     * dependency, installed automatically before launch.
     */
    FABRIC("Fabric + Bettle HUD", true);

    private final String displayName;
    private final boolean requiresFabric;

    ModLoader(String displayName, boolean requiresFabric) {
        this.displayName = displayName;
        this.requiresFabric = requiresFabric;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean requiresFabric() {
        return requiresFabric;
    }

    /** Suffix appended to the instance directory so profiles stay separate. */
    public String getInstanceSuffix() {
        return this == VANILLA ? "" : "-fabric";
    }

    /** Parses a persisted value, falling back to {@link #VANILLA}. */
    public static ModLoader fromString(String value) {
        if (value == null) {
            return VANILLA;
        }
        for (ModLoader loader : values()) {
            if (loader.name().equalsIgnoreCase(value.trim())) {
                return loader;
            }
        }
        return VANILLA;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
