package net.bettleclient.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import net.bettleclient.auth.MicrosoftAuthenticator;
import net.bettleclient.auth.MinecraftSession;
import net.bettleclient.config.LauncherConfig;
import net.bettleclient.install.FabricInstaller;
import net.bettleclient.install.ModInstaller;
import net.bettleclient.install.ModLoader;
import net.bettleclient.launcher.LaunchOptions;
import net.bettleclient.launcher.MinecraftLauncher;
import net.bettleclient.launcher.ProgressListener;
import net.bettleclient.util.OperatingSystem;
import net.bettleclient.version.VersionEntry;
import net.bettleclient.version.VersionManager;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The Bettle Client dashboard: a frameless, crimson glassmorphism JavaFX
 * application.
 *
 * <p>Palette, mirrored in {@code theme.css}:</p>
 * <ul>
 *   <li>{@code #FF0033} - primary crimson</li>
 *   <li>{@code #0D0D0D} - base background</li>
 *   <li>{@code #1A0006} - secondary container</li>
 *   <li>{@code #FF3355} - glow / hover highlight</li>
 * </ul>
 *
 * <p>Every network or process operation runs on a background executor; the UI
 * is only ever touched from the JavaFX application thread via
 * {@link Platform#runLater(Runnable)}.</p>
 */
public final class BettleUI extends Application {

    // ---- palette ----
    public static final String COLOR_PRIMARY = "#FF0033";
    public static final String COLOR_BASE = "#0D0D0D";
    public static final String COLOR_CONTAINER = "#1A0006";
    public static final String COLOR_GLOW = "#FF3355";

    private static final double WINDOW_WIDTH = 1120;
    private static final double WINDOW_HEIGHT = 700;
    private static final double SHADOW_PADDING = 22;

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CONSOLE_CHARACTERS = 400_000;

    // ---- services ----
    private LauncherConfig config;
    private VersionManager versionManager;
    private MicrosoftAuthenticator authenticator;
    private MinecraftLauncher launcher;
    private FabricInstaller fabricInstaller;
    private ModInstaller modInstaller;
    private Path launcherRoot;
    private ExecutorService worker;

    // ---- state ----
    private volatile MinecraftSession session;
    private volatile boolean launching;

    // ---- window ----
    private Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;

    // ---- controls ----
    private final ObservableList<VersionEntry> versionItems = FXCollections.observableArrayList();
    private ComboBox<VersionEntry> versionSelector;
    private ComboBox<ModLoader> loaderSelector;
    private Label loaderHintLabel;
    private CheckBox snapshotToggle;
    private Button playButton;
    private Button accountButton;
    private Button refreshVersionsButton;
    private TextField serverField;
    private Slider memorySlider;
    private Label memoryLabel;
    private ProgressBar progressBar;
    private Label statusLabel;
    private Label usernameLabel;
    private Label accountStatusLabel;
    private Circle statusDot;
    private ImageView avatarView;
    private TextArea consoleArea;
    private StackPane contentStack;
    private VBox homeView;
    private VBox settingsView;
    private VBox consoleView;
    private final ObservableList<Button> navButtons = FXCollections.observableArrayList();
    private Timeline playGlowAnimation;

    // ---- settings controls ----
    private TextField gameDirField;
    private TextField javaPathField;
    private TextField jvmArgsField;
    private TextField widthField;
    private TextField heightField;
    private CheckBox fullscreenBox;
    private CheckBox keepOpenBox;

    // ==================================================================
    // Lifecycle
    // ==================================================================

    @Override
    public void init() {
        this.launcherRoot = OperatingSystem.defaultLauncherRoot();
        this.config = LauncherConfig.load(launcherRoot);
        this.versionManager = new VersionManager(launcherRoot);
        this.authenticator = new MicrosoftAuthenticator(launcherRoot);
        this.launcher = new MinecraftLauncher(launcherRoot, versionManager);
        this.fabricInstaller = new FabricInstaller(launcher.getVersionsDirectory());
        this.modInstaller = new ModInstaller();
        this.worker = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "Bettle-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;

        BorderPane window = new BorderPane();
        window.getStyleClass().add("app-window");
        window.setTop(buildTitleBar());
        window.setLeft(buildSidebar());
        window.setCenter(buildContent());

        StackPane shadowHost = new StackPane(window);
        shadowHost.setPadding(new Insets(SHADOW_PADDING));
        shadowHost.setStyle("-fx-background-color: transparent;");
        window.setEffect(new DropShadow(46, 0, 12, Color.web(COLOR_PRIMARY, 0.34)));

        Scene scene = new Scene(shadowHost,
                WINDOW_WIDTH + SHADOW_PADDING * 2,
                WINDOW_HEIGHT + SHADOW_PADDING * 2);
        scene.setFill(Color.TRANSPARENT);
        applyStylesheet(scene);

        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setTitle("Bettle Client");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(940);
        primaryStage.setMinHeight(620);
        primaryStage.setOnCloseRequest(event -> shutdown());
        primaryStage.show();
        primaryStage.centerOnScreen();

        showView(homeView, 0);
        appendLog("Bettle Client " + MinecraftLauncher.LAUNCHER_VERSION + " starting up");
        appendLog("Launcher directory: " + launcherRoot);
        appendLog("Platform: " + OperatingSystem.current().getMojangName()
                + " / " + OperatingSystem.currentArch()
                + " | Java " + OperatingSystem.currentJavaMajorVersion());

        loadVersionsAsync();
        restoreSessionAsync();
    }

    @Override
    public void stop() {
        shutdown();
    }

    private void shutdown() {
        persistSettings();
        if (worker != null) {
            worker.shutdownNow();
            try {
                worker.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Platform.exit();
    }

    private void applyStylesheet(Scene scene) {
        java.net.URL stylesheet = BettleUI.class.getResource("/net/bettleclient/ui/theme.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        } else {
            // The stylesheet is packaged with the jar; if it is somehow absent
            // the app still runs, just without the crimson theme.
            appendLog("WARNING: theme.css could not be located on the classpath");
        }
    }

    // ==================================================================
    // Title bar
    // ==================================================================

    private Region buildTitleBar() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(0, 12, 0, 18));
        bar.setMinHeight(52);
        bar.setPrefHeight(52);

        StackPane logo = new StackPane();
        Circle outerRing = new Circle(11);
        outerRing.getStyleClass().add("logo-ring");
        Circle innerDot = new Circle(5);
        innerDot.getStyleClass().add("logo-core");
        innerDot.setEffect(new DropShadow(14, Color.web(COLOR_GLOW)));
        logo.getChildren().addAll(outerRing, innerDot);

        Label title = new Label("BETTLE");
        title.getStyleClass().add("title-brand");

        Label accent = new Label("CLIENT");
        accent.getStyleClass().add("title-brand-accent");

        Label versionTag = new Label("v" + MinecraftLauncher.LAUNCHER_VERSION);
        versionTag.getStyleClass().add("title-version");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton("–", "window-button", () -> stage.setIconified(true));
        Button maximize = windowButton("□", "window-button",
                () -> stage.setMaximized(!stage.isMaximized()));
        Button close = windowButton("✕", "window-button-close", this::shutdown);

        bar.getChildren().addAll(logo, title, accent, versionTag, spacer, minimize, maximize, close);

        // Frameless window dragging.
        bar.setOnMousePressed((MouseEvent event) -> {
            dragOffsetX = event.getScreenX() - stage.getX();
            dragOffsetY = event.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged((MouseEvent event) -> {
            if (stage.isMaximized()) {
                return;
            }
            stage.setX(event.getScreenX() - dragOffsetX);
            stage.setY(event.getScreenY() - dragOffsetY);
        });
        bar.setOnMouseClicked((MouseEvent event) -> {
            if (event.getClickCount() == 2) {
                stage.setMaximized(!stage.isMaximized());
            }
        });

        return bar;
    }

    private Button windowButton(String glyph, String styleClass, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().add(styleClass);
        button.setFocusTraversable(false);
        button.setOnAction(event -> action.run());
        return button;
    }

    // ==================================================================
    // Sidebar
    // ==================================================================

    private Region buildSidebar() {
        VBox sidebar = new VBox(6);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(22, 14, 18, 14));
        sidebar.setPrefWidth(196);
        sidebar.setMinWidth(196);

        Label header = new Label("DASHBOARD");
        header.getStyleClass().add("sidebar-header");
        VBox.setMargin(header, new Insets(0, 0, 10, 8));

        Button home = navButton("Home", true);
        Button settings = navButton("Settings", false);
        Button console = navButton("Console", false);

        home.setOnAction(event -> {
            selectNav(home);
            showView(homeView, 0);
        });
        settings.setOnAction(event -> {
            selectNav(settings);
            showView(settingsView, 1);
        });
        console.setOnAction(event -> {
            selectNav(console);
            showView(consoleView, 2);
        });

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label footer = new Label("Bettle Client\nCrimson Edition");
        footer.getStyleClass().add("sidebar-footer");

        sidebar.getChildren().addAll(header, home, settings, console, spacer, footer);
        return sidebar;
    }

    private Button navButton(String text, boolean active) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        if (active) {
            button.getStyleClass().add("nav-button-active");
        }
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setFocusTraversable(false);
        navButtons.add(button);
        return button;
    }

    private void selectNav(Button selected) {
        for (Button button : navButtons) {
            button.getStyleClass().remove("nav-button-active");
        }
        if (!selected.getStyleClass().contains("nav-button-active")) {
            selected.getStyleClass().add("nav-button-active");
        }
    }

    // ==================================================================
    // Content
    // ==================================================================

    private Region buildContent() {
        homeView = buildHomeView();
        settingsView = buildSettingsView();
        consoleView = buildConsoleView();

        contentStack = new StackPane(homeView, settingsView, consoleView);
        contentStack.getStyleClass().add("content-stack");
        contentStack.setPadding(new Insets(20, 24, 22, 8));
        return contentStack;
    }

    private void showView(Node view, int index) {
        for (Node node : contentStack.getChildren()) {
            node.setVisible(false);
            node.setManaged(false);
        }
        view.setVisible(true);
        view.setManaged(true);
        view.toFront();

        FadeTransition fade = new FadeTransition(Duration.millis(180), view);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    // ---------------------------- Home ----------------------------

    private VBox buildHomeView() {
        VBox root = new VBox(18);
        root.setFillWidth(true);

        root.getChildren().addAll(
                buildProfileCard(),
                buildLaunchCard(),
                buildProgressCard()
        );
        return root;
    }

    private Region buildProfileCard() {
        HBox card = new HBox(18);
        card.getStyleClass().addAll("glass-card", "profile-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(18, 22, 18, 22));

        StackPane avatarHolder = new StackPane();
        avatarHolder.getStyleClass().add("avatar-holder");
        avatarHolder.setMinSize(66, 66);
        avatarHolder.setPrefSize(66, 66);
        avatarHolder.setMaxSize(66, 66);

        avatarView = new ImageView();
        avatarView.setFitWidth(52);
        avatarView.setFitHeight(52);
        avatarView.setPreserveRatio(true);
        avatarView.setSmooth(false);

        Rectangle placeholder = new Rectangle(52, 52);
        placeholder.getStyleClass().add("avatar-placeholder");
        avatarHolder.getChildren().addAll(placeholder, avatarView);

        VBox identity = new VBox(4);
        usernameLabel = new Label("Not signed in");
        usernameLabel.getStyleClass().add("profile-name");

        HBox statusRow = new HBox(8);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusDot = new Circle(4.5);
        statusDot.getStyleClass().addAll("status-dot", "status-dot-offline");
        accountStatusLabel = new Label("Microsoft account required for online play");
        accountStatusLabel.getStyleClass().add("profile-status");
        statusRow.getChildren().addAll(statusDot, accountStatusLabel);

        identity.getChildren().addAll(usernameLabel, statusRow);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        accountButton = new Button("SIGN IN WITH MICROSOFT");
        accountButton.getStyleClass().add("secondary-button");
        accountButton.setOnAction(event -> {
            if (session == null) {
                startDeviceCodeLogin();
            } else {
                signOut();
            }
        });

        card.getChildren().addAll(avatarHolder, identity, spacer, accountButton);
        return card;
    }

    private Region buildLaunchCard() {
        VBox card = new VBox(16);
        card.getStyleClass().add("glass-card");
        card.setPadding(new Insets(20, 22, 22, 22));

        Label heading = new Label("LAUNCH CONFIGURATION");
        heading.getStyleClass().add("card-heading");

        // ---- version row ----
        VBox versionBlock = new VBox(7);
        Label versionLabel = new Label("Minecraft Version");
        versionLabel.getStyleClass().add("field-label");

        versionSelector = new ComboBox<>(versionItems);
        versionSelector.getStyleClass().add("version-combo");
        versionSelector.setPrefWidth(280);
        versionSelector.setPromptText("Loading versions...");
        versionSelector.setCellFactory(list -> new VersionCell());
        versionSelector.setButtonCell(new VersionCell());
        versionSelector.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                config.setLastVersionId(newValue.getId());
                updatePlayButtonState();
            }
        });

        snapshotToggle = new CheckBox("Include snapshots");
        snapshotToggle.getStyleClass().add("bettle-check");
        snapshotToggle.setSelected(config.isShowSnapshots());
        snapshotToggle.setOnAction(event -> {
            config.setShowSnapshots(snapshotToggle.isSelected());
            populateVersionSelector();
        });

        refreshVersionsButton = new Button("Refresh");
        refreshVersionsButton.getStyleClass().add("ghost-button");
        refreshVersionsButton.setTooltip(new Tooltip("Re-fetch Mojang's version manifest"));
        refreshVersionsButton.setOnAction(event -> loadVersionsAsync(true));

        HBox versionRow = new HBox(12, versionSelector, snapshotToggle, refreshVersionsButton);
        versionRow.setAlignment(Pos.CENTER_LEFT);
        versionBlock.getChildren().addAll(versionLabel, versionRow);

        // ---- mod loader row ----
        VBox loaderBlock = new VBox(7);
        Label loaderLabel = new Label("Profile");
        loaderLabel.getStyleClass().add("field-label");

        loaderSelector = new ComboBox<>(FXCollections.observableArrayList(ModLoader.values()));
        loaderSelector.getStyleClass().add("version-combo");
        loaderSelector.setPrefWidth(280);
        loaderSelector.setValue(ModLoader.fromString(config.getModLoader()));
        loaderSelector.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                config.setModLoader(newValue.name());
                updateLoaderHint();
            }
        });

        loaderHintLabel = new Label();
        loaderHintLabel.getStyleClass().add("profile-status");
        loaderHintLabel.setWrapText(true);

        HBox loaderRow = new HBox(12, loaderSelector, loaderHintLabel);
        loaderRow.setAlignment(Pos.CENTER_LEFT);
        loaderBlock.getChildren().addAll(loaderLabel, loaderRow);

        // ---- memory row ----
        VBox memoryBlock = new VBox(7);
        memoryLabel = new Label("Memory Allocation - " + config.getMaxMemoryMb() + " MB");
        memoryLabel.getStyleClass().add("field-label");

        memorySlider = new Slider(1024, config.getMemoryCeilingMb(), config.getMaxMemoryMb());
        memorySlider.getStyleClass().add("ram-slider");
        memorySlider.setBlockIncrement(512);
        memorySlider.setMajorTickUnit(2048);
        memorySlider.setMinorTickCount(3);
        memorySlider.setSnapToTicks(false);
        memorySlider.setShowTickMarks(true);
        memorySlider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int megabytes = (int) (Math.round(newValue.doubleValue() / 256.0) * 256);
            memoryLabel.setText("Memory Allocation - " + megabytes + " MB");
            config.setMaxMemoryMb(megabytes);
        });
        memoryBlock.getChildren().addAll(memoryLabel, memorySlider);

        // ---- quick connect ----
        VBox serverBlock = new VBox(7);
        Label serverLabel = new Label("Server Quick-Connect (optional)");
        serverLabel.getStyleClass().add("field-label");
        serverField = new TextField(config.getLastServerAddress());
        serverField.getStyleClass().add("bettle-field");
        serverField.setPromptText("play.example.net  or  play.example.net:25566");
        serverBlock.getChildren().addAll(serverLabel, serverField);

        card.getChildren().addAll(heading, versionBlock, loaderBlock, memoryBlock, serverBlock);
        updateLoaderHint();
        return card;
    }

    /** Explains what the selected profile will install before the game starts. */
    private void updateLoaderHint() {
        if (loaderHintLabel == null || loaderSelector == null) {
            return;
        }
        ModLoader loader = loaderSelector.getValue();
        if (loader == null || !loader.requiresFabric()) {
            loaderHintLabel.setText("Unmodified Minecraft.");
            return;
        }
        loaderHintLabel.setText(modInstaller.isModBundled()
                ? "Installs Fabric Loader, the Bettle HUD and Fabric API automatically."
                : "Installs Fabric Loader and Fabric API. No HUD mod is embedded in this build.");
    }

    private Region buildProgressCard() {
        VBox card = new VBox(14);
        card.getStyleClass().add("glass-card");
        card.setPadding(new Insets(20, 22, 22, 22));

        playButton = new Button("PLAY");
        playButton.getStyleClass().add("play-button");
        playButton.setMaxWidth(Double.MAX_VALUE);
        playButton.setPrefHeight(62);
        playButton.setDisable(true);
        playButton.setOnAction(event -> startLaunch());

        DropShadow glow = new DropShadow(20, Color.web(COLOR_PRIMARY, 0.75));
        glow.setSpread(0.12);
        playButton.setEffect(glow);

        playGlowAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(glow.radiusProperty(), 18, Interpolator.EASE_BOTH),
                        new KeyValue(glow.colorProperty(), Color.web(COLOR_PRIMARY, 0.55))),
                new KeyFrame(Duration.seconds(1.4),
                        new KeyValue(glow.radiusProperty(), 38, Interpolator.EASE_BOTH),
                        new KeyValue(glow.colorProperty(), Color.web(COLOR_GLOW, 0.95)))
        );
        playGlowAnimation.setAutoReverse(true);
        playGlowAnimation.setCycleCount(Timeline.INDEFINITE);

        playButton.setOnMouseEntered(event -> pulse(playButton, 1.015));
        playButton.setOnMouseExited(event -> pulse(playButton, 1.0));

        progressBar = new ProgressBar(0);
        progressBar.getStyleClass().add("bettle-progress");
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(10);

        statusLabel = new Label("Idle");
        statusLabel.getStyleClass().add("status-line");

        card.getChildren().addAll(playButton, progressBar, statusLabel);
        return card;
    }

    private static void pulse(Node node, double scale) {
        ScaleTransition transition = new ScaleTransition(Duration.millis(140), node);
        transition.setToX(scale);
        transition.setToY(scale);
        transition.play();
    }

    // -------------------------- Settings --------------------------

    private VBox buildSettingsView() {
        VBox root = new VBox(18);

        VBox card = new VBox(16);
        card.getStyleClass().add("glass-card");
        card.setPadding(new Insets(20, 22, 22, 22));

        Label heading = new Label("LAUNCHER SETTINGS");
        heading.getStyleClass().add("card-heading");

        gameDirField = new TextField(config.getGameDirectory());
        gameDirField.getStyleClass().add("bettle-field");
        gameDirField.setPromptText("Leave empty for per-version instances under "
                + launcherRoot.resolve("instances"));

        javaPathField = new TextField(config.getJavaExecutable());
        javaPathField.getStyleClass().add("bettle-field");
        javaPathField.setPromptText("Leave empty to use "
                + OperatingSystem.currentJavaExecutable());

        jvmArgsField = new TextField(config.getExtraJvmArguments());
        jvmArgsField.getStyleClass().add("bettle-field");
        jvmArgsField.setPromptText("-XX:+UseG1GC -XX:+ParallelRefProcEnabled");

        widthField = new TextField(String.valueOf(config.getWindowWidth()));
        widthField.getStyleClass().add("bettle-field");
        widthField.setPrefWidth(110);

        heightField = new TextField(String.valueOf(config.getWindowHeight()));
        heightField.getStyleClass().add("bettle-field");
        heightField.setPrefWidth(110);

        HBox resolutionRow = new HBox(10, widthField, new Label("x"), heightField);
        resolutionRow.setAlignment(Pos.CENTER_LEFT);
        resolutionRow.getChildren().get(1).getStyleClass().add("field-label");

        fullscreenBox = new CheckBox("Launch fullscreen");
        fullscreenBox.getStyleClass().add("bettle-check");
        fullscreenBox.setSelected(config.isFullscreen());

        keepOpenBox = new CheckBox("Keep launcher open after the game starts");
        keepOpenBox.getStyleClass().add("bettle-check");
        keepOpenBox.setSelected(config.isKeepLauncherOpen());

        Button save = new Button("SAVE SETTINGS");
        save.getStyleClass().add("secondary-button");
        save.setOnAction(event -> {
            persistSettings();
            setStatus("Settings saved");
            appendLog("Settings saved to " + launcherRoot.resolve("config.json"));
        });

        card.getChildren().addAll(
                heading,
                labelled("Game Directory", gameDirField),
                labelled("Java Executable", javaPathField),
                labelled("Additional JVM Arguments", jvmArgsField),
                labelled("Window Resolution", resolutionRow),
                fullscreenBox,
                keepOpenBox,
                save
        );

        ScrollPane scroll = new ScrollPane(card);
        scroll.getStyleClass().add("bettle-scroll");
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().add(scroll);
        return root;
    }

    private Region labelled(String text, Node control) {
        VBox block = new VBox(7);
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        block.getChildren().addAll(label, control);
        return block;
    }

    // --------------------------- Console ---------------------------

    private VBox buildConsoleView() {
        VBox root = new VBox(14);

        VBox card = new VBox(12);
        card.getStyleClass().add("glass-card");
        card.setPadding(new Insets(20, 22, 22, 22));
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("LAUNCH CONSOLE");
        heading.getStyleClass().add("card-heading");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button clear = new Button("Clear");
        clear.getStyleClass().add("ghost-button");
        clear.setOnAction(event -> consoleArea.clear());

        Button copy = new Button("Copy");
        copy.getStyleClass().add("ghost-button");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(consoleArea.getText());
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("Console copied to clipboard");
        });

        header.getChildren().addAll(heading, spacer, copy, clear);

        consoleArea = new TextArea();
        consoleArea.getStyleClass().add("console");
        consoleArea.setEditable(false);
        consoleArea.setWrapText(false);
        VBox.setVgrow(consoleArea, Priority.ALWAYS);

        card.getChildren().addAll(header, consoleArea);
        root.getChildren().add(card);
        VBox.setVgrow(card, Priority.ALWAYS);
        return root;
    }

    // ==================================================================
    // Version loading
    // ==================================================================

    private void loadVersionsAsync() {
        loadVersionsAsync(false);
    }

    private void loadVersionsAsync(boolean forceRefresh) {
        setStatus("Fetching Mojang version manifest...");
        progressBar.setProgress(-1);
        if (refreshVersionsButton != null) {
            refreshVersionsButton.setDisable(true);
        }

        worker.submit(() -> {
            try {
                List<VersionEntry> all = forceRefresh ? versionManager.refresh() : versionManager.load();
                Platform.runLater(() -> {
                    appendLog("Manifest loaded: " + all.size() + " total versions"
                            + (versionManager.isLoadedFromCache() ? " (from cache)" : ""));
                    populateVersionSelector();
                    progressBar.setProgress(0);
                    if (refreshVersionsButton != null) {
                        refreshVersionsButton.setDisable(false);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("ERROR: could not load version manifest - " + e.getMessage());
                    setStatus("Version manifest unavailable");
                    progressBar.setProgress(0);
                    if (refreshVersionsButton != null) {
                        refreshVersionsButton.setDisable(false);
                    }
                });
            }
        });
    }

    /** Rebuilds the dropdown contents from the currently loaded manifest. */
    private void populateVersionSelector() {
        List<VersionEntry> supported = snapshotToggle != null && snapshotToggle.isSelected()
                ? versionManager.getSupportedReleasesAndSnapshots()
                : versionManager.getSupportedReleases();

        versionItems.setAll(supported);

        if (supported.isEmpty()) {
            versionSelector.setPromptText("No versions available");
            setStatus("No supported versions found in the manifest");
            updatePlayButtonState();
            return;
        }

        Optional<VersionEntry> preferred = Optional
                .ofNullable(config.getLastVersionId())
                .flatMap(id -> supported.stream()
                        .filter(entry -> entry.getId().equals(id))
                        .findFirst());

        versionSelector.setValue(preferred.orElse(supported.get(0)));
        versionSelector.setPromptText("Select a version");

        setStatus(supported.size() + " versions available ("
                + supported.get(supported.size() - 1).getId()
                + " to " + supported.get(0).getId() + ")");
        updatePlayButtonState();
    }

    /** Renders one row of the version dropdown. */
    private static final class VersionCell extends ListCell<VersionEntry> {

        @Override
        protected void updateItem(VersionEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                getStyleClass().remove("version-cell-snapshot");
                return;
            }
            setText(item.getId() + (item.isSnapshot() ? "   snapshot" : ""));
            if (item.isSnapshot() && !getStyleClass().contains("version-cell-snapshot")) {
                getStyleClass().add("version-cell-snapshot");
            } else if (!item.isSnapshot()) {
                getStyleClass().remove("version-cell-snapshot");
            }
        }
    }

    // ==================================================================
    // Authentication
    // ==================================================================

    private void restoreSessionAsync() {
        worker.submit(() -> {
            Optional<MinecraftSession> restored = authenticator.restoreSession();
            Platform.runLater(() -> restored.ifPresentOrElse(
                    restoredSession -> {
                        applySession(restoredSession);
                        appendLog("Restored Microsoft session for " + restoredSession.getUsername());
                    },
                    () -> appendLog("No stored session - sign in to enable online play")
            ));
        });
    }

    private void startDeviceCodeLogin() {
        accountButton.setDisable(true);
        setStatus("Contacting Microsoft...");
        progressBar.setProgress(-1);

        DeviceCodeDialog dialog = new DeviceCodeDialog(stage);

        worker.submit(() -> {
            try {
                MinecraftSession authenticated = authenticator.login(
                        deviceCode -> Platform.runLater(() -> dialog.show(deviceCode)));

                Platform.runLater(() -> {
                    dialog.close();
                    applySession(authenticated);
                    appendLog("Signed in as " + authenticated.getUsername()
                            + " (" + authenticated.getUuid() + ")");
                    setStatus("Signed in as " + authenticated.getUsername());
                    progressBar.setProgress(0);
                    accountButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    dialog.close();
                    appendLog("ERROR: sign-in failed - " + e.getMessage());
                    setStatus("Sign-in failed");
                    progressBar.setProgress(0);
                    accountButton.setDisable(false);
                });
            }
        });
    }

    private void signOut() {
        authenticator.logout();
        session = null;
        usernameLabel.setText("Not signed in");
        accountStatusLabel.setText("Microsoft account required for online play");
        statusDot.getStyleClass().removeAll("status-dot-online");
        if (!statusDot.getStyleClass().contains("status-dot-offline")) {
            statusDot.getStyleClass().add("status-dot-offline");
        }
        avatarView.setImage(null);
        accountButton.setText("SIGN IN WITH MICROSOFT");
        appendLog("Signed out");
        setStatus("Signed out");
        updatePlayButtonState();
    }

    private void applySession(MinecraftSession authenticated) {
        this.session = authenticated;
        usernameLabel.setText(authenticated.getUsername());
        accountStatusLabel.setText("Authenticated • Microsoft • online play enabled");
        statusDot.getStyleClass().removeAll("status-dot-offline");
        if (!statusDot.getStyleClass().contains("status-dot-online")) {
            statusDot.getStyleClass().add("status-dot-online");
        }
        accountButton.setText("SIGN OUT");
        loadAvatar(authenticated);
        updatePlayButtonState();
    }

    /** Loads the player head asynchronously; failure just leaves the placeholder. */
    private void loadAvatar(MinecraftSession authenticated) {
        String url = "https://mc-heads.net/avatar/" + authenticated.getUuidUndashed() + "/64";
        Image image = new Image(url, 52, 52, true, false, true);
        image.errorProperty().addListener((observable, wasError, isError) -> {
            if (Boolean.TRUE.equals(isError)) {
                Platform.runLater(() -> avatarView.setImage(null));
            }
        });
        avatarView.setImage(image);
    }

    // ==================================================================
    // Launching
    // ==================================================================

    private void updatePlayButtonState() {
        boolean ready = session != null
                && versionSelector != null
                && versionSelector.getValue() != null
                && !launching;
        playButton.setDisable(!ready);
        if (playGlowAnimation != null) {
            if (ready) {
                playGlowAnimation.play();
            } else {
                playGlowAnimation.stop();
            }
        }
    }

    private void startLaunch() {
        VersionEntry selected = versionSelector.getValue();
        if (selected == null || session == null || launching) {
            return;
        }
        ModLoader loader = loaderSelector.getValue() == null
                ? ModLoader.VANILLA
                : loaderSelector.getValue();

        launching = true;
        updatePlayButtonState();
        playButton.setText("LAUNCHING...");
        persistSettings();

        LaunchOptions options = buildLaunchOptions(selected, loader);
        appendLog("Launching " + selected.getId()
                + " [" + loader.getDisplayName() + "] with " + options);

        worker.submit(() -> {
            UiProgressListener listener = new UiProgressListener();
            try {
                MinecraftSession valid = authenticator.requireValidSession();
                Platform.runLater(() -> this.session = valid);

                VersionEntry target = selected;
                if (loader.requiresFabric()) {
                    // Writing Fabric's profile JSON is the whole installation;
                    // the launch engine resolves its inheritsFrom chain from there.
                    String profileId = fabricInstaller.installLatest(selected.getId(), listener);
                    target = new VersionEntry(profileId, selected.getType(),
                            null, null, null, null, 0);
                    modInstaller.installAll(options.getGameDirectory(), selected.getId(), listener);
                }

                launcher.launch(target, valid, options, listener);

                Platform.runLater(() -> {
                    if (!keepOpenBox.isSelected()) {
                        stage.setIconified(true);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    appendLog("ERROR: " + e.getMessage());
                    setStatus("Launch failed - see console");
                    progressBar.setProgress(0);
                    resetPlayButton();
                });
            }
        });
    }

    private LaunchOptions buildLaunchOptions(VersionEntry selected, ModLoader loader) {
        Path gameDirectory = config.getGameDirectoryPath() != null
                ? config.getGameDirectoryPath()
                : launcher.getInstanceDirectory(selected.getId() + loader.getInstanceSuffix());

        int maxMemory = (int) Math.round(memorySlider.getValue() / 256.0) * 256;
        int minMemory = Math.min(config.getMinMemoryMb(), maxMemory);

        LaunchOptions.Builder builder = LaunchOptions.builder()
                .gameDirectory(gameDirectory)
                .javaExecutable(config.getJavaExecutablePath())
                .memory(minMemory, maxMemory)
                .jvmArguments(config.getExtraJvmArgumentList())
                .fullscreen(config.isFullscreen())
                .quickConnect(serverField.getText());

        if (!config.isFullscreen()) {
            builder.resolution(config.getWindowWidth(), config.getWindowHeight());
        }
        return builder.build();
    }

    private void resetPlayButton() {
        launching = false;
        playButton.setText("PLAY");
        updatePlayButtonState();
    }

    /** Bridges {@link ProgressListener} callbacks onto the JavaFX thread. */
    private final class UiProgressListener implements ProgressListener {

        @Override
        public void onStage(String stage) {
            Platform.runLater(() -> {
                setStatus(stage);
                appendLog("[stage] " + stage);
            });
        }

        @Override
        public void onProgress(double fraction) {
            Platform.runLater(() -> progressBar.setProgress(fraction < 0 ? -1 : fraction));
        }

        @Override
        public void onLog(String line) {
            Platform.runLater(() -> appendLog(line));
        }

        @Override
        public void onGameStarted(Process process) {
            Platform.runLater(() -> {
                setStatus("Minecraft is running (pid " + process.pid() + ")");
                appendLog("Game process started, pid " + process.pid());
                playButton.setText("RUNNING");
                progressBar.setProgress(1.0);
            });
        }

        @Override
        public void onGameExited(int exitCode) {
            Platform.runLater(() -> {
                appendLog("Game exited with code " + exitCode);
                setStatus(exitCode == 0 ? "Game closed" : "Game exited with code " + exitCode);
                progressBar.setProgress(0);
                resetPlayButton();
                if (!stage.isShowing()) {
                    return;
                }
                stage.setIconified(false);
            });
        }

        @Override
        public void onError(Throwable error) {
            Platform.runLater(() -> {
                appendLog("ERROR: " + error.getMessage());
                setStatus("Launch failed - see console");
                progressBar.setProgress(0);
                resetPlayButton();
            });
        }
    }

    // ==================================================================
    // Device code dialog
    // ==================================================================

    /**
     * Modal window that shows the Microsoft device code, the verification URL
     * and buttons to copy the code or open the page in the system browser.
     */
    private final class DeviceCodeDialog {

        private final Stage dialogStage;
        private final Label codeLabel;
        private final Label urlLabel;
        private final Label hintLabel;

        DeviceCodeDialog(Stage owner) {
            codeLabel = new Label("--------");
            codeLabel.getStyleClass().add("device-code");

            urlLabel = new Label("https://www.microsoft.com/link");
            urlLabel.getStyleClass().add("device-url");

            hintLabel = new Label("Waiting for you to approve the sign-in...");
            hintLabel.getStyleClass().add("profile-status");

            Label title = new Label("MICROSOFT SIGN-IN");
            title.getStyleClass().add("card-heading");

            Label instructions = new Label(
                    "Open the page below, enter this code, and approve the request.\n"
                            + "This window closes automatically once you are signed in.");
            instructions.getStyleClass().add("device-instructions");
            instructions.setWrapText(true);

            Button copyButton = new Button("COPY CODE");
            copyButton.getStyleClass().add("secondary-button");
            copyButton.setOnAction(event -> {
                ClipboardContent content = new ClipboardContent();
                content.putString(codeLabel.getText());
                Clipboard.getSystemClipboard().setContent(content);
                hintLabel.setText("Code copied to clipboard.");
            });

            Button openButton = new Button("OPEN LOGIN PAGE");
            openButton.getStyleClass().add("play-button-small");
            openButton.setOnAction(event -> openInBrowser(urlLabel.getText()));

            Button cancelButton = new Button("CANCEL");
            cancelButton.getStyleClass().add("ghost-button");
            cancelButton.setOnAction(event -> close());

            HBox buttons = new HBox(10, copyButton, openButton, cancelButton);
            buttons.setAlignment(Pos.CENTER);

            VBox content = new VBox(14, title, instructions, codeLabel, urlLabel, buttons, hintLabel);
            content.setAlignment(Pos.CENTER);
            content.setPadding(new Insets(28, 34, 26, 34));
            content.getStyleClass().addAll("glass-card", "device-dialog");
            content.setEffect(new DropShadow(36, 0, 8, Color.web(COLOR_PRIMARY, 0.5)));

            StackPane host = new StackPane(content);
            host.setPadding(new Insets(18));
            host.setStyle("-fx-background-color: transparent;");

            Scene scene = new Scene(host, 520, 380);
            scene.setFill(Color.TRANSPARENT);
            applyStylesheet(scene);

            dialogStage = new Stage();
            dialogStage.initOwner(owner);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initStyle(StageStyle.TRANSPARENT);
            dialogStage.setScene(scene);
            dialogStage.setResizable(false);

            // Let the dialog be dragged too.
            final double[] offset = new double[2];
            content.setOnMousePressed(event -> {
                offset[0] = event.getScreenX() - dialogStage.getX();
                offset[1] = event.getScreenY() - dialogStage.getY();
            });
            content.setOnMouseDragged(event -> {
                dialogStage.setX(event.getScreenX() - offset[0]);
                dialogStage.setY(event.getScreenY() - offset[1]);
            });
        }

        void show(MicrosoftAuthenticator.DeviceCode deviceCode) {
            codeLabel.setText(deviceCode.getUserCode());
            urlLabel.setText(deviceCode.getVerificationUri());
            hintLabel.setText("Code expires in about "
                    + Math.max(1, deviceCode.getSecondsRemaining() / 60) + " minutes.");
            appendLog("Device code issued: " + deviceCode.getUserCode()
                    + " -> " + deviceCode.getVerificationUri());
            if (!dialogStage.isShowing()) {
                dialogStage.show();
                dialogStage.centerOnScreen();
            }
            openInBrowser(deviceCode.getDirectVerificationUri());
        }

        void close() {
            if (dialogStage.isShowing()) {
                dialogStage.close();
            }
        }
    }

    private void openInBrowser(String url) {
        worker.submit(() -> {
            try {
                if (Desktop.isDesktopSupported()
                        && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                    return;
                }
                throw new UnsupportedOperationException("Desktop browse is unavailable");
            } catch (Exception e) {
                Platform.runLater(() ->
                        appendLog("Could not open a browser automatically. Visit: " + url));
            }
        });
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private void persistSettings() {
        if (config == null) {
            return;
        }
        if (serverField != null) {
            config.setLastServerAddress(serverField.getText());
        }
        if (memorySlider != null) {
            config.setMaxMemoryMb((int) Math.round(memorySlider.getValue() / 256.0) * 256);
        }
        if (gameDirField != null) {
            config.setGameDirectory(gameDirField.getText());
        }
        if (javaPathField != null) {
            config.setJavaExecutable(javaPathField.getText());
        }
        if (jvmArgsField != null) {
            config.setExtraJvmArguments(jvmArgsField.getText());
        }
        if (widthField != null) {
            config.setWindowWidth(parseIntOrDefault(widthField.getText(), config.getWindowWidth()));
        }
        if (heightField != null) {
            config.setWindowHeight(parseIntOrDefault(heightField.getText(), config.getWindowHeight()));
        }
        if (fullscreenBox != null) {
            config.setFullscreen(fullscreenBox.isSelected());
        }
        if (keepOpenBox != null) {
            config.setKeepLauncherOpen(keepOpenBox.isSelected());
        }
        if (snapshotToggle != null) {
            config.setShowSnapshots(snapshotToggle.isSelected());
        }
        if (loaderSelector != null && loaderSelector.getValue() != null) {
            config.setModLoader(loaderSelector.getValue().name());
        }
        config.save();
    }

    private static int parseIntOrDefault(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void setStatus(String text) {
        if (statusLabel != null) {
            statusLabel.setText(text);
        }
    }

    /** Appends a timestamped line to the console, trimming it when it grows large. */
    private void appendLog(String line) {
        if (consoleArea == null) {
            System.out.println("[Bettle] " + line);
            return;
        }
        consoleArea.appendText("[" + LocalTime.now().format(LOG_TIME) + "] " + line + "\n");
        if (consoleArea.getLength() > MAX_CONSOLE_CHARACTERS) {
            consoleArea.deleteText(0, consoleArea.getLength() - MAX_CONSOLE_CHARACTERS / 2);
        }
    }

    /**
     * Entry point when the launcher is started directly through the JavaFX
     * tooling. {@code net.bettleclient.Main} is the preferred bootstrap for a
     * shaded jar.
     */
    public static void main(String[] args) {
        launch(args);
    }
}
