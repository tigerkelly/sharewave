package com.sharewave.gui;

import com.google.gson.*;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.TabPane.TabClosingPolicy;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.*;
import javafx.util.Duration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * ShareWave GUI — connects to a running sharewave-server process
 * via the management TCP port.
 */
public class GuiApp extends Application {

    // ── Theme ─────────────────────────────────────────────────────────────────
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

    private static final String ACCENT  = "#5b6af7";
    private static final String ACCENTH = "#7b8aff";
    private static final String DANGER  = "#e05c6e";
    private static final String DANGERH = "#f07080";
    private static final String GREEN   = "#4caf72";

    // ── State ─────────────────────────────────────────────────────────────────
    private final GuiConfig         guiConfig   = new GuiConfig();
    private final AdminConfig       adminConfig = new AdminConfig();
    private ManagementClient        client;
    private volatile boolean        loggedIn    = false;

    // ── UI ────────────────────────────────────────────────────────────────────
    private Stage       primaryStage;
    private Scene       scene;
    private VBox        root;
    private TabPane     tabPane;
    private Label       statusLabel;
    private javafx.scene.shape.Circle statusDot;
    private Label       serverUrlLabel;
    private Label       statusSep;

    // Tab content roots
    private VBox  serverCard, filesCard, usersCard, archiveCard, logCard;
    private VBox  filesListBox, usersListBox, archiveListBox;
    private TextArea logArea;
    private ComboBox<String> logFileCombo;  // placeholder — server streams live

    // Connection fields (shown before connected)
    private TextField  hostField, mgmtPortField;
    private Button     connectBtn, themeBtn;

    // Server config fields
    private TextField webPortField, uploadDirField, dbPathField, keystoreField, srvMgmtPortField, siteTitleField, sessionTimeoutField;
    private Label titleHelp;
    private Label sessionTimeoutUnit;
    private Label saveConfigStatus;
    private Label firstRunNote;
    private ProgressBar diskUsageBar;
    private Label diskUsageLabel, diskUsageDetailLabel;
    private Button diskRefreshBtn;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        guiConfig.load();
        adminConfig.load();
        isDark = !"light".equals(guiConfig.getTheme());
        theme  = isDark ? Theme.DARK : Theme.LIGHT;

        stage.setTitle("ShareWave Admin v" + AppVersion.get());
        stage.setMinWidth(920);
        stage.setMinHeight(680);

        // Admin gate
        if (!showAdminGate()) { Platform.exit(); return; }

