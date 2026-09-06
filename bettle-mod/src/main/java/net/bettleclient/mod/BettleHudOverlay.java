package net.bettleclient.mod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Bettle Client in-game HUD overlay (Fabric client mod).
 *
 * <p>Renders a compact crimson-accented panel with an FPS counter, left/right
 * CPS counters, ping, and coordinates, and provides a ToggleSprint helper.
 * Everything is drawn through Fabric API events, so the mod carries no mixins
 * and stays compatible across point releases.</p>
 *
 * <h2>Default key bindings</h2>
 * <ul>
 *   <li><b>V</b> - toggle the ToggleSprint (always-sprint) helper</li>
 *   <li><b>]</b> - show or hide the HUD panel</li>
 *   <li><b>[</b> - cycle the HUD between the four screen corners</li>
 * </ul>
 *
 * <h2>Version compatibility</h2>
 * <p>Targets Minecraft 1.20.1 - 1.21.1 through Yarn mappings. On 1.21.2 and
 * newer, Fabric API replaced {@code HudRenderCallback}'s {@code float tickDelta}
 * parameter with a {@code RenderTickCounter}; only the signature of
 * {@link #onHudRender} needs to change for those versions, and the
 * {@code HudLayerRegistrationCallback} API is the forward-looking replacement.</p>
 *
 * <h2>CPS measurement</h2>
 * <p>Mouse state is sampled once per rendered frame rather than once per game
 * tick, so the counter resolves clicks far faster than 20 Hz without needing a
 * mixin on the mouse handler. A click that begins and ends inside a single
 * frame is not counted, and clicks are only sampled while the game window has
 * focus.</p>
 */
@Environment(EnvType.CLIENT)
public class BettleHudOverlay implements ClientModInitializer {

    public static final String MOD_ID = "bettleclient";

    // ---- Bettle Red palette, as ARGB ----
    private static final int ACCENT = 0xFFFF0033;
    private static final int ACCENT_GLOW = 0xFFFF3355;
    private static final int PANEL_BACKGROUND = 0xB80D0D0D;
    private static final int PANEL_INNER = 0x991A0006;
    private static final int TEXT_PRIMARY = 0xFFF4EEF0;
    private static final int TEXT_MUTED = 0xFF9C8A8F;
    private static final int TEXT_GOOD = 0xFF2FE07A;
    private static final int TEXT_WARN = 0xFFFFC24D;

    private static final int PANEL_PADDING = 6;
    private static final int LINE_SPACING = 2;
    private static final int SCREEN_MARGIN = 6;

    private static final long CPS_WINDOW_MILLIS = 1000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ClickTracker leftClicks = new ClickTracker();
    private final ClickTracker rightClicks = new ClickTracker();

    private HudConfig config = new HudConfig();
    private Path configFile;

    private KeyBinding toggleSprintKey;
    private KeyBinding toggleHudKey;
    private KeyBinding cyclePositionKey;

    private boolean leftWasDown;
    private boolean rightWasDown;

    // ==================================================================
    // Initialisation
    // ==================================================================

    @Override
    public void onInitializeClient() {
        this.configFile = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID + ".json");
        loadConfig();

        this.toggleSprintKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".toggle_sprint",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category." + MOD_ID + ".main"));

        this.toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category." + MOD_ID + ".main"));

        this.cyclePositionKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key." + MOD_ID + ".cycle_position",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_BRACKET,
                "category." + MOD_ID + ".main"));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);
        HudRenderCallback.EVENT.register(this::onHudRender);

        System.out.println("[BettleClient] HUD overlay initialised");
    }

    // ==================================================================
    // Tick: key handling and ToggleSprint
    // ==================================================================

    private void onEndClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }

        boolean configDirty = false;

        while (toggleSprintKey.wasPressed()) {
            config.toggleSprintEnabled = !config.toggleSprintEnabled;
            configDirty = true;
            sendActionBar(client, "ToggleSprint " + (config.toggleSprintEnabled ? "ON" : "OFF"));
        }

        while (toggleHudKey.wasPressed()) {
            config.hudEnabled = !config.hudEnabled;
            configDirty = true;
        }

        while (cyclePositionKey.wasPressed()) {
            config.corner = (config.corner + 1) % 4;
            configDirty = true;
            sendActionBar(client, "HUD moved to " + HudConfig.cornerName(config.corner));
        }

        if (configDirty) {
            saveConfig();
        }

        applyToggleSprint(client);
    }

    /**
     * Holds the sprint key down while the player is moving forward, which is
     * what a ToggleSprint helper does. Deliberately inactive while a screen is
     * open, while sneaking, and while the player is flying so it never fights
     * with normal input.
     */
    private void applyToggleSprint(MinecraftClient client) {
        if (!config.toggleSprintEnabled) {
            return;
        }
        if (client.player == null || client.currentScreen != null) {
            return;
        }
        if (client.player.isSneaking() || client.player.isUsingItem()) {
            return;
        }
        if (!client.options.forwardKey.isPressed()) {
            return;
        }
        client.options.sprintKey.setPressed(true);
    }

    private void sendActionBar(MinecraftClient client, String message) {
        if (client.player == null) {
            return;
        }
        client.player.sendMessage(
                net.minecraft.text.Text.literal("§c[Bettle] §f" + message), true);
    }

    // ==================================================================
    // Render
    // ==================================================================

    /**
     * Draws the HUD panel.
     *
     * <p>On Minecraft 1.21.2+ the second parameter becomes a
     * {@code RenderTickCounter}; the body of this method is unchanged.</p>
     */
    private void onHudRender(DrawContext context, float tickDelta) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }

        // Sample clicks every frame for a responsive CPS reading.
        sampleMouse(client);

        if (!config.hudEnabled || client.options.hudHidden || client.currentScreen != null) {
            return;
        }

        TextRenderer textRenderer = client.textRenderer;
        if (textRenderer == null) {
            return;
        }

        List<HudLine> lines = buildLines(client);
        if (lines.isEmpty()) {
            return;
        }

        int lineHeight = textRenderer.fontHeight + LINE_SPACING;
        int contentWidth = 0;
        for (HudLine line : lines) {
            contentWidth = Math.max(contentWidth, textRenderer.getWidth(line.text));
        }

        int panelWidth = contentWidth + PANEL_PADDING * 2;
        int panelHeight = lines.size() * lineHeight - LINE_SPACING + PANEL_PADDING * 2;

        int screenWidth = client.getWindow().getScaledWidth();
        int screenHeight = client.getWindow().getScaledHeight();

        int x = ((config.corner == HudConfig.TOP_LEFT) || (config.corner == HudConfig.BOTTOM_LEFT))
                ? SCREEN_MARGIN
                : screenWidth - panelWidth - SCREEN_MARGIN;
        int y = ((config.corner == HudConfig.TOP_LEFT) || (config.corner == HudConfig.TOP_RIGHT))
                ? SCREEN_MARGIN
                : screenHeight - panelHeight - SCREEN_MARGIN;

        drawPanel(context, x, y, panelWidth, panelHeight);

        int textX = x + PANEL_PADDING;
        int textY = y + PANEL_PADDING;
        for (HudLine line : lines) {
            context.drawTextWithShadow(textRenderer, line.text, textX, textY, line.color);
            textY += lineHeight;
        }
    }

    /**
     * Draws the crimson-accented container: a translucent body, a bright accent
     * bar down the leading edge, a hairline border, and notched corners that
     * read as rounded at GUI scale.
     */
    private void drawPanel(DrawContext context, int x, int y, int width, int height) {
        // Body.
        context.fill(x, y, x + width, y + height, PANEL_BACKGROUND);
        // Inner tint for depth.
        context.fill(x + 1, y + 1, x + width - 1, y + 1 + Math.max(2, height / 3), PANEL_INNER);

        // Hairline border.
        context.fill(x, y, x + width, y + 1, ACCENT);                      // top
        context.fill(x, y + height - 1, x + width, y + height, ACCENT);    // bottom
        context.fill(x, y, x + 1, y + height, ACCENT);                     // left
        context.fill(x + width - 1, y, x + width, y + height, ACCENT);     // right

        // Leading accent bar, on whichever side the panel is anchored to.
        boolean leftAnchored = config.corner == HudConfig.TOP_LEFT
                || config.corner == HudConfig.BOTTOM_LEFT;
        if (leftAnchored) {
            context.fill(x, y, x + 2, y + height, ACCENT_GLOW);
        } else {
            context.fill(x + width - 2, y, x + width, y + height, ACCENT_GLOW);
        }

        // Corner notches: knock out one pixel from each corner so the border
        // reads as a rounded rectangle rather than a hard box.
        context.fill(x, y, x + 1, y + 1, 0x00000000);
        context.fill(x + width - 1, y, x + width, y + 1, 0x00000000);
        context.fill(x, y + height - 1, x + 1, y + height, 0x00000000);
        context.fill(x + width - 1, y + height - 1, x + width, y + height, 0x00000000);
    }

    /** Builds the ordered list of HUD rows from the current client state. */
    private List<HudLine> buildLines(MinecraftClient client) {
        List<HudLine> lines = new ArrayList<>(6);

        if (config.showTitle) {
            lines.add(new HudLine("BETTLE", ACCENT));
        }

        if (config.showFps) {
            int fps = client.getCurrentFps();
            lines.add(new HudLine("FPS  " + fps, colorForFps(fps)));
        }

        if (config.showCps) {
            lines.add(new HudLine(
                    "CPS  " + leftClicks.countWithin(CPS_WINDOW_MILLIS)
                            + " | " + rightClicks.countWithin(CPS_WINDOW_MILLIS),
                    TEXT_PRIMARY));
        }

        if (config.showPing) {
            int ping = currentPing(client);
            lines.add(new HudLine(
                    ping < 0 ? "PING  --" : "PING  " + ping + "ms",
                    ping < 0 ? TEXT_MUTED : colorForPing(ping)));
        }

        if (config.showCoordinates && client.player != null) {
            lines.add(new HudLine(String.format("XYZ  %.0f %.0f %.0f",
                    client.player.getX(), client.player.getY(), client.player.getZ()),
                    TEXT_MUTED));
        }

        if (config.showToggleSprintState) {
            lines.add(new HudLine(
                    "SPRINT  " + (config.toggleSprintEnabled ? "ON" : "OFF"),
                    config.toggleSprintEnabled ? TEXT_GOOD : TEXT_MUTED));
        }

        return lines;
    }

    private static int colorForFps(int fps) {
        if (fps >= 120) {
            return TEXT_GOOD;
        }
        if (fps >= 45) {
            return TEXT_PRIMARY;
        }
        return TEXT_WARN;
    }

    private static int colorForPing(int ping) {
        if (ping <= 60) {
            return TEXT_GOOD;
        }
        if (ping <= 150) {
            return TEXT_PRIMARY;
        }
        return TEXT_WARN;
    }

    /** Latency reported by the server for this player, or -1 when unavailable. */
    private static int currentPing(MinecraftClient client) {
        try {
            if (client.getNetworkHandler() == null || client.player == null) {
                return -1;
            }
            PlayerListEntry entry = client.getNetworkHandler()
                    .getPlayerListEntry(client.player.getUuid());
            return entry == null ? -1 : entry.getLatency();
        } catch (Exception e) {
            return -1;
        }
    }

    // ==================================================================
    // CPS sampling
    // ==================================================================

    private void sampleMouse(MinecraftClient client) {
        if (client.getWindow() == null) {
            return;
        }
        long handle = client.getWindow().getHandle();

        boolean leftDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                == GLFW.GLFW_PRESS;

        if (leftDown && !leftWasDown) {
            leftClicks.record();
        }
        if (rightDown && !rightWasDown) {
            rightClicks.record();
        }

        leftWasDown = leftDown;
        rightWasDown = rightDown;
    }

    /** Fixed-capacity ring of click timestamps used to compute clicks per second. */
    private static final class ClickTracker {

        private static final int CAPACITY = 64;

        private final Deque<Long> timestamps = new ArrayDeque<>(CAPACITY);

        void record() {
            timestamps.addLast(System.currentTimeMillis());
            while (timestamps.size() > CAPACITY) {
                timestamps.removeFirst();
            }
        }

        int countWithin(long windowMillis) {
            long cutoff = System.currentTimeMillis() - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
            return timestamps.size();
        }
    }

    /** One rendered row: its text and its colour. */
    private static final class HudLine {

        final String text;
        final int color;

        HudLine(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    // ==================================================================
    // Config
    // ==================================================================

    /** Persisted HUD settings, written to {@code config/bettleclient.json}. */
    public static final class HudConfig {

        public static final int TOP_LEFT = 0;
        public static final int TOP_RIGHT = 1;
        public static final int BOTTOM_RIGHT = 2;
        public static final int BOTTOM_LEFT = 3;

        public boolean hudEnabled = true;
        public boolean showTitle = true;
        public boolean showFps = true;
        public boolean showCps = true;
        public boolean showPing = true;
        public boolean showCoordinates = true;
        public boolean showToggleSprintState = true;
        public boolean toggleSprintEnabled = false;
        public int corner = TOP_LEFT;

        static String cornerName(int corner) {
            switch (corner) {
                case TOP_RIGHT:
                    return "top right";
                case BOTTOM_RIGHT:
                    return "bottom right";
                case BOTTOM_LEFT:
                    return "bottom left";
                case TOP_LEFT:
                default:
                    return "top left";
            }
        }
    }

    private void loadConfig() {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            saveConfig();
            return;
        }
        try {
            String body = Files.readString(configFile, StandardCharsets.UTF_8);
            HudConfig loaded = GSON.fromJson(body, HudConfig.class);
            if (loaded != null) {
                this.config = loaded;
                if (this.config.corner < 0 || this.config.corner > 3) {
                    this.config.corner = HudConfig.TOP_LEFT;
                }
            }
        } catch (Exception e) {
            System.err.println("[BettleClient] Could not read HUD config, using defaults: "
                    + e.getMessage());
        }
    }

    private void saveConfig() {
        if (configFile == null) {
            return;
        }
        try {
            Path parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[BettleClient] Could not save HUD config: " + e.getMessage());
        }
    }

    /** The live HUD configuration, exposed for a future settings screen. */
    public HudConfig getConfig() {
        return config;
    }
}
