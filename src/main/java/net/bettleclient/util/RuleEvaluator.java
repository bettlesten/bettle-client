package net.bettleclient.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates Mojang's {@code rules} arrays, which gate libraries and launch
 * arguments on the host platform and on launcher feature flags.
 *
 * <p>A rules array is processed top to bottom. Each entry carries an
 * {@code action} of {@code allow} or {@code disallow} plus optional
 * {@code os} and {@code features} predicates; the last matching entry wins.
 * An absent or empty rules array means "always allowed".</p>
 *
 * <pre>{@code
 * "rules": [
 *   { "action": "allow" },
 *   { "action": "disallow", "os": { "name": "osx" } }
 * ]
 * }</pre>
 */
public final class RuleEvaluator {

    /** Feature flags understood by 1.13+ argument rules. */
    public static final String FEATURE_DEMO_USER = "is_demo_user";
    public static final String FEATURE_CUSTOM_RESOLUTION = "has_custom_resolution";
    public static final String FEATURE_QUICK_PLAY_SUPPORT = "has_quick_plays_support";
    public static final String FEATURE_QUICK_PLAY_SINGLEPLAYER = "is_quick_play_singleplayer";
    public static final String FEATURE_QUICK_PLAY_MULTIPLAYER = "is_quick_play_multiplayer";
    public static final String FEATURE_QUICK_PLAY_REALMS = "is_quick_play_realms";

    private RuleEvaluator() {
    }

    /** Evaluates a rules array with no feature flags enabled. */
    public static boolean isAllowed(JsonArray rules) {
        return isAllowed(rules, Collections.emptyMap());
    }

    /**
     * Evaluates a rules array.
     *
     * @param rules    the {@code rules} array, may be {@code null}
     * @param features launcher feature flags; missing keys count as {@code false}
     */
    public static boolean isAllowed(JsonArray rules, Map<String, Boolean> features) {
        if (rules == null || rules.isEmpty()) {
            return true;
        }
        Map<String, Boolean> effective = features == null ? Collections.emptyMap() : features;

        boolean allowed = false;
        for (JsonElement element : rules) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject rule = element.getAsJsonObject();
            if (!matches(rule, effective)) {
                continue;
            }
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";
            allowed = "allow".equalsIgnoreCase(action);
        }
        return allowed;
    }

    /** True when a rule's {@code os} and {@code features} predicates all hold. */
    private static boolean matches(JsonObject rule, Map<String, Boolean> features) {
        if (rule.has("os") && rule.get("os").isJsonObject()) {
            if (!matchesOs(rule.getAsJsonObject("os"))) {
                return false;
            }
        }
        if (rule.has("features") && rule.get("features").isJsonObject()) {
            JsonObject required = rule.getAsJsonObject("features");
            for (Map.Entry<String, JsonElement> entry : required.entrySet()) {
                boolean wanted = entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isBoolean()
                        && entry.getValue().getAsBoolean();
                boolean actual = Boolean.TRUE.equals(features.get(entry.getKey()));
                if (wanted != actual) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean matchesOs(JsonObject os) {
        if (os.has("name")) {
            String expected = os.get("name").getAsString();
            if (!expected.equalsIgnoreCase(OperatingSystem.current().getMojangName())) {
                return false;
            }
        }
        if (os.has("arch")) {
            String expected = os.get("arch").getAsString();
            if (!archMatches(expected)) {
                return false;
            }
        }
        if (os.has("version")) {
            String regex = os.get("version").getAsString();
            try {
                if (!Pattern.compile(regex).matcher(OperatingSystem.osVersion()).find()) {
                    return false;
                }
            } catch (PatternSyntaxException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Mojang historically wrote {@code "arch": "x86"} to mean "32-bit only".
     * Newer manifests also use {@code x64}, {@code arm64} and {@code arm32}.
     */
    private static boolean archMatches(String expected) {
        String current = OperatingSystem.currentArch();
        if (expected.equalsIgnoreCase(current)) {
            return true;
        }
        if (expected.equalsIgnoreCase("x86_64") || expected.equalsIgnoreCase("amd64")) {
            return current.equals("x64");
        }
        if (expected.equalsIgnoreCase("aarch64")) {
            return current.equals("arm64");
        }
        return false;
    }
}