        buildMainWindow(stage);
    }

    // ── Admin gate ────────────────────────────────────────────────────────────

    private boolean showAdminGate() {
        if (adminConfig.isFirstRun()) return showSetPasswordDialog();
        return showLoginDialog();
    }

    private boolean showSetPasswordDialog() {
        Stage dlg = dialogStage("ShareWave — Set Admin Password");
        boolean[] result = {false};
        VBox box = dialogBox();
        Label title = dialogTitle("Set Admin Password");
        Label sub   = dialogSub("This password protects the ShareWave GUI.");
        PasswordField pw1 = dialogPwField("New password (min 4 chars)");
        PasswordField pw2 = dialogPwField("Confirm password");
        Label err = errorLabel();
        Button btn = primaryBtn("Set Password");
        Runnable doSet = () -> {
            if (pw1.getText().length() < 4) { err.setText("Min 4 characters."); return; }
            if (!pw1.getText().equals(pw2.getText())) { err.setText("Passwords do not match."); return; }
            adminConfig.setPassword(pw1.getText());
            result[0] = true; dlg.close();
        };
        btn.setOnAction(e -> doSet.run());
        pw2.setOnAction(e -> doSet.run());
        box.getChildren().addAll(title, sub, pw1, pw2, err, btn);
        box.setPrefWidth(360);
        dlg.setScene(new Scene(box)); dlg.sizeToScene(); dlg.showAndWait();
        return result[0];
    }

    private boolean showLoginDialog() {
        Stage dlg = dialogStage("ShareWave — Admin Login");
        boolean[] result = {false};
        int[] attempts = {0};
        VBox box = dialogBox();
        Label title = new Label("🌊 ShareWave");
        title.setStyle("-fx-text-fill:#5b6af7;-fx-font-size:18px;-fx-font-weight:bold;");
        Label sub = dialogSub("Enter admin password to continue.");
        PasswordField pw = dialogPwField("Admin password");
        Label err = errorLabel();
        Button btn = primaryBtn("Unlock");
        Runnable doLogin = () -> {
            if (adminConfig.checkPassword(pw.getText())) { result[0] = true; dlg.close(); }
            else { attempts[0]++; pw.clear();
                   err.setText("Incorrect password." + (attempts[0] >= 3 ? " (" + attempts[0] + " attempts)" : "")); }
        };
        btn.setOnAction(e -> doLogin.run());
        pw.setOnAction(e -> doLogin.run());
        box.getChildren().addAll(title, sub, pw, err, btn);
        box.setPrefWidth(340);
        dlg.setScene(new Scene(box)); dlg.sizeToScene(); dlg.showAndWait();
        return result[0];
    }

    // ── Main window ───────────────────────────────────────────────────────────

    private void buildMainWindow(Stage stage) {
        serverCard  = buildServerCard();
        filesCard   = buildFilesCard();
        usersCard   = buildUsersCard();
        archiveCard = buildArchiveCard();
        logCard     = buildLogCard();

        tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Tab serverTab  = buildTab("⚙  Server",  wrapInScroll(serverCard));
        Tab filesTab   = buildTab("📁  Files",   filesCard);
        Tab usersTab   = buildTab("👤  Users",   usersCard);
        Tab archiveTab = buildTab("🗄  Archive", archiveCard);
        Tab logTab     = buildTab("📋  Log",     logCard);

        tabPane.getTabs().addAll(serverTab, filesTab, usersTab, archiveTab, logTab);

        // Status bar
        HBox statusBar = buildStatusBar();

        root = new VBox(8);
        root.setPadding(new Insets(12));
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.getChildren().addAll(statusBar, tabPane);

        scene = new Scene(root, 960, 720);
        applyTheme();
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> { guiConfig.save(); disconnectFromServer(); });
        stage.show();
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        HBox bar = new HBox(10); bar.setAlignment(Pos.CENTER_LEFT);
        statusDot   = new javafx.scene.shape.Circle(6, Color.web(DANGER));
        statusLabel = new Label("DISCONNECTED");
        statusLabel.setStyle("-fx-text-fill:" + DANGER + ";-fx-font-weight:bold;-fx-font-size:13px;");
        statusSep = new Label("·");
        statusSep.setStyle("-fx-text-fill:" + theme.border() + ";-fx-font-size:16px;");
        serverUrlLabel = new Label("");
        serverUrlLabel.setStyle("-fx-text-fill:" + ACCENT + ";-fx-font-size:11px;");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);

        themeBtn = new Button(isDark ? "☀ Light" : "🌙 Dark");
        themeBtn.setOnAction(e -> toggleTheme());
        styleOutlineBtn(themeBtn);

        Button changePwBtn = new Button("Change Password");
        changePwBtn.setOnAction(e -> changeAdminPassword());
        changePwBtn.setTooltip(new Tooltip("Change the admin password used to log in to this GUI"));
        String changePwBase = "-fx-background-color:#ffd9a0;-fx-text-fill:#1a1d27;-fx-border-color:" +
                theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:5 12;-fx-font-weight:bold;";
        changePwBtn.setStyle(changePwBase);
        changePwBtn.setOnMouseEntered(e -> changePwBtn.setStyle(changePwBase.replace("#ffd9a0","#ffe6c0")));
        changePwBtn.setOnMouseExited(e  -> changePwBtn.setStyle(changePwBase));

        bar.getChildren().addAll(statusDot, statusLabel, statusSep, serverUrlLabel, sp, changePwBtn, themeBtn);
        return bar;
    }

    // ── Server / connection tab ───────────────────────────────────────────────

    private VBox buildServerCard() {
        VBox card = new VBox(10);
        VBox.setVgrow(card, Priority.ALWAYS);

        // ── Connection section
        Label connTitle = sectionLabel("Connect to Server");
        HBox hostRow = new HBox(8); hostRow.setAlignment(Pos.CENTER_LEFT);
        hostField = styledTF(guiConfig.getHost(), 180);
        mgmtPortField = styledTF(guiConfig.getMgmtPort(), 70);
        connectBtn = new Button("Connect");
        applyToggleStyle(connectBtn, false);
        connectBtn.setOnAction(e -> {
            if (client != null && client.isConnected()) disconnectFromServer();
            else connectToServer();
        });
        hostRow.getChildren().addAll(
            fieldLabel("Server Host", 90), hostField,
            fieldLabel("Mgmt Port", 70), mgmtPortField,
            connectBtn
        );

        // First-run help text — the server's admin password must be set
        // on the server machine before the GUI can authenticate.
        firstRunNote = new Label(
            "First time setup: on a newly installed system, run the server " +
            "by hand once before connecting —\n" +
            "    java -jar sharewave-server.jar\n" +
            "It will prompt you on the console to create the admin password. " +
            "After that, the server can run as a\n" +
            "systemd service and this GUI can connect using that password."
        );
        firstRunNote.setWrapText(true);
        firstRunNote.setStyle(
            "-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;" +
            "-fx-font-family:'Consolas','Courier New',monospace;" +
            "-fx-padding:6 10;-fx-background-color:" + theme.bg() + ";" +
            "-fx-background-radius:6;-fx-border-color:" + theme.border() +
            ";-fx-border-radius:6;"
        );

        // ── Disk usage section
        Label diskTitle = sectionLabel("Disk Usage");
        diskUsageBar = new ProgressBar(0);
        diskUsageBar.setMaxWidth(Double.MAX_VALUE);
        diskUsageBar.setPrefHeight(10);
        diskUsageLabel = new Label("Not connected");
        diskUsageLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        diskUsageDetailLabel = new Label("");
        diskUsageDetailLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        diskUsageDetailLabel.setWrapText(true);

        diskRefreshBtn = greenRefreshBtn("Refresh", "3 10", "-fx-font-size:11px;");
        diskRefreshBtn.setOnAction(e -> refreshDiskUsage());

        HBox diskLabelRow = new HBox(8, diskUsageLabel, diskRefreshBtn);
        diskLabelRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(diskUsageLabel, Priority.ALWAYS);

        VBox diskBox = new VBox(4, diskUsageBar, diskLabelRow, diskUsageDetailLabel);

        // ── Server config section (shown after connect)
        Label cfgTitle = sectionLabel("Server Configuration");

        HBox titleRow = new HBox(8); titleRow.setAlignment(Pos.CENTER_LEFT);
        siteTitleField = styledTF("", 300); HBox.setHgrow(siteTitleField, Priority.ALWAYS);
        siteTitleField.setPromptText("ShareWave");
        titleHelp = new Label("(shown in a bar below the header on the web page)");
        titleHelp.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:10px;");
        VBox titleCol = new VBox(2, siteTitleField, titleHelp);
        HBox.setHgrow(titleCol, Priority.ALWAYS);
        titleRow.getChildren().addAll(fieldLabel("Site Title", 90), titleCol);


        HBox webPortRow = new HBox(8); webPortRow.setAlignment(Pos.CENTER_LEFT);
        webPortField = styledTF("", 70);
        webPortRow.getChildren().addAll(fieldLabel("Web Port", 90), webPortField);

        HBox uploadRow = new HBox(8); uploadRow.setAlignment(Pos.CENTER_LEFT);
        uploadDirField = styledTF("", 300); HBox.setHgrow(uploadDirField, Priority.ALWAYS);
        uploadRow.getChildren().addAll(fieldLabel("Upload Dir", 90), uploadDirField);

        HBox dbRow = new HBox(8); dbRow.setAlignment(Pos.CENTER_LEFT);
        dbPathField = styledTF("", 300); HBox.setHgrow(dbPathField, Priority.ALWAYS);
        dbRow.getChildren().addAll(fieldLabel("Database", 90), dbPathField);

        HBox ksRow = new HBox(8); ksRow.setAlignment(Pos.CENTER_LEFT);
        keystoreField = styledTF("", 300); HBox.setHgrow(keystoreField, Priority.ALWAYS);
        ksRow.getChildren().addAll(fieldLabel("Keystore", 90), keystoreField);

        HBox mgmtPortRow = new HBox(8); mgmtPortRow.setAlignment(Pos.CENTER_LEFT);
        srvMgmtPortField = styledTF("", 70);
        mgmtPortRow.getChildren().addAll(fieldLabel("Mgmt Port", 90), srvMgmtPortField);

        HBox sessionTimeoutRow = new HBox(8); sessionTimeoutRow.setAlignment(Pos.CENTER_LEFT);
        sessionTimeoutField = styledTF("", 50);
        sessionTimeoutField.setPromptText("5");
        sessionTimeoutUnit = new Label("minutes (web UI inactivity timeout)");
        sessionTimeoutUnit.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        sessionTimeoutRow.getChildren().addAll(fieldLabel("Session Timeout", 90), sessionTimeoutField, sessionTimeoutUnit);

        Button saveConfigBtn = new Button("Save Config");
        applyToggleStyle(saveConfigBtn, false);
        saveConfigBtn.setStyle(saveConfigBtn.getStyle()
            .replace("-fx-padding:8 24", "-fx-padding:6 18")
            .replace("-fx-font-size:13px", "-fx-font-size:12px"));
        saveConfigBtn.setOnAction(e -> saveServerConfig());

        saveConfigStatus = new Label("");
        saveConfigStatus.setStyle("-fx-font-size:11px;");
        HBox saveRow = new HBox(10, saveConfigBtn, saveConfigStatus);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(
            connTitle, hostRow, firstRunNote,
            new Separator(),
            diskTitle, diskBox,
            new Separator(),
            cfgTitle, titleRow, webPortRow, uploadRow, dbRow, ksRow, mgmtPortRow, sessionTimeoutRow, saveRow
        );
        return card;
    }

    // ── Files tab ─────────────────────────────────────────────────────────────

    private VBox buildFilesCard() {
        VBox card = new VBox(6); card.setPadding(new Insets(10, 12, 10, 12));
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox toolbar = new HBox(8); toolbar.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(""); lbl.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        lbl.setId("filesCountLabel");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button refresh = greenRefreshBtn("Refresh");
        refresh.setOnAction(e -> refreshFiles());
        toolbar.getChildren().addAll(lbl, sp, refresh);

        HBox headers = new HBox(0); headers.setPadding(new Insets(4, 8, 4, 8));
        headers.setStyle("-fx-background-color:" + theme.panel() + ";-fx-border-color:" + theme.border() +
                ";-fx-border-radius:5 5 0 0;-fx-background-radius:5 5 0 0;");
        headers.getChildren().addAll(
            colHdr("Filename",220), colHdr("Owner",75), colHdr("Size",60),
            colHdr("Access",60), colHdr("Expires",70), colHdr("Uploaded",135),
            colHdr("Last DL",135), colHdr("",62)
        );

        filesListBox = new VBox(0); filesListBox.setFillWidth(true);
        ScrollPane scroll = makeScroll(filesListBox, 200);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        scroll.setStyle(scroll.getStyle() + "-fx-border-radius:0 0 5 5;");

        Label ph = new Label("Connect to server, then click Refresh.");
        ph.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-padding:12 8;");
        filesListBox.getChildren().add(ph);

        card.getChildren().addAll(toolbar, headers, scroll);
        return card;
    }

    // ── Users tab ─────────────────────────────────────────────────────────────

    private VBox buildUsersCard() {
        VBox card = new VBox(10);
        VBox.setVgrow(card, Priority.ALWAYS);

        HBox toolbar = new HBox(8); toolbar.setAlignment(Pos.CENTER_RIGHT);
        Button refresh = greenRefreshBtn("Refresh"); refresh.setOnAction(e -> refreshUsers());
        toolbar.getChildren().add(refresh);

        HBox createRow = new HBox(8); createRow.setAlignment(Pos.CENTER_LEFT);
        TextField  newUser = styledTF("", 150); newUser.setPromptText("username");
        PasswordField newPw = new PasswordField(); newPw.setPromptText("password");
        newPw.setPrefWidth(140);
        newPw.setStyle("-fx-background-color:" + theme.input() + ";-fx-text-fill:" + theme.text() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:5;-fx-background-radius:5;-fx-padding:5 8;");
        Button createBtn = new Button("Create User"); applyToggleStyle(createBtn, false);
        createBtn.setStyle(createBtn.getStyle()
            .replace("-fx-padding:8 24","-fx-padding:5 14")
            .replace("-fx-font-size:13px","-fx-font-size:12px"));
        Label createMsg = new Label(""); createMsg.setStyle("-fx-font-size:11px;");
        createBtn.setOnAction(e -> createUser(newUser.getText().trim(),
                newPw.getText(), createMsg, newUser, newPw));
        createRow.getChildren().addAll(fieldLabel("New User",72), newUser,
                fieldLabel("Password",65), newPw, createBtn);

        usersListBox = new VBox(0); usersListBox.setFillWidth(true);
        ScrollPane scroll = makeScroll(usersListBox, 400);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label ph = new Label("Connect to server to manage users.");
        ph.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-padding:8 4;");
        usersListBox.getChildren().add(ph);

        card.getChildren().addAll(toolbar, createRow, createMsg, scroll);
        return card;
    }

    // ── Archive tab ───────────────────────────────────────────────────────────

    private VBox buildArchiveCard() {
        VBox card = new VBox(10);
        VBox.setVgrow(card, Priority.ALWAYS);
        HBox toolbar = new HBox(8); toolbar.setAlignment(Pos.CENTER_RIGHT);
        Button refresh = greenRefreshBtn("Refresh"); refresh.setOnAction(e -> refreshArchive());
        toolbar.getChildren().add(refresh);
        Label info = new Label("Files moved here after expiry. Only admins can delete them.");
        info.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;"); info.setWrapText(true);
        archiveListBox = new VBox(0); archiveListBox.setFillWidth(true);
        ScrollPane scroll = makeScroll(archiveListBox, 150);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        Label ph = new Label("No archived files.");
        ph.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-padding:8 4;");
        archiveListBox.getChildren().add(ph);
        card.getChildren().addAll(toolbar, info, scroll);
        return card;
    }

    // ── Log tab ───────────────────────────────────────────────────────────────

    private VBox buildLogCard() {
        VBox card = new VBox(8); VBox.setVgrow(card, Priority.ALWAYS);
        HBox toolbar = new HBox(8); toolbar.setAlignment(Pos.CENTER_RIGHT);
        Button clear = new Button("Clear"); clear.setOnAction(e -> logArea.clear());
        String clearBase = "-fx-background-color:#66ff66;-fx-text-fill:#1a1d27;-fx-border-color:" +
                theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:5 12;-fx-font-weight:bold;";
        clear.setStyle(clearBase);
        clear.setOnMouseEntered(e -> clear.setStyle(clearBase.replace("#66ff66","#80ff80")));
        clear.setOnMouseExited(e  -> clear.setStyle(clearBase));
        toolbar.getChildren().add(clear);
        logArea = new TextArea();
        logArea.setEditable(false); logArea.setWrapText(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        applyLogAreaStyle();
        card.getChildren().addAll(toolbar, logArea);
        return card;
    }

    // ── Connect / Disconnect ──────────────────────────────────────────────────

    private void connectToServer() {
        String host = hostField.getText().trim();
        String portStr = mgmtPortField.getText().trim();
        int port;
        try { port = Integer.parseInt(portStr); }
        catch (NumberFormatException e) { showAlert("Invalid management port."); return; }

        guiConfig.setHost(host);
        guiConfig.setMgmtPort(portStr);
        guiConfig.save();

        connectBtn.setDisable(true);
        connectBtn.setText("Connecting…");

        new Thread(() -> {
            try {
                ManagementClient c = new ManagementClient(host, port);
                c.setLogListener(msg -> Platform.runLater(() -> appendLog(msg)));
                c.setStateListener(connected -> Platform.runLater(() -> {
                    if (!connected) onDisconnected();
                }));
                c.connect();

                // Login
                JsonObject resp = c.send(Map.of("cmd","login",
                        "password", promptPassword()));
                if (!resp.has("ok") || !resp.get("ok").getAsBoolean()) {
                    c.close();
                    Platform.runLater(() -> {
                        showAlert("Authentication failed: " +
                                (resp.has("error") ? resp.get("error").getAsString() : ""));
                        onDisconnected();
                    });
                    return;
                }
                client = c;
                loggedIn = true;

                // Load initial config + data
                JsonObject cfg = c.cmd("get_config");
                String fullLog = "";
                try {
                    JsonObject logResp = c.cmd("get_log");
                    if (logResp.has("log")) fullLog = logResp.get("log").getAsString();
                } catch (Exception ignored) {
                    // Older server without get_log support — skip silently
                }
                final String fullLogFinal = fullLog;
                Platform.runLater(() -> {
                    onConnected(host, port);
                    populateConfigFields(cfg);
                    refreshFiles();
                    refreshUsers();
                    refreshArchive();
                    loadFullLog(fullLogFinal);
                    appendLog("Connected to " + host + ":" + port);
                    refreshDiskUsage();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showAlert("Connection failed: " + e.getMessage());
                    onDisconnected();
                });
            }
        }, "gui-connect").start();
    }

    /** Shows a password dialog and returns the entered string. Called off FX thread via invokeAndWait. */
    private String promptPassword() throws Exception {
        String[] result = {""};
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        Platform.runLater(() -> {
            Stage dlg = dialogStage("Server Authentication");
            VBox box = dialogBox();
            PasswordField pw = dialogPwField("Server admin password");
            Button btn = primaryBtn("Authenticate");
            Label err = errorLabel();
            btn.setOnAction(e -> { result[0] = pw.getText(); dlg.close(); latch.countDown(); });
            pw.setOnAction(e -> btn.fire());
            box.getChildren().addAll(dialogTitle("Authenticate"), pw, err, btn);
            box.setPrefWidth(320);
            dlg.setScene(new Scene(box)); dlg.sizeToScene();
            dlg.setOnCloseRequest(e -> latch.countDown());
            dlg.showAndWait();
        });
        latch.await();
        return result[0];
    }

    private void disconnectFromServer() {
        if (client != null) { client.close(); client = null; }
        loggedIn = false;
        onDisconnected();
    }

    private void onConnected(String host, int port) {
        statusLabel.setText("CONNECTED");
        statusLabel.setStyle("-fx-text-fill:" + GREEN + ";-fx-font-weight:bold;-fx-font-size:13px;");
        statusDot.setFill(Color.web(GREEN));
        serverUrlLabel.setText(host + ":" + port);
        connectBtn.setText("Disconnect");
        applyToggleStyle(connectBtn, true);
        connectBtn.setDisable(false);
    }

    private void onDisconnected() {
        loggedIn = false; client = null;
        statusLabel.setText("DISCONNECTED");
        statusLabel.setStyle("-fx-text-fill:" + DANGER + ";-fx-font-weight:bold;-fx-font-size:13px;");
        statusDot.setFill(Color.web(DANGER));
        serverUrlLabel.setText("");
        connectBtn.setText("Connect");
        applyToggleStyle(connectBtn, false);
        connectBtn.setDisable(false);

        if (diskUsageBar != null) {
            diskUsageBar.setProgress(0);
            diskUsageBar.setStyle("");
            diskUsageLabel.setText("Not connected");
            diskUsageDetailLabel.setText("");
        }
    }

    // ── Data refresh methods ──────────────────────────────────────────────────

    private void refreshFiles() {
        if (!loggedIn) return;
        new Thread(() -> {
            try {
                JsonObject resp = client.cmd("list_files");
                JsonArray files = resp.has("files") ? resp.getAsJsonArray("files") : new JsonArray();
                Platform.runLater(() -> {
                    filesListBox.getChildren().clear();
                    updateLabel("filesCountLabel", files.size() + " file(s)");
                    if (files.size() == 0) {
                        filesListBox.getChildren().add(placeholder("No files uploaded yet."));
                        return;
                    }
                    DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                            .withZone(java.time.ZoneId.systemDefault());
                    for (int i = 0; i < files.size(); i++) {
                        JsonObject f = files.get(i).getAsJsonObject();
                        filesListBox.getChildren().add(buildFileRow(f, i, dtFmt));
                    }
                });
            } catch (Exception e) { appendLog("ERROR refreshing files: " + e.getMessage()); }
        }, "refresh-files").start();
    }

    private HBox buildFileRow(JsonObject f, int i, DateTimeFormatter dtFmt) {
        int    id             = f.get("id").getAsInt();
        String filename       = f.get("filename").getAsString();
        long   size           = f.get("size").getAsLong();
        String owner          = f.get("owner").getAsString();
        boolean pub           = f.get("isPublic").getAsBoolean();
        long   uploaded       = f.get("uploaded").getAsLong();
        long   expires        = f.get("expires").getAsLong();
        long   lastDownloaded = f.has("lastDownloaded") ? f.get("lastDownloaded").getAsLong() : 0;
        String message        = f.has("message") ? f.get("message").getAsString() : "";

        HBox row = new HBox(0); row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5,10,5,10));
        row.setStyle("-fx-background-color:" + (i%2==0 ? theme.panel() : theme.bg()) + ";");

        Label fnLbl = rowCell(filename, message.isEmpty() ? 220 : 196);
        fnLbl.setStyle(fnLbl.getStyle() + "-fx-font-weight:600;");
        HBox fnBox = new HBox(4); fnBox.setAlignment(Pos.CENTER_LEFT);
        fnBox.setPrefWidth(220); fnBox.setMinWidth(220);
        fnBox.getChildren().add(fnLbl);
        if (!message.isEmpty()) {
            Label msgIcon = new Label("💬");
            msgIcon.setStyle("-fx-font-size:11px;-fx-cursor:hand;");
            Tooltip tip = new Tooltip(message);
            tip.setShowDuration(Duration.seconds(30));
            tip.setWrapText(true);
            tip.setMaxWidth(300);
            Tooltip.install(msgIcon, tip);
            fnBox.getChildren().add(msgIcon);
        }
        Label ownerLbl = rowCell(owner, 75);
        Label sizeLbl  = rowCell(fmtBytes(size), 60);

        Label accessLbl = new Label(pub ? "Public" : "Private");
        accessLbl.setPrefWidth(60); accessLbl.setMinWidth(60);
        accessLbl.setStyle("-fx-text-fill:" + (pub ? GREEN : theme.muted()) +
                ";-fx-font-size:11px;-fx-font-weight:600;");

        String expTxt; String expClr;
        if (expires == 0) { expTxt="Never"; expClr=ACCENT; }
        else {
            long diff = expires - System.currentTimeMillis()/1000;
            if (diff<=0) { expTxt="Expired"; expClr=DANGER; }
            else { long d=diff/86400; expTxt=d==0?"<1d":d+"d"; expClr=d<=3?DANGER:GREEN; }
        }
        Label expLbl = new Label(expTxt);
        expLbl.setPrefWidth(70); expLbl.setMinWidth(70);
        expLbl.setStyle("-fx-text-fill:" + expClr + ";-fx-font-size:11px;-fx-font-weight:600;");

        // Uploaded
        String upStr = java.time.Instant.ofEpochSecond(uploaded)
                .atZone(java.time.ZoneId.systemDefault()).format(dtFmt);
        Label upLbl = rowCell(upStr, 135);

        // Last downloaded
        String dlStr = lastDownloaded == 0 ? "—"
                : java.time.Instant.ofEpochSecond(lastDownloaded)
                    .atZone(java.time.ZoneId.systemDefault()).format(dtFmt);
        Label dlLbl = rowCell(dlStr, 135);
        if (lastDownloaded == 0)
            dlLbl.setStyle(dlLbl.getStyle() + "-fx-text-fill:" + theme.muted() + ";");

        // Action buttons — single letter with tooltips
        HBox actions = new HBox(3); actions.setPrefWidth(62); actions.setMinWidth(62);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button accessBtn = new Button("A");
        styleSmallBtn(accessBtn, "#5a7a6f", "#6a9a8f");
        accessBtn.setTooltip(new Tooltip("Manage access — who can download this file"));
        accessBtn.setOnAction(e -> showAccessDialog(id, filename));

        Button expiryBtn = new Button("E");
        styleSmallBtn(expiryBtn, ACCENT, ACCENTH);
        expiryBtn.setTooltip(new Tooltip("Set expiry — change or clear the expiry date"));
        expiryBtn.setOnAction(e -> showExpiryDialog(id, filename, expires));

        Button delBtn = new Button("D");
        styleSmallBtn(delBtn, DANGER, DANGERH);
        delBtn.setTooltip(new Tooltip("Delete — permanently remove this file from disk"));
        delBtn.setOnAction(e -> deleteFile(id, filename));

        actions.getChildren().addAll(accessBtn, expiryBtn, delBtn);

        row.getChildren().addAll(fnBox, ownerLbl, sizeLbl, accessLbl,
                expLbl, upLbl, dlLbl, actions);
        return row;
    }

    private void refreshUsers() {
        if (!loggedIn) return;
        new Thread(() -> {
            try {
                JsonObject resp = client.cmd("list_users");
                JsonArray users = resp.has("users") ? resp.getAsJsonArray("users") : new JsonArray();
                Platform.runLater(() -> {
                    usersListBox.getChildren().clear();
                    if (users.size() == 0) {
                        usersListBox.getChildren().add(placeholder("No users registered."));
                        return;
                    }
                    for (int i = 0; i < users.size(); i++) {
                        String u = users.get(i).getAsString();
                        usersListBox.getChildren().add(buildUserRow(u, i));
                    }
                });
            } catch (Exception e) { appendLog("ERROR refreshing users: " + e.getMessage()); }
        }, "refresh-users").start();
    }

    private HBox buildUserRow(String username, int i) {
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7,10,7,10));
        row.setStyle("-fx-background-color:" + (i%2==0?theme.panel():theme.bg()) + ";");
        Label lbl = new Label(username);
        lbl.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-size:12px;-fx-font-weight:600;");
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button resetBtn = new Button("Reset Password"); styleSmallBtn(resetBtn, ACCENT, ACCENTH);
        resetBtn.setOnAction(e -> showResetPasswordDialog(username));
        Button delBtn = new Button("Delete"); styleSmallBtn(delBtn, DANGER, DANGERH);
        delBtn.setOnAction(e -> deleteUser(username));
        row.getChildren().addAll(lbl, sp, resetBtn, delBtn);
        return row;
    }

    private void refreshArchive() {
        if (!loggedIn) return;
        new Thread(() -> {
            try {
                JsonObject resp = client.cmd("list_archive");
                JsonArray items = resp.has("archive") ? resp.getAsJsonArray("archive") : new JsonArray();
                Platform.runLater(() -> {
                    archiveListBox.getChildren().clear();
                    if (items.size() == 0) {
                        archiveListBox.getChildren().add(placeholder("No archived files."));
                        return;
                    }
                    DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                            .withZone(java.time.ZoneId.systemDefault());
                    for (int i = 0; i < items.size(); i++) {
                        JsonObject r = items.get(i).getAsJsonObject();
                        archiveListBox.getChildren().add(buildArchiveRow(r, i, fmt));
                    }
                });
            } catch (Exception e) { appendLog("ERROR refreshing archive: " + e.getMessage()); }
        }, "refresh-archive").start();
    }

    /**
     * Fetches disk usage info for the volume hosting the server's upload
     * directory and updates the progress bar + labels in the Server tab.
     */
    private void refreshDiskUsage() {
        if (!loggedIn) return;
        new Thread(() -> {
            try {
                JsonObject resp = client.cmd("get_disk_usage");
                long total     = resp.get("totalBytes").getAsLong();
                long free      = resp.get("freeBytes").getAsLong();
                long used      = resp.get("usedBytes").getAsLong();
                long shareWave = resp.get("shareWaveBytes").getAsLong();
                String dir     = resp.get("uploadDir").getAsString();

                double frac = total > 0 ? (double) used / total : 0;

                Platform.runLater(() -> {
                    diskUsageBar.setProgress(frac);
                    // Colour the bar green/orange/red based on usage level
                    String barColor = frac >= 0.9 ? DANGER : frac >= 0.75 ? "#e0a85c" : GREEN;
                    diskUsageBar.setStyle("-fx-accent:" + barColor + ";");

                    diskUsageLabel.setText(String.format("%s used of %s (%.1f%%)  \u2014  %s free",
                            fmtBytes(used), fmtBytes(total), frac * 100, fmtBytes(free)));
                    diskUsageDetailLabel.setText(
                            "ShareWave data (uploads + archive): " + fmtBytes(shareWave) + "  \u2014  " + dir);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    diskUsageLabel.setText("Unable to retrieve disk usage");
                    diskUsageDetailLabel.setText(e.getMessage());
                });
                appendLog("ERROR refreshing disk usage: " + e.getMessage());
            }
        }, "refresh-disk-usage").start();
    }

    private HBox buildArchiveRow(JsonObject r, int i, DateTimeFormatter fmt) {
        int archId = r.get("id").getAsInt();
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6,10,6,10));
        row.setStyle("-fx-background-color:" + (i%2==0?theme.panel():theme.bg()) + ";");
        VBox info = new VBox(2);
        Label nm = new Label(r.get("filename").getAsString());
        nm.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-size:12px;-fx-font-weight:600;");
        String exp = java.time.Instant.ofEpochSecond(r.get("expiredAt").getAsLong())
                .atZone(java.time.ZoneId.systemDefault()).format(fmt);
        Label meta = new Label(fmtBytes(r.get("size").getAsLong()) +
                "  •  owner: " + r.get("owner").getAsString() + "  •  expired: " + exp);
        meta.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:10px;");
        info.getChildren().addAll(nm, meta);
        Region sp = new Region(); HBox.setHgrow(sp, Priority.ALWAYS);
        Button delBtn = new Button("Delete"); styleSmallBtn(delBtn, DANGER, DANGERH);
        delBtn.setOnAction(e -> deleteArchive(archId, r.get("filename").getAsString()));
        row.getChildren().addAll(info, sp, delBtn);
        return row;
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void createUser(String username, String password, Label msg,
                            TextField userField, PasswordField pwField) {
        if (username.isEmpty() || password.isEmpty()) {
            msg.setText("Fill in both fields."); msg.setStyle("-fx-font-size:11px;-fx-text-fill:"+DANGER+";"); return; }
        if (password.length() < 4) {
            msg.setText("Password >= 4 chars."); msg.setStyle("-fx-font-size:11px;-fx-text-fill:"+DANGER+";"); return; }
        if (!loggedIn) { msg.setText("Not connected."); return; }
        new Thread(() -> {
            try {
                client.cmd("create_user","username",username,"password",password);
                Platform.runLater(() -> {
                    msg.setText("User '" + username + "' created.");
                    msg.setStyle("-fx-font-size:11px;-fx-text-fill:"+GREEN+";");
                    userField.clear(); pwField.clear();
                    refreshUsers();
                });
            } catch (Exception e) {
                Platform.runLater(() -> { msg.setText(e.getMessage());
                    msg.setStyle("-fx-font-size:11px;-fx-text-fill:"+DANGER+";"); });
            }
        }, "create-user").start();
    }

    private void deleteUser(String username) {
        if (!confirm("Delete user '" + username + "'?")) return;
        new Thread(() -> {
            try { client.cmd("delete_user","username",username);
                Platform.runLater(this::refreshUsers); }
            catch (Exception e) { appendLog("ERROR: " + e.getMessage()); }
        }, "delete-user").start();
    }

    private void showResetPasswordDialog(String username) {
        Stage dlg = dialogStage("Reset Password — " + username);
        VBox box = dialogBox();
        PasswordField pw1 = dialogPwField("New password");
        PasswordField pw2 = dialogPwField("Confirm password");
        Label err = errorLabel();
        HBox btnRow = new HBox(8); btnRow.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = ghostBtn("Cancel"); cancel.setOnAction(e -> dlg.close());
        Button save = primaryBtn("Reset"); save.setMaxWidth(100);
        save.setOnAction(e -> {
            if (pw1.getText().length() < 4) { err.setText("Min 4 chars."); return; }
            if (!pw1.getText().equals(pw2.getText())) { err.setText("Passwords do not match."); return; }
            new Thread(() -> {
                try { client.cmd("reset_password","username",username,"password",pw1.getText());
                    Platform.runLater(dlg::close); }
                catch (Exception ex) { Platform.runLater(() -> err.setText(ex.getMessage())); }
            },"reset-pw").start();
        });
        pw2.setOnAction(e -> save.fire());
        btnRow.getChildren().addAll(cancel, save);
        box.getChildren().addAll(dialogTitle("Reset: " + username), pw1, pw2, err, btnRow);
        box.setPrefWidth(320);
        dlg.setScene(new Scene(box)); dlg.sizeToScene(); dlg.showAndWait();
    }

    private void deleteFile(int fileId, String filename) {
        if (!confirm("Permanently delete \"" + filename + "\"?")) return;
        new Thread(() -> {
            try { client.cmd("delete_file","fileId",fileId);
                Platform.runLater(() -> { refreshFiles(); refreshDiskUsage(); }); }
            catch (Exception e) { appendLog("ERROR: " + e.getMessage()); }
        },"delete-file").start();
    }

    private void showAccessDialog(int fileId, String filename) {
        if (!loggedIn) return;

        // Fetch current access settings from server
        new Thread(() -> {
            try {
                JsonObject resp = client.cmd("get_file_access", "fileId", fileId);
                boolean isPublic  = resp.has("isPublic") && resp.get("isPublic").getAsBoolean();
                List<String> currentUsers = new ArrayList<>();
                if (resp.has("users"))
                    resp.getAsJsonArray("users").forEach(e -> currentUsers.add(e.getAsString()));
                List<String> allUsers = new ArrayList<>();
                if (resp.has("allUsers"))
                    resp.getAsJsonArray("allUsers").forEach(e -> allUsers.add(e.getAsString()));
                String message = resp.has("message") ? resp.get("message").getAsString() : "";

                Platform.runLater(() -> buildAccessDialog(fileId, filename,
                        isPublic, currentUsers, allUsers, message));
            } catch (Exception e) {
                appendLog("ERROR fetching access: " + e.getMessage());
            }
        }, "get-access").start();
    }

    private void buildAccessDialog(int fileId, String filename, boolean isPublic,
                                    List<String> currentUsers, List<String> allUsers, String message) {
        Stage dlg = dialogStage("Manage Access — " + filename);
        dlg.setResizable(true);

        VBox box = new VBox(12);
        box.setPadding(new Insets(20));
        box.setStyle("-fx-background-color:" + theme.panel() + ";");
        box.setPrefWidth(400);

        Label title = dialogTitle(filename);
        title.setWrapText(true);

        // ── Access mode ─────────────────────────────────────────────────────
        Label modeLabel = new Label("Who can download?");
        modeLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-text-transform:uppercase;");

        ToggleGroup modeGroup = new ToggleGroup();
        RadioButton rbPublic   = styledRadio("Public — any logged-in user", modeGroup);
        RadioButton rbSpecific = styledRadio("Specific users", modeGroup);

        // Determine initial mode
        if (isPublic) rbPublic.setSelected(true);
        else          rbSpecific.setSelected(true);

        // ── Specific users section ───────────────────────────────────────────
        VBox specificBox = new VBox(8);
        specificBox.setStyle("-fx-padding:4 0 0 0;");

        // Current access chips
        Label chipsLabel = new Label("Currently allowed:");
        chipsLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");

        FlowPane chips = new FlowPane(6, 6);
        List<String> draft = new ArrayList<>(currentUsers);
        Runnable[] renderChipsRef = {null};
        renderChipsRef[0] = () -> {
            chips.getChildren().clear();
            if (draft.isEmpty()) {
                Label none = new Label("(none)");
                none.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
                chips.getChildren().add(none);
            } else {
                for (String u : new ArrayList<>(draft)) {
                    HBox chip = new HBox(4);
                    chip.setAlignment(Pos.CENTER_LEFT);
                    chip.setStyle("-fx-background-color:" + theme.bg() + ";-fx-background-radius:99;" +
                            "-fx-padding:3 8 3 10;-fx-border-color:" + theme.border() + ";-fx-border-radius:99;");
                    Label uLbl = new Label(u);
                    uLbl.setStyle("-fx-text-fill:" + theme.logText() + ";-fx-font-size:11px;");
                    Button rm = new Button("×");
                    rm.setStyle("-fx-background-color:transparent;-fx-text-fill:" + theme.muted() + ";" +
                            "-fx-cursor:hand;-fx-padding:0;-fx-font-size:11px;");
                    rm.setOnMouseEntered(e -> rm.setStyle(rm.getStyle().replace(theme.muted(),"#e05c6e")));
                    rm.setOnMouseExited(e  -> rm.setStyle(rm.getStyle().replace("#e05c6e",theme.muted())));
                    rm.setOnAction(e -> { draft.remove(u); renderChipsRef[0].run(); });
                    chip.getChildren().addAll(uLbl, rm);
                    chips.getChildren().add(chip);
                }
            }
        };
        renderChipsRef[0].run();

        // Add user dropdown
        Label addLabel = new Label("Add a user:");
        addLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");

        ComboBox<String> addCombo = themedCombo(new String[0]);
        addCombo.setMaxWidth(Double.MAX_VALUE);
        addCombo.setPrefWidth(220);
        HBox.setHgrow(addCombo, Priority.ALWAYS);

        Runnable refreshCombo = () -> {
            List<String> available = allUsers.stream()
                    .filter(u -> !draft.contains(u))
                    .toList();
            addCombo.getItems().setAll(available);
            if (!available.isEmpty()) addCombo.setValue(available.get(0));
            else addCombo.setValue(null);
        };
        refreshCombo.run();

        Button addBtn = new Button("Add");
        styleSmallBtn(addBtn, ACCENT, ACCENTH);
        addBtn.setOnAction(e -> {
            String sel = addCombo.getValue();
            if (sel != null && !draft.contains(sel)) {
                draft.add(sel);
                renderChipsRef[0].run();
                refreshCombo.run();
            }
        });

        HBox addRow = new HBox(8);
        addRow.setAlignment(Pos.CENTER_LEFT);
        addRow.getChildren().addAll(addCombo, addBtn);

        specificBox.getChildren().addAll(chipsLabel, chips, addLabel, addRow);

        // Show/hide specificBox based on mode
        specificBox.setVisible(rbSpecific.isSelected());
        specificBox.setManaged(rbSpecific.isSelected());
        rbPublic.setOnAction(e   -> { specificBox.setVisible(false); specificBox.setManaged(false); });
        rbSpecific.setOnAction(e -> { specificBox.setVisible(true);  specificBox.setManaged(true);  });

        // ── Note for downloaders ─────────────────────────────────────────────
        Label msgLabel = new Label("Note for downloaders (optional)");
        msgLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-text-transform:uppercase;");
        TextArea messageField = new TextArea(message == null ? "" : message);
        messageField.setWrapText(true);
        messageField.setPrefRowCount(3);
        messageField.setPrefWidth(360);
        messageField.setStyle("-fx-control-inner-background:" + theme.input() + ";-fx-text-fill:" + theme.text() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:5;-fx-background-radius:5;-fx-font-size:12px;");

        // ── Buttons ─────────────────────────────────────────────────────────
        Label err = errorLabel();
        HBox btnRow = new HBox(8); btnRow.setAlignment(Pos.CENTER_RIGHT);
        Button cancelBtn = ghostBtn("Cancel"); cancelBtn.setOnAction(e -> dlg.close());
        Button saveBtn   = primaryBtn("Save"); saveBtn.setMaxWidth(100);

        saveBtn.setOnAction(e -> {
            boolean pub  = rbPublic.isSelected();
            List<String> users;
            if (pub) users = List.of();
            else     users = new ArrayList<>(draft);
            String msgText = messageField.getText() == null ? "" : messageField.getText().trim();

            new Thread(() -> {
                try {
                    client.cmd("set_file_access",
                            "fileId",   fileId,
                            "isPublic", pub,
                            "users",    users,
                            "message",  msgText);
                    Platform.runLater(() -> { dlg.close(); refreshFiles(); });
                } catch (Exception ex) {
                    Platform.runLater(() -> err.setText(ex.getMessage()));
                }
            }, "set-access").start();
        });

        btnRow.getChildren().addAll(cancelBtn, saveBtn);

        box.getChildren().addAll(title, modeLabel, rbPublic, rbSpecific,
                specificBox, msgLabel, messageField, err, btnRow);

        ScrollPane sp = new ScrollPane(box);
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color:" + theme.panel() + ";-fx-background:transparent;");
        sp.skinProperty().addListener((obs, o, n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = sp.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:" + theme.panel() + ";");
        }));
        // Cap how tall the ScrollPane can grow so a long user list scrolls
        // instead of the window growing unbounded; sizeToScene() below then
        // sizes the window to fit (up to this cap) before it's ever shown,
        // so there's no zero/placeholder-height window at any point.
        sp.setMaxHeight(420);
        box.setPrefWidth(420);

        Scene dlgScene = new Scene(sp);
        dlgScene.setFill(Color.web(theme.panel()));
        dlg.setScene(dlgScene);
        dlg.sizeToScene();
        dlg.showAndWait();
    }

    private RadioButton styledRadio(String text, ToggleGroup group) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-size:12px;");
        return rb;
    }

    private void showExpiryDialog(int fileId, String filename, long currentExpires) {
        Stage dlg = dialogStage("Set Expiry — " + filename);
        VBox box = dialogBox();
        Label title = dialogTitle(filename);
        title.setWrapText(true);
        String curStr = currentExpires == 0 ? "Never expires"
                : "Expires in " + Math.max(0,(currentExpires-System.currentTimeMillis()/1000)/86400) + " day(s)";
        Label curLbl = new Label(curStr); curLbl.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        ComboBox<String> combo = themedCombo(new String[]{
            "Never expires","1 day","3 days","7 days","14 days","30 days","90 days","1 year"
        });
        int[] dayMap = {0,1,3,7,14,30,90,365};
        combo.setValue("Never expires");
        Label err = errorLabel();
        HBox btnRow = new HBox(8); btnRow.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = ghostBtn("Cancel"); cancel.setOnAction(e -> dlg.close());
        Button save = primaryBtn("Save"); save.setMaxWidth(100);
        save.setOnAction(e -> {
            int idx = combo.getSelectionModel().getSelectedIndex();
            int days = (idx>=0&&idx<dayMap.length)?dayMap[idx]:0;
            long exp = days==0 ? 0 : System.currentTimeMillis()/1000 + (long)days*86400;
            new Thread(() -> {
                try { client.cmd("set_file_expiry","fileId",fileId,"expires",exp);
                    Platform.runLater(() -> { dlg.close(); refreshFiles(); }); }
                catch (Exception ex) { Platform.runLater(() -> err.setText(ex.getMessage())); }
            },"set-expiry").start();
        });
        btnRow.getChildren().addAll(cancel, save);
        box.getChildren().addAll(title, curLbl, combo, err, btnRow);
        box.setPrefWidth(320);
        dlg.setScene(new Scene(box)); dlg.sizeToScene(); dlg.showAndWait();
    }

    private void deleteArchive(int archiveId, String filename) {
        if (!confirm("Permanently delete archived file \"" + filename + "\"?")) return;
        new Thread(() -> {
            try { client.cmd("delete_archive","archiveId",archiveId);
                Platform.runLater(() -> { refreshArchive(); refreshDiskUsage(); }); }
            catch (Exception e) { appendLog("ERROR: " + e.getMessage()); }
        },"delete-archive").start();
    }

    private void saveServerConfig() {
        if (!loggedIn) { showAlert("Not connected."); return; }
        new Thread(() -> {
            try {
                client.cmd("set_config",
                    "webPort",     webPortField.getText().trim(),
                    "mgmtPort",    srvMgmtPortField.getText().trim(),
                    "uploadDir",   uploadDirField.getText().trim(),
                    "dbPath",      dbPathField.getText().trim(),
                    "keystore",    keystoreField.getText().trim(),
                    "siteTitle",   siteTitleField.getText().trim(),
                    "sessionTimeoutMinutes", sessionTimeoutField.getText().trim()
                );
                appendLog("Server config saved. Site title/version take effect immediately; " +
                        "restart server for other changes to take effect.");
                Platform.runLater(() -> showSaveConfigStatus(
                        "\u2713 Saved \u2014 restart server for most changes to take effect", GREEN));
            } catch (Exception e) {
                appendLog("ERROR saving config: " + e.getMessage());
                Platform.runLater(() -> showSaveConfigStatus("\u2717 " + e.getMessage(), DANGER));
            }
        },"save-config").start();
    }

    /** Shows a transient status message next to the Save Config button. */
    private void showSaveConfigStatus(String text, String color) {
        if (saveConfigStatus == null) return;
        saveConfigStatus.setText(text);
        saveConfigStatus.setStyle("-fx-font-size:11px;-fx-text-fill:" + color + ";");
        PauseTransition pt = new PauseTransition(Duration.seconds(5));
        pt.setOnFinished(e -> saveConfigStatus.setText(""));
        pt.play();
    }

    private void populateConfigFields(JsonObject cfg) {
        webPortField.setText(cfg.has("webPort")   ? cfg.get("webPort").getAsString()   : "");
        uploadDirField.setText(cfg.has("uploadDir")? cfg.get("uploadDir").getAsString() : "");
        dbPathField.setText(cfg.has("dbPath")     ? cfg.get("dbPath").getAsString()    : "");
        keystoreField.setText(cfg.has("keystore") ? cfg.get("keystore").getAsString()  : "");
        srvMgmtPortField.setText(cfg.has("mgmtPort")? cfg.get("mgmtPort").getAsString(): "");
        siteTitleField.setText(cfg.has("siteTitle")? cfg.get("siteTitle").getAsString(): "");
        sessionTimeoutField.setText(cfg.has("sessionTimeoutMinutes")? cfg.get("sessionTimeoutMinutes").getAsString(): "5");
    }

    private void changeAdminPassword() {
        Stage dlg = dialogStage("Change Admin Password");
        VBox box = dialogBox();
        PasswordField cur = dialogPwField("Current password");
        PasswordField pw1 = dialogPwField("New password");
        PasswordField pw2 = dialogPwField("Confirm new password");
        Label err = errorLabel();
        Button btn = primaryBtn("Change");
        btn.setOnAction(e -> {
            if (!adminConfig.checkPassword(cur.getText())) { err.setText("Current password incorrect."); return; }
            if (pw1.getText().length()<4) { err.setText("Min 4 chars."); return; }
            if (!pw1.getText().equals(pw2.getText())) { err.setText("Passwords do not match."); return; }
            adminConfig.setPassword(pw1.getText());
            dlg.close();
        });
        pw2.setOnAction(e -> btn.fire());
        box.getChildren().addAll(dialogTitle("Change Admin Password"), cur, pw1, pw2, err, btn);
        box.setPrefWidth(320);
        dlg.setScene(new Scene(box)); dlg.sizeToScene(); dlg.showAndWait();
    }

    // ── Log ───────────────────────────────────────────────────────────────────

    private void appendLog(String msg) {
        Platform.runLater(() -> {
            if (logArea == null) return;
            String ts = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + ts + "] " + msg + "\n");
            scrollLogToEnd();
        });
    }

    /**
     * Loads the complete server-side log file into the log area (replacing
     * any placeholder content) and scrolls to the end so the most recent
     * entries are visible. Called once after connecting; subsequent entries
     * arrive via the live log stream through appendLog().
     */
    private void loadFullLog(String fullLog) {
        if (logArea == null) return;
        logArea.clear();
        if (fullLog != null && !fullLog.isEmpty()) {
            // Ensure the loaded content ends with a newline before any
            // subsequent live-streamed lines are appended.
            logArea.appendText(fullLog.endsWith("\n") ? fullLog : fullLog + "\n");
        }
        scrollLogToEnd();
    }

    /** Scrolls the log TextArea to show the most recently added line. */
    private void scrollLogToEnd() {
        if (logArea == null) return;
        logArea.positionCaret(logArea.getText().length());
        logArea.setScrollTop(Double.MAX_VALUE);
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private void toggleTheme() {
        isDark = !isDark; theme = isDark ? Theme.DARK : Theme.LIGHT;
        themeBtn.setText(isDark ? "☀ Light" : "🌙 Dark");
        guiConfig.setTheme(isDark ? "dark" : "light"); guiConfig.save();
        applyTheme();
    }

    private void applyTheme() {
        if (scene == null) return;
        scene.setFill(Color.web(theme.bg()));
        root.setStyle("-fx-background-color:" + theme.bg() + ";");

        // Inject CSS for TabPane header strip — inline setStyle() cannot reach it
        applyTabPaneCss();

        String cs = cardStyle();
        for (VBox c : new VBox[]{serverCard, filesCard, usersCard, archiveCard, logCard})
            if (c != null) c.setStyle(cs);
        applyLogAreaStyle();

        // Re-apply text field styles so they update on theme switch
        applyTextFieldStyles();
        if (titleHelp != null) titleHelp.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:10px;");
        if (sessionTimeoutUnit != null) sessionTimeoutUnit.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        if (statusSep != null) statusSep.setStyle("-fx-text-fill:" + theme.border() + ";-fx-font-size:16px;");

        // Re-style the first-run help note
        if (firstRunNote != null) {
            firstRunNote.setStyle(
                "-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;" +
                "-fx-font-family:'Consolas','Courier New',monospace;" +
                "-fx-padding:6 10;-fx-background-color:" + theme.bg() + ";" +
                "-fx-background-radius:6;-fx-border-color:" + theme.border() +
                ";-fx-border-radius:6;"
            );
        }

        // Re-style disk usage section
        if (diskUsageLabel != null) {
            diskUsageLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
            diskUsageDetailLabel.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
            diskRefreshBtn.setStyle(
                "-fx-background-color:#66ff66;-fx-text-fill:#1a1d27;-fx-border-color:" +
                theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:3 10;-fx-font-weight:bold;-fx-font-size:11px;"
            );
        }

        String tbBase = "-fx-background-color:" + theme.panel() + ";-fx-text-fill:" + theme.muted() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:4 10;-fx-font-size:11px;";
        if (themeBtn != null) {
            themeBtn.setStyle(tbBase);
            themeBtn.setOnMouseEntered(e -> themeBtn.setStyle(tbBase.replace(theme.muted(),theme.text())));
            themeBtn.setOnMouseExited(e  -> themeBtn.setStyle(tbBase));
        }
    }

    /** Injects a CSS data-URL into the scene to style the TabPane header area,
     *  which cannot be reached via Node.setStyle(). */
    private void applyTabPaneCss() {
        if (scene == null || tabPane == null) return;
        scene.getStylesheets().clear();
        String bg     = theme.bg();
        String panel  = theme.panel();
        String border = theme.border();
        String text   = theme.text();
        String muted  = theme.muted();
        String css = String.format("""
            .tab-pane > .tab-header-area {
                -fx-background-color: %s;
                -fx-padding: 0;
            }
            .tab-pane > .tab-header-area > .tab-header-background {
                -fx-background-color: %s;
            }
            .tab-pane > .tab-header-area > .headers-region > .tab {
                -fx-background-color: %s;
                -fx-background-radius: 5 5 0 0;
                -fx-padding: 5 14 5 14;
                -fx-cursor: hand;
            }
            .tab-pane > .tab-header-area > .headers-region > .tab:selected {
                -fx-background-color: %s;
            }
            .tab-pane > .tab-header-area > .headers-region > .tab .tab-label {
                -fx-text-fill: %s;
                -fx-font-size: 12px;
            }
            .tab-pane > .tab-header-area > .headers-region > .tab:selected .tab-label {
                -fx-text-fill: %s;
                -fx-font-weight: bold;
            }
            .tab-pane > .tab-header-area > .tab-header-background,
            .tab-pane > .tab-content-area {
                -fx-background-color: %s;
            }
            """, bg, bg, bg, panel, muted, text, bg);
        scene.getStylesheets().add("data:text/css," +
                css.replace(" ", "%20").replace("\n", "%0A")
                   .replace("{", "%7B").replace("}", "%7D")
                   .replace(":", "%3A").replace(";", "%3B")
                   .replace("#", "%23").replace(".", "%2E")
                   .replace(">", "%3E").replace("-", "%2D")
                   .replace("(", "%28").replace(")", "%29")
                   .replace(",", "%2C").replace("'", "%27")
                   .replace("/", "%2F").replace("+", "%2B")
                   .replace("*", "%2A")
        );
    }

    /** Re-applies text field inline styles after a theme switch. */
    private void applyTextFieldStyles() {
        String tf = tfStyle();
        if (hostField        != null) hostField.setStyle(tf);
        if (mgmtPortField    != null) mgmtPortField.setStyle(tf);
        if (webPortField     != null) webPortField.setStyle(tf);
        if (uploadDirField   != null) uploadDirField.setStyle(tf);
        if (dbPathField      != null) dbPathField.setStyle(tf);
        if (keystoreField    != null) keystoreField.setStyle(tf);
        if (srvMgmtPortField != null) srvMgmtPortField.setStyle(tf);
        if (siteTitleField   != null) siteTitleField.setStyle(tf);
        if (sessionTimeoutField != null) sessionTimeoutField.setStyle(tf);
    }

    private void applyLogAreaStyle() {
        if (logArea == null) return;
        logArea.setStyle(
            "-fx-control-inner-background:" + theme.logBg() + ";" +
            "-fx-text-fill:" + theme.logText() + ";" +
            "-fx-font-family:'Consolas','Courier New',monospace;" +
            "-fx-font-size:12px;-fx-border-color:" + theme.border() + ";" +
            "-fx-background-radius:6;-fx-border-radius:6;");
    }

    private String cardStyle() {
        return "-fx-background-color:" + theme.panel() + ";-fx-border-color:" + theme.border() +
               ";-fx-border-radius:8;-fx-background-radius:8;-fx-padding:14 16;";
    }

    // ── UI factory helpers ────────────────────────────────────────────────────

    private Tab buildTab(String title, javafx.scene.Node content) {
        return new Tab(title, content);
    }

    private ScrollPane wrapInScroll(VBox card) {
        ScrollPane sp = new ScrollPane(card);
        sp.setFitToWidth(true); sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setStyle("-fx-background-color:transparent;-fx-background:transparent;");
        sp.skinProperty().addListener((obs,o,n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = sp.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
    }

    private ScrollPane makeScroll(VBox content, int minH) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true); sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setMinHeight(minH);
        sp.setStyle("-fx-background-color:transparent;-fx-background:transparent;" +
                "-fx-border-color:" + theme.border() + ";-fx-border-radius:6;");
        sp.skinProperty().addListener((obs,o,n) -> Platform.runLater(() -> {
            javafx.scene.Node vp = sp.lookup(".viewport");
            if (vp != null) vp.setStyle("-fx-background-color:transparent;");
        }));
        return sp;
    }

    private Label colHdr(String t, double w) {
        Label l = new Label(t); l.setPrefWidth(w); l.setMinWidth(w);
        l.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:10px;-fx-font-weight:bold;");
        return l;
    }

    private Label rowCell(String t, double w) {
        Label l = new Label(t); l.setPrefWidth(w); l.setMinWidth(w); l.setMaxWidth(w);
        l.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-size:11px;");
        return l;
    }

    private Label placeholder(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;-fx-padding:10 6;");
        return l;
    }

    private Label sectionLabel(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-weight:bold;-fx-font-size:12px;");
        return l;
    }

    private Label fieldLabel(String text, double w) {
        Label l = new Label(text); l.setMinWidth(w);
        l.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;");
        return l;
    }

    private TextField styledTF(String val, double w) {
        TextField tf = new TextField(val); tf.setPrefWidth(w); tf.setStyle(tfStyle());
        return tf;
    }

    private String tfStyle() {
        return "-fx-background-color:" + theme.input() + ";-fx-text-fill:" + theme.text() +
               ";-fx-border-color:" + theme.border() + ";-fx-border-radius:5;-fx-background-radius:5;-fx-padding:5 8;";
    }

    private Button primaryBtn(String text) {
        Button b = new Button(text); b.setMaxWidth(Double.MAX_VALUE);
        String base = "-fx-background-color:" + ACCENT + ";-fx-text-fill:white;" +
                "-fx-font-weight:bold;-fx-background-radius:6;-fx-cursor:hand;-fx-padding:7 0;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(ACCENT, ACCENTH)));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    /** Builds a "Refresh" style button with a green background (#66ff66). */
    private Button greenRefreshBtn(String text) {
        return greenRefreshBtn(text, "5 12", "");
    }

    /** Builds a "Refresh" style button with a green background (#66ff66) and custom padding/extra CSS. */
    private Button greenRefreshBtn(String text, String padding, String extra) {
        Button b = new Button(text);
        String base = "-fx-background-color:#66ff66;-fx-text-fill:#1a1d27;-fx-border-color:" +
                theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:" + padding + ";-fx-font-weight:bold;" + extra;
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace("#66ff66","#80ff80")));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private Button accentOutlineBtn(String text) {
        Button b = new Button(text);
        String base = "-fx-background-color:transparent;-fx-text-fill:" + theme.muted() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:5 12;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(theme.muted(),theme.text()).replace(theme.border(),ACCENT)));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private Button ghostBtn(String text) {
        Button b = new Button(text);
        String base = "-fx-background-color:transparent;-fx-text-fill:" + theme.muted() + ";" +
                "-fx-border-color:" + theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:7 16;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(theme.muted(),theme.text()).replace(theme.border(),ACCENT)));
        b.setOnMouseExited(e  -> b.setStyle(base));
        return b;
    }

    private void styleOutlineBtn(Button b) {
        String base = "-fx-background-color:transparent;-fx-text-fill:" + theme.muted() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:6;-fx-background-radius:6;" +
                "-fx-cursor:hand;-fx-padding:4 10;-fx-font-size:11px;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(theme.muted(),theme.text())));
        b.setOnMouseExited(e  -> b.setStyle(base));
    }

    private void applyToggleStyle(Button b, boolean isDanger) {
        String bg    = isDanger ? DANGER  : ACCENT;
        String hover = isDanger ? DANGERH : ACCENTH;
        String base = "-fx-background-color:" + bg + ";-fx-text-fill:white;" +
                "-fx-font-weight:bold;-fx-background-radius:6;-fx-cursor:hand;" +
                "-fx-font-size:13px;-fx-padding:8 24;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(bg,hover)));
        b.setOnMouseExited(e  -> b.setStyle(base));
    }

    private void styleSmallBtn(Button b, String bg, String hover) {
        String base = "-fx-background-color:" + bg + ";-fx-text-fill:white;" +
                "-fx-background-radius:5;-fx-cursor:hand;-fx-font-size:11px;-fx-padding:3 9;";
        b.setStyle(base);
        b.setOnMouseEntered(e -> b.setStyle(base.replace(bg,hover)));
        b.setOnMouseExited(e  -> b.setStyle(base));
    }

    private ComboBox<String> themedCombo(String[] items) {
        ComboBox<String> c = new ComboBox<>();
        c.getItems().addAll(items); c.setPrefWidth(220); c.setMaxWidth(220);
        c.setStyle("-fx-background-color:" + theme.input() + ";-fx-border-color:" + theme.border() + ";" +
                   "-fx-border-radius:5;-fx-background-radius:5;-fx-padding:2 4;");
        javafx.util.Callback<ListView<String>,ListCell<String>> cf = lv -> {
            if (lv != null) lv.setStyle("-fx-background-color:" + theme.panel() + ";-fx-border-color:" + theme.border() + ";");
            return new ListCell<>() {
                @Override protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty||item==null?null:item);
                    setStyle("-fx-background-color:" + theme.panel() + ";-fx-text-fill:" + theme.text() + ";-fx-font-size:12px;-fx-padding:5 8;");
                }
                @Override public void updateSelected(boolean sel) {
                    super.updateSelected(sel);
                    if (!isEmpty()) setStyle("-fx-background-color:"+(sel?theme.border():theme.panel())+
                            ";-fx-text-fill:" + theme.text() + ";-fx-font-size:12px;-fx-padding:5 8;");
                }
            };
        };
        c.setCellFactory(cf); c.setButtonCell(cf.call(null));
        return c;
    }

    private void updateLabel(String id, String text) {
        if (filesCard == null) return;
        filesCard.getChildren().stream()
            .filter(n -> n instanceof HBox)
            .findFirst()
            .ifPresent(tb -> ((HBox)tb).getChildren().stream()
                .filter(n -> n instanceof Label l && id.equals(l.getId()))
                .forEach(n -> ((Label)n).setText(text)));
    }

    // ── Dialog helpers ────────────────────────────────────────────────────────

    private Stage dialogStage(String title) {
        Stage s = new Stage(StageStyle.DECORATED);
        s.setTitle(title); s.initModality(Modality.APPLICATION_MODAL); s.setResizable(false);
        return s;
    }
    private VBox dialogBox() {
        VBox b = new VBox(12); b.setPadding(new Insets(20));
        b.setStyle("-fx-background-color:" + theme.panel() + ";"); return b;
    }
    private Label dialogTitle(String t) {
        Label l = new Label(t); l.setStyle("-fx-text-fill:" + theme.text() + ";-fx-font-weight:bold;-fx-font-size:13px;");
        l.setWrapText(true); return l;
    }
    private Label dialogSub(String t) {
        Label l = new Label(t); l.setStyle("-fx-text-fill:" + theme.muted() + ";-fx-font-size:11px;"); return l;
    }
    private PasswordField dialogPwField(String prompt) {
        PasswordField pf = new PasswordField(); pf.setPromptText(prompt);
        pf.setStyle("-fx-background-color:" + theme.input() + ";-fx-text-fill:" + theme.text() +
                ";-fx-border-color:" + theme.border() + ";-fx-border-radius:5;-fx-background-radius:5;-fx-padding:6 8;");
        return pf;
    }
    private Label errorLabel() {
        Label l = new Label(""); l.setStyle("-fx-text-fill:#e0607a;-fx-font-size:11px;");
        l.setWrapText(true); return l;
    }
    private boolean confirm(String msg) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, msg, ButtonType.YES, ButtonType.NO);
        a.setTitle("Confirm"); a.setHeaderText(null);
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }
    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
            a.setTitle("ShareWave"); a.setHeaderText(null); a.showAndWait();
        });
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private static String fmtBytes(long b) {
        if (b < 1024)            return b + " B";
        if (b < 1048576)         return String.format("%.1f KB", b/1024.0);
        if (b < 1073741824)      return String.format("%.2f MB", b/1048576.0);
        return String.format("%.2f GB", b/1073741824.0);
    }

    public static void main(String[] args) { launch(args); }
}
