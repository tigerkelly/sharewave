package com.sharewave;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * JavaFX control panel for the ShareWave server.
 * Requires admin password on startup. Includes a Users tab for managing
 * web-app accounts directly from the desktop UI.
 */
public class MainApp extends Application {

    // ── Theme palettes ────────────────────────────────────────────────────
    private record Theme(String bg, String panel, String border,
                         String text, String muted, String input,
                         String logBg, String logText) {
        static final Theme DARK  = new Theme(
                "#0f1117","#1a1d27","#2e3148","#e2e4f0","#7a7f9a","#0f1117","#0f1117","#9badfb");
        static final Theme LIGHT = new Theme(
                "#f0f2f8","#ffffff","#d0d4e8","#1a1d2e","#6b7099","#ffffff","#f8f9fc","#3a4070");
    }

    private boolean isDark = true;
    private Theme   theme  = Theme.DARK;

    // ── Config / admin ────────────────────────────────────────────────────
    private final AppConfig   config      = new AppConfig();
    private final AdminConfig adminConfig = new AdminConfig();

    // ── Server state ──────────────────────────────────────────────────────
    private Server   server;
    private Database database;
    private ScheduledExecutorService purgeScheduler;
    private FileLogger fileLogger;

    // ── UI references ─────────────────────────────────────────────────────
    private Scene    scene;
    private VBox     root;
    private VBox     configCard, logCard, usersCard, archiveCard, filesCard;
    private TabPane  tabPane;
    private HBox     statusCard;
    private TextField portField, uploadDirField, dbPathField, keystoreField;
    private Button    toggleBtn, themeBtn;
    private Label     statusLabel, urlLabel;
    private TextArea  logArea;
    private VBox      userListBox;
    private VBox      archiveListBox;
    private VBox      filesListBox;
    private Path      archiveDir;
    private javafx.scene.shape.Circle statusDot;
    private Stage     primaryStage;

    // ── Accent colours ────────────────────────────────────────────────────
    private static final String ACCENT  = "#5b6af7";
    private static final String ACCENTH = "#7b8aff";
    private static final String DANGER  = "#e05c6e";
    private static final String DANGERH = "#f07080";
    private static final String GREEN   = "#4caf72";

    // ═════════════════════════════════════════════════════════════════════
    // Startup
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        config.load();
        adminConfig.load();
        isDark = !"light".equals(config.getTheme());
        theme  = isDark ? Theme.DARK : Theme.LIGHT;

        // Show login/setup before the main window
        if (!showAdminGate()) {
            Platform.exit();
            return;
        }

        buildMainWindow(stage);
    }

    /**
     * Shows either a "Set admin password" dialog (first run) or a
     * "Enter password" dialog. Returns true if the user is authenticated.
     */
    private boolean showAdminGate() {
        if (adminConfig.isFirstRun()) {
            return showSetPasswordDialog();
        } else {
            return showLoginDialog();
        }
    }

    // ── Set-password dialog (first run) ───────────────────────────────────

    private boolean showSetPasswordDialog() {
        Stage dlg = dialogStage("ShareWave — Set Admin Password");
        boolean[] result = {false};

        VBox box = new VBox(12);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color:#1a1d27;");

        Label title = new Label("Set Admin Password");
        title.setStyle("-fx-text-fill:#e2e4f0;-fx-font-size:15px;-fx-font-weight:bold;");

        Label sub = new Label("This password protects the ShareWave control panel.");
        sub.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");
        sub.setWrapText(true);

        PasswordField pw1 = dialogPasswordField("New password (min 4 chars)");
        PasswordField pw2 = dialogPasswordField("Confirm password");
        Label         err = errorLabel();
        Button        btn = primaryBtn("Set Password");

        Runnable doSet = () -> {
            String p1 = pw1.getText(), p2 = pw2.getText();
            if (p1.length() < 4)          { err.setText("Password must be at least 4 characters."); return; }
            if (!p1.equals(p2))           { err.setText("Passwords do not match."); return; }
            adminConfig.setPassword(p1);
            result[0] = true;
            dlg.close();
        };
        btn.setOnAction(e -> doSet.run());
        pw2.setOnAction(e -> doSet.run());

        box.getChildren().addAll(title, sub, pw1, pw2, err, btn);
        dlg.setScene(new Scene(box, 360, 260));
        dlg.showAndWait();
        return result[0];
    }

    // ── Login dialog ──────────────────────────────────────────────────────

    private boolean showLoginDialog() {
        Stage dlg = dialogStage("ShareWave — Admin Login");
        boolean[] result = {false};
        int[]     attempts = {0};

        VBox box = new VBox(12);
        box.setPadding(new Insets(24));
        box.setStyle("-fx-background-color:#1a1d27;");

        Label title = new Label("🌊 ShareWave");
        title.setStyle("-fx-text-fill:#5b6af7;-fx-font-size:18px;-fx-font-weight:bold;");

        Label sub = new Label("Enter admin password to continue.");
        sub.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");

        PasswordField pw  = dialogPasswordField("Admin password");
        Label         err = errorLabel();
        Button        btn = primaryBtn("Unlock");

        Runnable doLogin = () -> {
            if (adminConfig.checkPassword(pw.getText())) {
                result[0] = true;
                dlg.close();
            } else {
                attempts[0]++;
                pw.clear();
                err.setText("Incorrect password." +
                        (attempts[0] >= 3 ? " (" + attempts[0] + " attempts)" : ""));
            }
        };
        btn.setOnAction(e -> doLogin.run());
        pw.setOnAction(e  -> doLogin.run());

        box.getChildren().addAll(title, sub, pw, err, btn);
        dlg.setScene(new Scene(box, 340, 220));
        dlg.showAndWait();
        return result[0];
    }

    // ═════════════════════════════════════════════════════════════════════
    // Main window
    // ═════════════════════════════════════════════════════════════════════

    private void buildMainWindow(Stage stage) {
        stage.setTitle("ShareWave Server");
        stage.setMinWidth(880);
        stage.setMinHeight(580);

        // Build all panels
        configCard  = buildConfigCard();
        statusCard  = buildStatusCard();
        usersCard   = buildUsersCard();
        archiveCard = buildArchiveCard();
        logCard     = buildLogCard();

        // Wrap each panel in a ScrollPane so content is never clipped
        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        filesCard = buildFilesCard();

        Tab serverTab  = buildTab("⚙  Server",  wrapInScroll(configCard));
        Tab filesTab   = buildTab("📁  Files",   filesCard);
        Tab usersTab   = buildTab("👤  Users",   wrapInScroll(usersCard));
        Tab archiveTab = buildTab("🗄  Archive", wrapInScroll(archiveCard));
        Tab logTab     = buildTab("📋  Log",     logCard);

        // Refresh log file list when switching to the Log tab
        logTab.setOnSelectionChanged(e -> {
            if (logTab.isSelected()) populateLogFileCombo();
        });

        tabPane.getTabs().addAll(serverTab, filesTab, usersTab, archiveTab, logTab);

        // Status bar sits above the tab pane, always visible
        root = new VBox(8);
        root.setPadding(new Insets(12, 12, 12, 12));
        VBox.setVgrow(tabPane, Priority.ALWAYS);   // TabPane fills all remaining height
        root.getChildren().addAll(statusCard, tabPane);

        scene = new Scene(root, 920, 700);
        applyTheme();
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> { saveConfig(); stopServer(); });
        stage.show();
        log("ShareWave ready. Configure and click Start Server.");
    }

    private Tab buildTab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        return tab;
    }

    private ScrollPane wrapInScroll(VBox card) {
        ScrollPane sp = new ScrollPane(card);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color:transparent;-fx-background:transparent;");
        sp.skinProperty().addListener((obs, o, n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = sp.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
    }

    // ── Config card ───────────────────────────────────────────────────────

    private VBox buildConfigCard() {
        VBox card = new VBox(10);

        // Toolbar row: theme toggle + change password
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_RIGHT);
        Button chgPwBtn = accentOutlineBtn("Change Password");
        chgPwBtn.setOnAction(e -> changeAdminPassword());
        themeBtn = new Button(isDark ? "☀ Light" : "🌙 Dark");
        themeBtn.setOnAction(e -> toggleTheme());
        titleRow.getChildren().addAll(chgPwBtn, themeBtn);

        HBox portRow = new HBox(8); portRow.setAlignment(Pos.CENTER_LEFT);
        portRow.getChildren().addAll(fieldLabel("Port", 80),
                portField = styledTextField(config.getPort(), 80));

        HBox dirRow = new HBox(8); dirRow.setAlignment(Pos.CENTER_LEFT);
        uploadDirField = styledTextField(config.getUploadDir(), 300);
        HBox.setHgrow(uploadDirField, Priority.ALWAYS);
        Button browseBtn = accentOutlineBtn("Browse…");
        browseBtn.setOnAction(e -> browseDir());
        dirRow.getChildren().addAll(fieldLabel("Upload Dir", 80), uploadDirField, browseBtn);

        HBox dbRow = new HBox(8); dbRow.setAlignment(Pos.CENTER_LEFT);
        dbPathField = styledTextField(config.getDbPath(), 300);
        HBox.setHgrow(dbPathField, Priority.ALWAYS);
        dbRow.getChildren().addAll(fieldLabel("Database", 80), dbPathField);

        HBox ksRow = new HBox(8); ksRow.setAlignment(Pos.CENTER_LEFT);
        keystoreField = styledTextField(config.getKeystore(), 300);
        HBox.setHgrow(keystoreField, Priority.ALWAYS);
        Button browseKs = accentOutlineBtn("Browse…");
        browseKs.setOnAction(e -> browseKeystore());
        ksRow.getChildren().addAll(fieldLabel("Keystore", 80), keystoreField, browseKs);

        toggleBtn = new Button("▶  Start Server");
        applyToggleStyle(toggleBtn, false);
        toggleBtn.setOnAction(e -> toggleServer());

        card.getChildren().addAll(titleRow, portRow, dirRow, dbRow, ksRow, toggleBtn);
        return card;
    }

    // ── Status card ───────────────────────────────────────────────────────

    private HBox buildStatusCard() {
        HBox card = new HBox(10);
        card.setAlignment(Pos.CENTER_LEFT);

        statusDot   = new javafx.scene.shape.Circle(6, Color.web(DANGER));
        statusLabel = new Label("STOPPED");
        statusLabel.setStyle("-fx-text-fill:" + DANGER + ";-fx-font-weight:bold;-fx-font-size:13px;");

        Label sep = new Label("·");
        sep.setStyle("-fx-font-size:16px;");

        urlLabel = new Label("—");
        urlLabel.setStyle("-fx-text-fill:" + ACCENT + ";-fx-font-size:12px;-fx-cursor:hand;");
        urlLabel.setOnMouseClicked(e -> {
            String url = urlLabel.getText();
            if (url.startsWith("http")) {
                try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); }
                catch (Exception ignored) {}
            }
        });

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        card.getChildren().addAll(statusDot, statusLabel, sep, urlLabel, sp);
        return card;
    }

    // ── Users card ────────────────────────────────────────────────────────

    private VBox buildUsersCard() {
        VBox card = new VBox(10);

        HBox header = new HBox(8); header.setAlignment(Pos.CENTER_RIGHT);
        Button refreshBtn = accentOutlineBtn("Refresh");
        refreshBtn.setOnAction(e -> refreshUserList());
        header.getChildren().add(refreshBtn);

        // Create user row
        HBox createRow = new HBox(8); createRow.setAlignment(Pos.CENTER_LEFT);
        TextField  newUsername = styledTextField("", 160);
        newUsername.setPromptText("username");
        PasswordField newPassword = new PasswordField();
        newPassword.setPromptText("password");
        newPassword.setPrefWidth(140);
        newPassword.setStyle(textFieldStyle());
        Button createBtn = new Button("Create User");
        applyToggleStyle(createBtn, false);
        createBtn.setStyle(createBtn.getStyle()
                .replace("-fx-padding:8 24", "-fx-padding:5 14")
                .replace("-fx-font-size:13px", "-fx-font-size:12px"));

        Label createMsg = new Label("");
        createMsg.setStyle("-fx-font-size:11px;");

        createBtn.setOnAction(e -> {
            String u = newUsername.getText().trim();
            String p = newPassword.getText();
            if (u.isEmpty() || p.isEmpty()) {
                createMsg.setText("Enter both username and password.");
                createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + DANGER + ";");
                return;
            }
            if (p.length() < 4) {
                createMsg.setText("Password must be at least 4 characters.");
                createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + DANGER + ";");
                return;
            }
            if (database == null) {
                createMsg.setText("Start the server first.");
                createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + DANGER + ";");
                return;
            }
            try {
                int uid = database.registerUser(u, p);
                if (uid < 0) {
                    createMsg.setText("Username already taken.");
                    createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + DANGER + ";");
                } else {
                    createMsg.setText("User '" + u + "' created.");
                    createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + GREEN + ";");
                    newUsername.clear(); newPassword.clear();
                    log("ADMIN created user: " + u);
                    refreshUserList();
                }
            } catch (Exception ex) {
                createMsg.setText("Error: " + ex.getMessage());
                createMsg.setStyle("-fx-font-size:11px;-fx-text-fill:" + DANGER + ";");
            }
        });

        createRow.getChildren().addAll(
                fieldLabel("New User", 72), newUsername,
                fieldLabel("Password", 65), newPassword,
                createBtn);

        // Scrollable user list
        userListBox = new VBox(0);
        userListBox.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(userListBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(400);
        scroll.setMinHeight(300);
        // Remove the default grey viewport background
        scroll.setStyle(
            "-fx-background-color:transparent;" +
            "-fx-background:transparent;" +
            "-fx-border-color:" + theme.border() + ";" +
            "-fx-border-radius:6;-fx-background-radius:6;"
        );
        // The viewport itself also needs clearing - done via skin after layout
        scroll.skinProperty().addListener((obs, o, n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = scroll.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));

        Label noServer = new Label("Start the server to manage users.");
        noServer.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:8 4;");
        userListBox.getChildren().add(noServer);

        card.getChildren().addAll(header, createRow, createMsg, scroll);
        return card;
    }

    // ── Files card (admin view of all uploaded files) ────────────────────

    private VBox buildFilesCard() {
        // Use VBox with ALWAYS grow so the tab content fills the full tab height
        VBox card = new VBox(0);
        card.setPadding(new Insets(10, 12, 10, 12));
        card.setSpacing(6);

        // Toolbar
        HBox toolbar = new HBox(8); toolbar.setAlignment(Pos.CENTER_LEFT);
        Label countLabel = new Label("Start the server to view files.");
        countLabel.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");
        countLabel.setId("filesCountLabel");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button refreshBtn = accentOutlineBtn("Refresh");
        refreshBtn.setOnAction(e -> refreshFilesList());
        toolbar.getChildren().addAll(countLabel, sp, refreshBtn);

        // Column headers bar
        HBox headers = new HBox(0);
        headers.setPadding(new Insets(4, 8, 4, 8));
        headers.setStyle("-fx-background-color:" + theme.panel() + ";" +
                "-fx-border-color:" + theme.border() + ";" +
                "-fx-border-radius:5 5 0 0;-fx-background-radius:5 5 0 0;");
        headers.getChildren().addAll(
            colHeader("Filename",  240),
            colHeader("Owner",      80),
            colHeader("Size",       65),
            colHeader("Access",     65),
            colHeader("Expires",    85),
            colHeader("Uploaded",  120),
            colHeader("Actions",   110)
        );

        // Scrollable file rows
        filesListBox = new VBox(0);
        filesListBox.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(filesListBox);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Critical: make the ScrollPane take all remaining vertical space
        VBox.setVgrow(scroll, Priority.ALWAYS);
        scroll.setMinHeight(200);
        scroll.setStyle(
            "-fx-background-color:transparent;" +
            "-fx-background:transparent;" +
            "-fx-border-color:" + theme.border() + ";" +
            "-fx-border-radius:0 0 5 5;"
        );
        // Clear viewport background so rows are visible
        scroll.skinProperty().addListener((obs, o, n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = scroll.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));

        Label placeholder = new Label("Start the server, then click Refresh.");
        placeholder.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:12 8;");
        filesListBox.getChildren().add(placeholder);

        card.getChildren().addAll(toolbar, headers, scroll);
        return card;
    }

    private Label colHeader(String text, double width) {
        Label l = new Label(text);
        l.setPrefWidth(width);
        l.setMinWidth(width);
        l.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:10px;-fx-font-weight:bold;" +
                "-fx-text-transform:uppercase;");
        return l;
    }

    private void refreshFilesList() {
        if (database == null) return;
        try {
            java.util.List<Database.FileRecord> files = database.getAllFiles();
            // Get owner usernames for each file
            java.util.Map<Integer, String> ownerNames = new java.util.HashMap<>();
            for (Database.FileRecord f : files) {
                if (!ownerNames.containsKey(f.ownerId)) {
                    String name = database.getUsername(f.ownerId);
                    ownerNames.put(f.ownerId, name != null ? name : "?");
                }
            }
            Platform.runLater(() -> {
                // Update count label
                filesCard.getChildren().stream()
                    .filter(n -> n instanceof HBox)
                    .findFirst()
                    .ifPresent(tb -> ((HBox)tb).getChildren().stream()
                        .filter(n -> n instanceof Label l && "filesCountLabel".equals(l.getId()))
                        .forEach(n -> ((Label)n).setText(files.size() + " file" +
                                (files.size()==1?"":"s") + " stored")));

                filesListBox.getChildren().clear();
                if (files.isEmpty()) {
                    Label none = new Label("No files uploaded yet.");
                    none.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:10 6;");
                    filesListBox.getChildren().add(none);
                    return;
                }

                java.time.format.DateTimeFormatter dtFmt =
                    java.time.format.DateTimeFormatter.ofPattern("MM/dd/yy HH:mm")
                        .withZone(java.time.ZoneId.systemDefault());

                for (int i = 0; i < files.size(); i++) {
                    Database.FileRecord f = files.get(i);
                    String rowBg = (i % 2 == 0) ? theme.panel() : theme.bg();

                    HBox row = new HBox(0);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(5, 10, 5, 10));
                    row.setStyle("-fx-background-color:" + rowBg + ";");

                    // Filename (truncated)
                    Label fnLbl = rowCell(f.filename, 240);
                    fnLbl.setStyle(fnLbl.getStyle() + "-fx-font-weight:600;");

                    // Owner
                    Label ownerLbl = rowCell(ownerNames.getOrDefault(f.ownerId,"?"), 80);

                    // Size
                    Label sizeLbl = rowCell(fmtBytesAdmin(f.size), 65);

                    // Access badge
                    String accessTxt = f.isPublic ? "Public" : "Private";
                    String accessClr = f.isPublic ? "#4caf72" : "#7a7f9a";
                    Label accessLbl = new Label(accessTxt);
                    accessLbl.setPrefWidth(65); accessLbl.setMinWidth(65);
                    accessLbl.setStyle("-fx-text-fill:" + accessClr +
                            ";-fx-font-size:11px;-fx-font-weight:600;");

                    // Expires
                    String expTxt;
                    String expClr;
                    if (f.expires == 0) {
                        expTxt = "Never"; expClr = "#5b6af7";
                    } else {
                        long now = System.currentTimeMillis() / 1000;
                        long diff = f.expires - now;
                        if (diff <= 0) {
                            expTxt = "Expired"; expClr = "#e05c6e";
                        } else {
                            long days = diff / 86400;
                            expTxt = days == 0 ? "<1 day" : days + "d";
                            expClr = days <= 3 ? "#e05c6e" : "#4caf72";
                        }
                    }
                    Label expLbl = new Label(expTxt);
                    expLbl.setPrefWidth(85); expLbl.setMinWidth(85);
                    expLbl.setStyle("-fx-text-fill:" + expClr + ";-fx-font-size:11px;-fx-font-weight:600;");

                    // Uploaded
                    String uploadedStr = java.time.Instant.ofEpochSecond(f.uploaded).atZone(
                            java.time.ZoneId.systemDefault()).format(dtFmt);
                    Label uploadLbl = rowCell(uploadedStr, 120);

                    // Action buttons
                    HBox actions = new HBox(4);
                    actions.setPrefWidth(110); actions.setMinWidth(110);
                    actions.setAlignment(Pos.CENTER_LEFT);

                    Button expiryBtn = new Button("Expiry");
                    styleSmallBtn(expiryBtn, ACCENT, ACCENTH);
                    final int fid = f.id;
                    final String fname = f.filename;
                    final long curExpires = f.expires;
                    expiryBtn.setOnAction(e -> showAdminExpiryDialog(fid, fname, curExpires));

                    Button delBtn = new Button("Delete");
                    styleSmallBtn(delBtn, DANGER, DANGERH);
                    delBtn.setOnAction(e -> adminDeleteFile(fid, fname));

                    actions.getChildren().addAll(expiryBtn, delBtn);

                    row.getChildren().addAll(fnLbl, ownerLbl, sizeLbl, accessLbl,
                            expLbl, uploadLbl, actions);
                    // ensure row fills full width
                    HBox.setHgrow(fnLbl, Priority.NEVER);
                    filesListBox.getChildren().add(row);
                }
            });
        } catch (Exception ex) {
            log("ERROR listing files: " + ex.getMessage());
        }
    }

    private Label rowCell(String text, double width) {
        Label l = new Label(text);
        l.setPrefWidth(width); l.setMinWidth(width);
        l.setMaxWidth(width);
        l.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-size:11px;");
        l.setEllipsisString("…");
        return l;
    }

    private void adminDeleteFile(int fileId, String filename) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete \"" + filename + "\"?\nThe file will be permanently removed from disk.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete File");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt != ButtonType.YES) return;
            try {
                Database.FileRecord rec = database.getFile(fileId);
                if (rec != null) {
                    java.nio.file.Path uploadDir = java.nio.file.Path.of(
                            uploadDirField.getText().trim());
                    Files.deleteIfExists(uploadDir.resolve(rec.storedName));
                }
                database.deleteFile(fileId);
                log("ADMIN deleted file: " + filename);
                refreshFilesList();
            } catch (Exception ex) {
                log("ERROR deleting file: " + ex.getMessage());
            }
        });
    }

    private void showAdminExpiryDialog(int fileId, String filename, long currentExpires) {
        Stage dlg = dialogStage("Set Expiry — " + filename);

        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:#1a1d27;");

        Label title = new Label("Set expiry for: " + filename);
        title.setStyle("-fx-text-fill:#e2e4f0;-fx-font-weight:bold;-fx-font-size:12px;");
        title.setWrapText(true);

        // Show current expiry info
        String curStr;
        if (currentExpires == 0) {
            curStr = "Current: Never expires";
        } else {
            long diff = currentExpires - System.currentTimeMillis()/1000;
            curStr = diff <= 0 ? "Current: Already expired"
                    : "Current: expires in " + (diff/86400) + " day(s)";
        }
        Label curLbl = new Label(curStr);
        curLbl.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");

        ComboBox<String> combo = new ComboBox<>();
        combo.getItems().addAll(
            "Never expires",
            "1 day from now",
            "3 days from now",
            "7 days from now",
            "14 days from now",
            "30 days from now",
            "90 days from now",
            "1 year from now"
        );
        combo.setValue("Never expires");
        combo.setPrefWidth(220);
        combo.setMaxWidth(220);
        combo.setStyle(
            "-fx-background-color:#0f1117;" +
            "-fx-border-color:#2e3148;" +
            "-fx-border-radius:5;-fx-background-radius:5;" +
            "-fx-padding:2 4;"
        );
        // Cell factory — dark background (#1a1d27) for the dropdown list,
        // light text (#e2e4f0) for both the selected value and list items.
        javafx.util.Callback<javafx.scene.control.ListView<String>,
                             javafx.scene.control.ListCell<String>> cellFactory =
            lv -> {
                if (lv != null) {
                    // Style the popup list view itself
                    lv.setStyle("-fx-background-color:#1a1d27;-fx-border-color:#2e3148;");
                }
                return new javafx.scene.control.ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item);
                        setStyle("-fx-background-color:#1a1d27;-fx-text-fill:#e2e4f0;" +
                                 "-fx-font-size:12px;-fx-padding:5 8;");
                    }
                    @Override public void updateSelected(boolean selected) {
                        super.updateSelected(selected);
                        if (!isEmpty())
                            setStyle("-fx-background-color:" + (selected ? "#2e3148" : "#1a1d27") +
                                     ";-fx-text-fill:#e2e4f0;-fx-font-size:12px;-fx-padding:5 8;");
                    }
                };
            };
        combo.setCellFactory(cellFactory);
        combo.setButtonCell(cellFactory.call(null));

        int[] dayMap = {0, 1, 3, 7, 14, 30, 90, 365};

        Label err = errorLabel();

        // Button row: Cancel + Save side by side
        HBox btnRow = new HBox(8);
        btnRow.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:#7a7f9a;" +
            "-fx-border-color:#2e3148;-fx-border-radius:6;-fx-background-radius:6;" +
            "-fx-cursor:hand;-fx-padding:7 16;"
        );
        cancelBtn.setOnMouseEntered(e -> cancelBtn.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:#e2e4f0;" +
            "-fx-border-color:#5b6af7;-fx-border-radius:6;-fx-background-radius:6;" +
            "-fx-cursor:hand;-fx-padding:7 16;"
        ));
        cancelBtn.setOnMouseExited(e -> cancelBtn.setStyle(
            "-fx-background-color:transparent;-fx-text-fill:#7a7f9a;" +
            "-fx-border-color:#2e3148;-fx-border-radius:6;-fx-background-radius:6;" +
            "-fx-cursor:hand;-fx-padding:7 16;"
        ));
        cancelBtn.setOnAction(e -> dlg.close());

        Button saveBtn = primaryBtn("Save");
        saveBtn.setMaxWidth(100);

        saveBtn.setOnAction(e -> {
            int idx = combo.getSelectionModel().getSelectedIndex();
            int days = (idx >= 0 && idx < dayMap.length) ? dayMap[idx] : 0;
            long newExpires = days == 0 ? 0
                    : System.currentTimeMillis()/1000 + (long)days * 86400;
            try {
                database.setFileExpiry(fileId, newExpires);
                log("ADMIN set expiry for \"" + filename + "\" to " +
                        (days == 0 ? "never" : days + " days"));
                dlg.close();
                refreshFilesList();
            } catch (Exception ex) {
                err.setText("Error: " + ex.getMessage());
            }
        });

        btnRow.getChildren().addAll(cancelBtn, saveBtn);

        box.getChildren().addAll(title, curLbl, combo, err, btnRow);
        dlg.setScene(new Scene(box, 320, 245));
        dlg.showAndWait();
    }

    // ── Archive card ─────────────────────────────────────────────────────

    private VBox buildArchiveCard() {
        VBox card = new VBox(10);

        HBox header = new HBox(8); header.setAlignment(Pos.CENTER_RIGHT);
        Button refreshBtn = accentOutlineBtn("Refresh");
        refreshBtn.setOnAction(e -> refreshArchiveList());
        header.getChildren().add(refreshBtn);

        Label info = new Label("Files moved here after expiry. Only you (the admin) can see or delete them.");
        info.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");
        info.setWrapText(true);

        archiveListBox = new VBox(0);
        archiveListBox.setFillWidth(true);

        ScrollPane scroll = new ScrollPane(archiveListBox);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(150);
        scroll.setMinHeight(60);
        scroll.setStyle(
            "-fx-background-color:transparent;-fx-background:transparent;" +
            "-fx-border-color:" + theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;"
        );
        scroll.skinProperty().addListener((obs, o, n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = scroll.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));

        Label placeholder = new Label("No archived files.");
        placeholder.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:8 4;");
        archiveListBox.getChildren().add(placeholder);

        card.getChildren().addAll(header, info, scroll);
        return card;
    }

    private void refreshArchiveList() {
        if (database == null) return;
        try {
            java.util.List<Database.ArchivedRecord> files = database.getArchivedFiles();
            Platform.runLater(() -> {
                archiveListBox.getChildren().clear();
                if (files.isEmpty()) {
                    Label none = new Label("No archived files.");
                    none.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:8 4;");
                    archiveListBox.getChildren().add(none);
                    return;
                }
                for (int i = 0; i < files.size(); i++) {
                    Database.ArchivedRecord rec = files.get(i);
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(6, 10, 6, 10));
                    String rowBg = (i % 2 == 0) ? theme.panel() : theme.bg();
                    row.setStyle("-fx-background-color:" + rowBg + ";");

                    // Filename + metadata
                    VBox info = new VBox(2);
                    Label nameLabel = new Label(rec.filename());
                    nameLabel.setStyle("-fx-text-fill:" + theme.text() +
                            ";-fx-font-size:12px;-fx-font-weight:600;");
                    java.time.format.DateTimeFormatter fmt =
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                    String expiredStr = java.time.Instant.ofEpochSecond(rec.expiredAt())
                            .atZone(java.time.ZoneId.systemDefault()).format(fmt);
                    Label metaLabel = new Label(
                            fmtBytesAdmin(rec.size()) + "  •  owner: " + rec.owner() +
                            "  •  expired: " + expiredStr);
                    metaLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:10px;");
                    info.getChildren().addAll(nameLabel, metaLabel);

                    Region stretch = new Region(); HBox.setHgrow(stretch, Priority.ALWAYS);

                    Button delBtn = new Button("Delete");
                    styleSmallBtn(delBtn, DANGER, DANGERH);
                    final int archId = rec.id();
                    final String fname = rec.filename();
                    delBtn.setOnAction(e -> deleteArchivedFile(archId, fname));

                    row.getChildren().addAll(info, stretch, delBtn);
                    archiveListBox.getChildren().add(row);
                }
            });
        } catch (Exception ex) {
            log("ERROR listing archive: " + ex.getMessage());
        }
    }

    private void deleteArchivedFile(int archId, String filename) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Permanently delete archived file '" + filename + "'?\nThis cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete Archived File");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                try {
                    String storedName = database.deleteArchivedFile(archId);
                    if (storedName != null && archiveDir != null) {
                        Files.deleteIfExists(archiveDir.resolve(storedName));
                    }
                    log("ADMIN deleted archived file: " + filename);
                    refreshArchiveList();
                } catch (Exception ex) {
                    log("ERROR deleting archive file: " + ex.getMessage());
                }
            }
        });
    }

    private static String fmtBytesAdmin(long b) {
        if (b < 1024)                return b + " B";
        if (b < 1024 * 1024)         return String.format("%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.2f MB", b / (1024.0 * 1024));
        return String.format("%.2f GB", b / (1024.0 * 1024 * 1024));
    }

    // ── Log card ──────────────────────────────────────────────────────────

    private ComboBox<String> logFileCombo;

    private VBox buildLogCard() {
        VBox card = new VBox(8);
        VBox.setVgrow(card, Priority.ALWAYS);

        // Toolbar
        HBox header = new HBox(8); header.setAlignment(Pos.CENTER_LEFT);

        // File selector label
        Label fileLbl = new Label("Viewing:");
        fileLbl.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;");

        // Log file ComboBox
        logFileCombo = new ComboBox<>();
        logFileCombo.setPrefWidth(200);
        logFileCombo.setStyle(
            "-fx-background-color:#0f1117;-fx-border-color:#2e3148;" +
            "-fx-border-radius:5;-fx-background-radius:5;-fx-padding:2 4;"
        );
        // Style combo cells dark
        javafx.util.Callback<javafx.scene.control.ListView<String>,
                             javafx.scene.control.ListCell<String>> logCellFactory =
            lv -> {
                if (lv != null) lv.setStyle("-fx-background-color:#1a1d27;-fx-border-color:#2e3148;");
                return new javafx.scene.control.ListCell<>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        setText(empty || item == null ? null : item);
                        setStyle("-fx-background-color:#1a1d27;-fx-text-fill:#e2e4f0;" +
                                 "-fx-font-size:12px;-fx-padding:4 8;");
                    }
                    @Override public void updateSelected(boolean sel) {
                        super.updateSelected(sel);
                        if (!isEmpty())
                            setStyle("-fx-background-color:" + (sel?"#2e3148":"#1a1d27") +
                                     ";-fx-text-fill:#e2e4f0;-fx-font-size:12px;-fx-padding:4 8;");
                    }
                };
            };
        logFileCombo.setCellFactory(logCellFactory);
        logFileCombo.setButtonCell(logCellFactory.call(null));
        logFileCombo.getItems().add("sharewave.log");
        logFileCombo.setValue("sharewave.log");
        logFileCombo.setOnAction(e -> loadSelectedLogFile());

        Button refreshLogBtn = accentOutlineBtn("Refresh");
        refreshLogBtn.setOnAction(e -> {
            populateLogFileCombo();
            loadSelectedLogFile();
        });

        Button clearBtn = accentOutlineBtn("Clear View");
        clearBtn.setOnAction(e -> logArea.clear());

        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        header.getChildren().addAll(fileLbl, logFileCombo, refreshLogBtn, sp, clearBtn);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(10);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        card.getChildren().addAll(header, logArea);
        return card;
    }

    private void populateLogFileCombo() {
        if (fileLogger == null) return;
        Platform.runLater(() -> {
            String current = logFileCombo.getValue();
            logFileCombo.getItems().setAll(fileLogger.availableLogFiles());
            if (current != null && logFileCombo.getItems().contains(current))
                logFileCombo.setValue(current);
            else if (!logFileCombo.getItems().isEmpty())
                logFileCombo.setValue(logFileCombo.getItems().get(0));
        });
    }

    private void loadSelectedLogFile() {
        if (fileLogger == null) return;
        String selected = logFileCombo.getValue();
        if (selected == null) return;
        new Thread(() -> {
            try {
                String content = fileLogger.readLogFile(selected);
                Platform.runLater(() -> {
                    logArea.setText(content);
                    // Scroll to bottom for current log, top for old files
                    if (selected.equals("sharewave.log"))
                        logArea.setScrollTop(Double.MAX_VALUE);
                    else
                        logArea.setScrollTop(0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> logArea.setText("Error reading log: " + ex.getMessage()));
            }
        }, "log-reader").start();
    }

    // ═════════════════════════════════════════════════════════════════════
    // User management
    // ═════════════════════════════════════════════════════════════════════

    private void refreshUserList() {
        if (database == null) return;
        try {
            List<String> users = database.getAllUsernames();
            Platform.runLater(() -> {
                userListBox.getChildren().clear();
                if (users.isEmpty()) {
                    Label none = new Label("No users registered.");
                    none.setStyle("-fx-text-fill:#7a7f9a;-fx-font-size:11px;-fx-padding:4 0 0 4;");
                    userListBox.getChildren().add(none);
                    return;
                }
                for (int i = 0; i < users.size(); i++) {
                    String u = users.get(i);
                    HBox row = new HBox(10);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(7, 10, 7, 10));
                    // Alternate row shading for readability
                    String rowBg = (i % 2 == 0) ? theme.panel() : theme.bg();
                    row.setStyle("-fx-background-color:" + rowBg + ";");

                    Label uLabel = new Label(u);
                    uLabel.setStyle("-fx-text-fill:" + theme.text() +
                            ";-fx-font-size:12px;-fx-font-weight:600;");
                    Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);

                    Button resetBtn = new Button("Reset Password");
                    styleSmallBtn(resetBtn, ACCENT, ACCENTH);
                    resetBtn.setOnAction(e -> showResetPasswordDialog(u));

                    Button delBtn = new Button("Delete");
                    styleSmallBtn(delBtn, DANGER, DANGERH);
                    delBtn.setOnAction(e -> deleteUser(u));

                    row.getChildren().addAll(uLabel, sp, resetBtn, delBtn);
                    userListBox.getChildren().add(row);
                }
            });
        } catch (Exception ex) {
            log("ERROR listing users: " + ex.getMessage());
        }
    }

    private void deleteUser(String username) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete user '" + username + "'? This cannot be undone.",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Delete User");
        confirm.setHeaderText(null);
        confirm.showAndWait().ifPresent(bt -> {
            if (bt == ButtonType.YES) {
                try {
                    database.deleteUser(username);
                    log("ADMIN deleted user: " + username);
                    refreshUserList();
                } catch (Exception ex) {
                    log("ERROR deleting user: " + ex.getMessage());
                }
            }
        });
    }

    private void showResetPasswordDialog(String username) {
        Stage dlg = dialogStage("Reset Password — " + username);

        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:#1a1d27;");

        Label title = new Label("Reset password for: " + username);
        title.setStyle("-fx-text-fill:#e2e4f0;-fx-font-weight:bold;");

        PasswordField pw1 = dialogPasswordField("New password");
        PasswordField pw2 = dialogPasswordField("Confirm password");
        Label         err = errorLabel();
        Button        btn = primaryBtn("Reset");

        btn.setOnAction(e -> {
            String p1 = pw1.getText(), p2 = pw2.getText();
            if (p1.length() < 4) { err.setText("Minimum 4 characters."); return; }
            if (!p1.equals(p2))  { err.setText("Passwords do not match."); return; }
            try {
                database.setPassword(username, p1);
                log("ADMIN reset password for: " + username);
                dlg.close();
            } catch (Exception ex) {
                err.setText("Error: " + ex.getMessage());
            }
        });
        pw2.setOnAction(e -> btn.fire());

        box.getChildren().addAll(title, pw1, pw2, err, btn);
        dlg.setScene(new Scene(box, 320, 230));
        dlg.showAndWait();
    }

    private void changeAdminPassword() {
        Stage dlg = dialogStage("Change Admin Password");

        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:#1a1d27;");

        Label title = new Label("Change Admin Password");
        title.setStyle("-fx-text-fill:#e2e4f0;-fx-font-weight:bold;");

        PasswordField current = dialogPasswordField("Current password");
        PasswordField pw1     = dialogPasswordField("New password");
        PasswordField pw2     = dialogPasswordField("Confirm new password");
        Label         err     = errorLabel();
        Button        btn     = primaryBtn("Change");

        btn.setOnAction(e -> {
            if (!adminConfig.checkPassword(current.getText())) {
                err.setText("Current password is incorrect."); return;
            }
            String p1 = pw1.getText(), p2 = pw2.getText();
            if (p1.length() < 4) { err.setText("Minimum 4 characters."); return; }
            if (!p1.equals(p2))  { err.setText("Passwords do not match."); return; }
            adminConfig.setPassword(p1);
            log("Admin password changed.");
            dlg.close();
        });
        pw2.setOnAction(e -> btn.fire());

        box.getChildren().addAll(title, current, pw1, pw2, err, btn);
        dlg.setScene(new Scene(box, 320, 270));
        dlg.showAndWait();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Theme
    // ═════════════════════════════════════════════════════════════════════

    private void toggleTheme() {
        isDark = !isDark;
        theme  = isDark ? Theme.DARK : Theme.LIGHT;
        themeBtn.setText(isDark ? "☀ Light" : "🌙 Dark");
        config.setTheme(isDark ? "dark" : "light");
        config.save();
        applyTheme();
    }

    private void applyTheme() {
        scene.setFill(Color.web(theme.bg()));
        root.setStyle("-fx-background-color:" + theme.bg() + ";");

        // Style the TabPane via CSS injection (setStyle can't reach the header strip)
        if (tabPane != null && scene != null) {
            scene.getStylesheets().clear();
            String bg = theme.bg(), panel = theme.panel(), border = theme.border(),
                   text = theme.text(), muted = theme.muted();
            String css = String.format("""
                .tab-pane > .tab-header-area { -fx-background-color: %s; -fx-padding: 0; }
                .tab-pane > .tab-header-area > .tab-header-background { -fx-background-color: %s; }
                .tab-pane > .tab-header-area > .headers-region > .tab {
                    -fx-background-color: %s; -fx-background-radius: 5 5 0 0; -fx-padding: 5 14 5 14; }
                .tab-pane > .tab-header-area > .headers-region > .tab:selected { -fx-background-color: %s; }
                .tab-pane > .tab-header-area > .headers-region > .tab .tab-label {
                    -fx-text-fill: %s; -fx-font-size: 12px; }
                .tab-pane > .tab-header-area > .headers-region > .tab:selected .tab-label {
                    -fx-text-fill: %s; -fx-font-weight: bold; }
                .tab-pane > .tab-content-area { -fx-background-color: %s; }
                """, bg, bg, bg, panel, muted, text, bg);
            scene.getStylesheets().add("data:text/css," +
                css.replace(" ","%20").replace("\n","%0A").replace("{","%7B").replace("}","%7D")
                   .replace(":","%3A").replace(";","%3B").replace("#","%23").replace(".","%2E")
                   .replace(">","%3E").replace("-","%2D").replace("(","%28").replace(")","%29")
                   .replace(",","%2C").replace("'","%27").replace("/","%2F").replace("+","%2B")
                   .replace("*","%2A"));
        }

        String cardStyle = cardStyle();
        configCard.setStyle(cardStyle);
        statusCard.setStyle("-fx-background-color:" + theme.panel() + ";" +
                "-fx-border-color:" + theme.border() + ";" +
                "-fx-border-radius:8;-fx-background-radius:8;-fx-padding:10 14;");
        usersCard.setStyle(cardStyle);
        archiveCard.setStyle(cardStyle);
        filesCard.setStyle(cardStyle);
        logCard.setStyle(cardStyle);

        styleConfigCardChildren();

        statusCard.getChildren().stream()
                .filter(n -> n instanceof Label l && l.getText().equals("·"))
                .forEach(n -> ((Label) n).setStyle(
                        "-fx-text-fill:" + theme.border() + ";-fx-font-size:16px;"));

        String tfStyle = textFieldStyle();
        portField.setStyle(tfStyle);
        uploadDirField.setStyle(tfStyle);
        dbPathField.setStyle(tfStyle);
        keystoreField.setStyle(tfStyle);

        logArea.setStyle(
                "-fx-control-inner-background:" + theme.logBg() + ";" +
                "-fx-text-fill:" + theme.logText() + ";" +
                "-fx-font-family:'Consolas','Courier New',monospace;" +
                "-fx-font-size:12px;" +
                "-fx-border-color:" + theme.border() + ";" +
                "-fx-background-radius:6;-fx-border-radius:6;"
        );

        String tbBase = "-fx-background-color:" + theme.panel() +
                ";-fx-text-fill:" + theme.muted() +
                ";-fx-border-color:" + theme.border() +
                ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:4 10;-fx-font-size:11px;";
        themeBtn.setStyle(tbBase);
        themeBtn.setOnMouseEntered(e -> themeBtn.setStyle(tbBase.replace(theme.muted(), theme.text())));
        themeBtn.setOnMouseExited(e  -> themeBtn.setStyle(tbBase));
    }

    private void styleConfigCardChildren() {
        String lblStyle = "-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;";
        String infoStyle = "-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;";
        for (VBox card : new VBox[]{configCard, usersCard, archiveCard, filesCard, logCard}) {
            card.getChildren().forEach(node -> {
                // Style muted info labels
                if (node instanceof Label l) l.setStyle(infoStyle);
                // Style field labels inside HBox rows
                if (node instanceof HBox row) row.getChildren().forEach(child -> {
                    if (child instanceof Label l) l.setStyle(lblStyle);
                });
            });
        }
        // Re-apply tab pane styling
        if (tabPane != null) {
            String tabStyle =
                "-fx-background-color:" + theme.bg() + ";" +
                "-fx-tab-header-background:" + theme.panel() + ";";
            tabPane.setStyle(tabStyle);
        }
    }

    private String cardStyle() {
        return "-fx-background-color:" + theme.panel() + ";" +
               "-fx-border-color:" + theme.border() + ";" +
               "-fx-border-radius:8;-fx-background-radius:8;-fx-padding:14 16;";
    }

    private String textFieldStyle() {
        return "-fx-background-color:" + theme.input() + ";" +
               "-fx-text-fill:" + theme.text() + ";" +
               "-fx-border-color:" + theme.border() + ";" +
               "-fx-border-radius:5;-fx-background-radius:5;-fx-padding:5 8;";
    }

    // ═════════════════════════════════════════════════════════════════════
    // Server control
    // ═════════════════════════════════════════════════════════════════════

    private void toggleServer() {
        if (server == null) startServer(); else stopServer();
    }

    private void startServer() {
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { alert("Invalid port number."); return; }
        if (port < 1 || port > 65535) { alert("Port must be 1–65535."); return; }

        Path   uploadDir = Path.of(uploadDirField.getText().trim());
        String dbPath    = dbPathField.getText().trim();

        saveConfig();

        new Thread(() -> {
            try {
                database = new Database(dbPath);
                database.open();

                SessionManager   sessions = new SessionManager();
                ShareWaveHandler servlet  = new ShareWaveHandler(
                        database, sessions, uploadDir, this::log);

                String keystorePath = keystoreField.getText().trim();
                if (!CertUtil.ensureKeystore(keystorePath, this::log))
                    throw new Exception("Could not create/find keystore at: " + keystorePath);

                SslContextFactory.Server sslCtx = new SslContextFactory.Server();
                sslCtx.setKeyStorePath(keystorePath);
                sslCtx.setKeyStorePassword(CertUtil.KEYSTORE_PASSWORD);
                sslCtx.setKeyManagerPassword(CertUtil.KEYSTORE_PASSWORD);
                sslCtx.setKeyStoreType("PKCS12");

                HttpConfiguration httpsConfig = new HttpConfiguration();
                httpsConfig.setSecureScheme("https");
                httpsConfig.setSecurePort(port);
                SecureRequestCustomizer src = new SecureRequestCustomizer();
                src.setSniHostCheck(false);
                httpsConfig.addCustomizer(src);

                server = new Server();
                ServerConnector httpsConnector = new ServerConnector(server,
                        new SslConnectionFactory(sslCtx, "http/1.1"),
                        new HttpConnectionFactory(httpsConfig));
                httpsConnector.setPort(port);
                server.addConnector(httpsConnector);

                ServletContextHandler ctx =
                        new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
                ctx.setContextPath("/");
                ServletHolder holder = new ServletHolder(servlet);
                holder.getRegistration().setMultipartConfig(servlet.multipartConfig());
                ctx.addServlet(holder, "/*");
                server.setHandler(ctx);
                server.start();

                String url = "https://localhost:" + port + "/";
                Platform.runLater(() -> {
                    statusLabel.setText("RUNNING");
                    statusLabel.setStyle("-fx-text-fill:" + GREEN +
                            ";-fx-font-weight:bold;-fx-font-size:13px;");
                    statusDot.setFill(Color.web(GREEN));
                    urlLabel.setText(url);
                    applyToggleStyle(toggleBtn, true);
                    toggleBtn.setText("■  Stop Server");
                    portField.setDisable(true);
                    uploadDirField.setDisable(true);
                    dbPathField.setDisable(true);
                    keystoreField.setDisable(true);
                    refreshUserList();
                    refreshArchiveList();
                    refreshFilesList();
                    populateLogFileCombo();
                });
                // Initialise archive directory
                archiveDir = uploadDir.resolve("archive");
                try { Files.createDirectories(archiveDir); } catch (Exception ignored) {}

                // Start file logger
                try {
                    fileLogger = new FileLogger(uploadDir);
                    log("Log dir    : " + fileLogger.getLogDir().toAbsolutePath());
                } catch (Exception e) {
                    log("WARN: could not start file logger: " + e.getMessage());
                }

                // Start hourly purge — moves expired files to archive dir
                final Database db2  = database;
                final Path upDir    = uploadDir;
                final Path archDir  = archiveDir;
                purgeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "expiry-purge");
                    t.setDaemon(true);
                    return t;
                });
                purgeScheduler.scheduleAtFixedRate(() -> {
                    try {
                        java.util.List<Database.ArchivedRecord> purged = db2.purgeExpiredFiles();
                        for (Database.ArchivedRecord rec : purged) {
                            Path srcFile  = upDir.resolve(rec.storedName());
                            Path destFile = archDir.resolve(rec.storedName());
                            try {
                                if (Files.exists(srcFile))
                                    Files.move(srcFile, destFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } catch (Exception ignored) {}
                            log("ARCHIVED expired: " + rec.filename() + " (owner: " + rec.owner() + ")");
                        }
                        if (!purged.isEmpty()) Platform.runLater(() -> refreshArchiveList());
                    } catch (Exception e) {
                        log("PURGE ERROR: " + e.getMessage());
                    }
                }, 0, 60, TimeUnit.MINUTES);

                log("Server started on port " + port);
                log("Upload dir : " + uploadDir.toAbsolutePath());
                log("Database   : " + dbPath);
                log("Browse to  : " + url);

            } catch (Exception e) {
                if (database != null) { database.close(); database = null; }
                server = null;
                log("ERROR starting server: " + e.getMessage());
                Platform.runLater(() -> alert("Failed to start: " + e.getMessage()));
            }
        }, "server-start").start();
    }

    private void stopServer() {
        new Thread(() -> {
            try { if (server != null) { server.stop(); server = null; } }
            catch (Exception ignored) {}
            if (purgeScheduler != null) { purgeScheduler.shutdownNow(); purgeScheduler = null; }
            if (fileLogger != null) { fileLogger.close(); fileLogger = null; }
            if (database != null) { database.close(); database = null; }
            Platform.runLater(() -> {
                statusLabel.setText("STOPPED");
                statusLabel.setStyle("-fx-text-fill:" + DANGER +
                        ";-fx-font-weight:bold;-fx-font-size:13px;");
                statusDot.setFill(Color.web(DANGER));
                urlLabel.setText("—");
                applyToggleStyle(toggleBtn, false);
                toggleBtn.setText("▶  Start Server");
                portField.setDisable(false);
                uploadDirField.setDisable(false);
                dbPathField.setDisable(false);
                keystoreField.setDisable(false);
            });
            log("Server stopped.");
        }, "server-stop").start();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Persistence
    // ═════════════════════════════════════════════════════════════════════

    private void saveConfig() {
        config.setPort(portField.getText().trim());
        config.setUploadDir(uploadDirField.getText().trim());
        config.setDbPath(dbPathField.getText().trim());
        config.setKeystore(keystoreField.getText().trim());
        config.setTheme(isDark ? "dark" : "light");
        config.save();
    }

    // ═════════════════════════════════════════════════════════════════════
    // UI helpers
    // ═════════════════════════════════════════════════════════════════════

    private void browseDir() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Upload Directory");
        File f = dc.showDialog(primaryStage);
        if (f != null) { uploadDirField.setText(f.getAbsolutePath()); saveConfig(); }
    }

    private void browseKeystore() {
        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Select Keystore File");
        fc.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("PKCS12 Keystore", "*.keystore","*.p12"),
                new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*"));
        File f = fc.showOpenDialog(primaryStage);
        if (f != null) { keystoreField.setText(f.getAbsolutePath()); saveConfig(); }
    }

    private void log(String msg) {
        String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        // Write to file logger (includes full timestamp)
        if (fileLogger != null) fileLogger.log(msg);
        Platform.runLater(() -> {
            if (logArea == null) return;
            // Only append to live view when showing the current log file
            boolean showingCurrent = logFileCombo == null
                    || "sharewave.log".equals(logFileCombo.getValue());
            if (showingCurrent) {
                logArea.appendText("[" + ts + "] " + msg + "\n");
            }
        });
    }

    private void alert(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            a.setTitle("ShareWave"); a.setHeaderText(null); a.showAndWait();
        });
    }

    // ── Dialog factory helpers ────────────────────────────────────────────

    private Stage dialogStage(String title) {
        Stage dlg = new Stage(StageStyle.DECORATED);
        dlg.setTitle(title);
        dlg.initModality(Modality.APPLICATION_MODAL);
        dlg.setResizable(false);
        return dlg;
    }

    private PasswordField dialogPasswordField(String prompt) {
        PasswordField pf = new PasswordField();
        pf.setPromptText(prompt);
        pf.setStyle("-fx-background-color:#0f1117;-fx-text-fill:#e2e4f0;" +
                "-fx-border-color:#2e3148;-fx-border-radius:5;" +
                "-fx-background-radius:5;-fx-padding:6 8;");
        return pf;
    }

    private Label errorLabel() {
        Label l = new Label("");
        l.setStyle("-fx-text-fill:#f18899;-fx-font-size:11px;");
        l.setWrapText(true);
        return l;
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text);
        b.setMaxWidth(Double.MAX_VALUE);
        String base = "-fx-background-color:" + ACCENT + ";-fx-text-fill:white;" +
                "-fx-font-weight:bold;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:7 0;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(ACCENT, ACCENTH)));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private TextField styledTextField(String def, double prefWidth) {
        TextField tf = new TextField(def);
        tf.setPrefWidth(prefWidth);
        tf.setStyle(textFieldStyle());
        return tf;
    }

    private Label fieldLabel(String text, double minWidth) {
        Label l = new Label(text);
        l.setMinWidth(minWidth);
        l.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        return l;
    }

    private Button accentOutlineBtn(String text) {
        Button b = new Button(text);
        String base = "-fx-background-color:transparent;-fx-text-fill:" + theme.muted() + ";" +
                "-fx-border-color:" + theme.border() + ";-fx-border-radius:6;" +
                "-fx-background-radius:6;-fx-cursor:hand;-fx-padding:5 12;";
        String hov  = base.replace(theme.muted(), theme.text())
                          .replace(theme.border(), ACCENT);
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(hov));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private void styleSmallBtn(Button b, String bg, String hover) {
        String base = "-fx-background-color:" + bg + ";-fx-text-fill:white;" +
                "-fx-background-radius:5;-fx-cursor:hand;" +
                "-fx-font-size:11px;-fx-padding:3 9;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(bg, hover)));
        b.setOnMouseExited(e  -> b.setStyle(base));
    }

    private void applyToggleStyle(Button b, boolean isStop) {
        String bg    = isStop ? DANGER  : ACCENT;
        String hover = isStop ? DANGERH : ACCENTH;
        String base = "-fx-background-color:" + bg + ";-fx-text-fill:white;" +
                "-fx-font-weight:bold;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-font-size:13px;-fx-padding:8 24;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(bg, hover)));
        b.setOnMouseExited(e  -> b.setStyle(base));
    }

    public static void main(String[] args) { launch(args); }
}
