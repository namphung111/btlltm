package com.chat.platform.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ChatDesktopApplication extends Application implements WebSocket.Listener {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final ExecutorService requests = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "chat-http");
        thread.setDaemon(true);
        return thread;
    });
    private final ListView<Room> roomList = new ListView<>();
    private final ListView<DirectConversation> directConversationList = new ListView<>();
    private final ListView<Member> memberList = new ListView<>();
    private final ListView<ConversationEntry> conversationList = new ListView<>();
    private final TextField conversationSearch = new TextField();
    private final ComboBox<String> conversationFilter = new ComboBox<>();
    private final VBox messages = new VBox(10);
    private final ScrollPane messageScroll = new ScrollPane(messages);
    private final Label title = new Label("Chọn hoặc tham gia nhóm");
    private final Label membersLabel = new Label();
    private final TextField messageInput = new TextField();
    private final Button leaveGroupButton = new Button("Rời nhóm");
    private final Button transferLeaderButton = new Button("Chuyển trưởng nhóm");
    private final Button deleteGroupButton = new Button("Xóa nhóm");
    private final Map<String, Message> displayedMessages = new HashMap<>();
    private final Map<String, Image> avatarCache = new HashMap<>();
    private final Set<String> avatarsWithoutImage = new HashSet<>();
    private final Set<String> avatarLoads = new HashSet<>();
    private final Set<String> subscriptions = new HashSet<>();

    private Stage stage;
    private String baseUrl;
    private String username;
    private String token;
    private String activeRoomId;
    private boolean activeDirectConversation;
    private String activeDirectUsername;
    private WebSocket socket;
    private String incomingText = "";
    private String conversationFilterValue = "Tất cả";

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        showLogin();
    }

    @Override
    public void stop() {
        requests.shutdownNow();
        if (socket != null) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "Client closed");
        }
    }

    private void showLogin() {
        TextField serverField = new TextField("http://localhost:8080");
        TextField usernameField = new TextField();
        PasswordField passwordField = new PasswordField();
        serverField.setPromptText("Server URL, ví dụ https://chat.example.com");
        usernameField.setPromptText("Tên đăng nhập");
        passwordField.setPromptText("Mật khẩu (ít nhất 8 ký tự)");
        Label serverLabel = new Label("Máy chủ");
        Label usernameLabel = new Label("Tên đăng nhập");
        Label passwordLabel = new Label("Mật khẩu");
        serverLabel.setLabelFor(serverField);
        usernameLabel.setLabelFor(usernameField);
        passwordLabel.setLabelFor(passwordField);
        serverField.getStyleClass().add("form-field");
        usernameField.getStyleClass().add("form-field");
        passwordField.getStyleClass().add("form-field");
        Button loginButton = new Button("Đăng nhập");
        loginButton.getStyleClass().add("primary-button");
        Button registerButton = new Button("Đăng ký");
        registerButton.getStyleClass().add("secondary-button");
        Label message = new Label("Kết nối tới server trung tâm để bắt đầu chat.");
        message.getStyleClass().add("login-status");
        HBox buttons = new HBox(10, loginButton, registerButton);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Label brandMark = new Label("R");
        brandMark.getStyleClass().add("brand-mark");
        Label brandName = new Label("REALTIME CHAT");
        brandName.getStyleClass().add("brand-name");
        HBox brand = new HBox(11, brandMark, brandName);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label heading = new Label("Chào mừng trở lại");
        heading.getStyleClass().add("login-heading");
        Label subtitle = new Label("Đăng nhập hoặc tạo tài khoản để tiếp tục trò chuyện.");
        subtitle.getStyleClass().add("login-subtitle");
        VBox form = new VBox(9, brand, heading, subtitle,
                serverLabel, serverField, usernameLabel, usernameField,
                passwordLabel, passwordField, buttons, message);
        form.getStyleClass().add("login-card");
        BorderPane root = new BorderPane(form);
        root.getStyleClass().add("login-root");
        BorderPane.setAlignment(form, Pos.CENTER);
        loginButton.setOnAction(event -> authenticate(serverField, usernameField, passwordField,
                message, false));
        registerButton.setOnAction(event -> authenticate(serverField, usernameField, passwordField,
                message, true));
        stage.setTitle("Realtime Chat - Đăng nhập");
        Scene scene = new Scene(root, 600, 520);
        applyStyles(scene);
        stage.setScene(scene);
        stage.show();
    }

    private void authenticate(TextField serverField, TextField usernameField,
                              PasswordField passwordField, Label status, boolean register) {
        String server = serverField.getText().trim().replaceAll("/+$", "");
        String enteredUsername = usernameField.getText().trim();
        String password = passwordField.getText();
        if (!(server.startsWith("http://") || server.startsWith("https://"))
                || enteredUsername.isBlank() || password.isBlank()) {
            status.setText("Nhập URL http(s), tên đăng nhập và mật khẩu hợp lệ.");
            return;
        }
        URI serverUri;
        try {
            serverUri = URI.create(server);
        } catch (IllegalArgumentException e) {
            status.setText("URL server không hợp lệ.");
            return;
        }
        if ("http".equalsIgnoreCase(serverUri.getScheme()) && !isLocalHost(serverUri.getHost())) {
            status.setText("Server ngoài máy này phải dùng HTTPS để bảo vệ mật khẩu và token.");
            return;
        }
        status.setText("Đang kết nối...");
        requests.execute(() -> {
            try {
                HttpResponse<String> response = sendJson(server + "/api/auth/"
                        + (register ? "register" : "login"), Map.of(
                        "username", enteredUsername, "password", password), null);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException(errorText(response.body()));
                }
                JsonNode auth = JSON.readTree(response.body());
                baseUrl = server;
                username = auth.path("username").asText();
                token = auth.path("token").asText();
                socket = http.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .header("Authorization", "Bearer " + token)
                        .buildAsync(URI.create(webSocketUrl(server)), this).join();
                Platform.runLater(this::showChat);
            } catch (Exception e) {
                Platform.runLater(() -> status.setText("Không thể đăng nhập: " + rootMessage(e)));
            }
        });
    }

    private void showChat() {
        conversationList.getStyleClass().add("conversation-list");
        conversationList.setAccessibleText("Danh sách cuộc trò chuyện");
        conversationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ConversationEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean group = entry.room() != null;
                String name = group ? entry.room().name() : entry.direct().username();
                Label heading = new Label(name);
                heading.getStyleClass().add("room-name");
                Label kind = new Label(group ? "Nhóm" : (entry.direct().online() ? "Đang hoạt động" : "Ngoại tuyến"));
                kind.getStyleClass().add("member-status");
                HBox row = new HBox(9, group ? groupAvatar() : avatarView(name), new VBox(3, heading, kind));
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        conversationList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected == null) return;
            if (selected.room() != null) openRoom(selected.room());
            else if (!activeDirectConversation || !selected.direct().conversationId().equals(activeRoomId))
                openDirectConversation(selected.direct());
        });
        conversationSearch.setPromptText("Tìm kiếm cuộc trò chuyện");
        conversationSearch.getStyleClass().add("sidebar-field");
        conversationSearch.textProperty().addListener((observable, oldValue, newValue) -> rebuildConversationList());
        conversationFilter.getItems().setAll("Tất cả", "Nhóm", "Cá nhân");
        conversationFilter.setValue("Tất cả");
        conversationFilter.setAccessibleText("Lọc cuộc trò chuyện");
        conversationFilter.getStyleClass().add("conversation-filter");
        conversationFilter.setOnAction(event -> {
            conversationFilterValue = conversationFilter.getValue();
            rebuildConversationList();
        });
        conversationList.setPlaceholder(new Label("Chưa có cuộc trò chuyện"));
        VBox.setVgrow(conversationList, Priority.ALWAYS);
        roomList.setPrefWidth(250);
        roomList.getStyleClass().add("conversation-list");
        roomList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Room room, boolean empty) {
                super.updateItem(room, empty);
                if (empty || room == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label roomName = new Label(room.name());
                roomName.getStyleClass().add("room-name");
                Label roomId = new Label(room.roomId());
                roomId.getStyleClass().add("room-id");
                VBox details = new VBox(3, roomName, roomId);
                setText(null);
                setGraphic(details);
            }
        });
        memberList.getStyleClass().add("member-list");
        memberList.setPrefHeight(190);
        memberList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Member member, boolean empty) {
                super.updateItem(member, empty);
                if (empty || member == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(member.username());
                name.getStyleClass().add("member-name");
                Label status = new Label(member.online() ? "Đang hoạt động" : "Ngoại tuyến");
                status.getStyleClass().addAll("member-status",
                        member.online() ? "online" : "offline");
                Label role = new Label("LEADER".equals(member.role()) ? "Trưởng nhóm" : "Thành viên");
                role.getStyleClass().add("member-role");
                VBox details = new VBox(2, name, status, role);
                StackPane avatar = avatarView(member.username());
                if (!member.username().equals(username)) {
                    installDirectChatMenu(avatar, member.username());
                }
                HBox row = new HBox(8, avatar, details);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        directConversationList.getStyleClass().addAll("conversation-list", "direct-conversation-list");
        directConversationList.setPrefHeight(130);
        directConversationList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DirectConversation conversation, boolean empty) {
                super.updateItem(conversation, empty);
                if (empty || conversation == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Label name = new Label(conversation.username());
                name.getStyleClass().add("room-name");
                Label status = new Label(conversation.online() ? "Đang hoạt động" : "Ngoại tuyến");
                status.getStyleClass().addAll("member-status",
                        conversation.online() ? "online" : "offline");
                HBox row = new HBox(8, avatarView(conversation.username()), new VBox(2, name, status));
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            }
        });
        roomList.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
            if (selected != null) {
                openRoom(selected);
            }
        });
        directConversationList.getSelectionModel().selectedItemProperty()
                .addListener((observable, previous, selected) -> {
                    if (selected != null && (!activeDirectConversation
                            || !selected.conversationId().equals(activeRoomId))) {
                        openDirectConversation(selected);
                    }
                });

        Button createRoom = new Button("+");
        createRoom.setAccessibleText("Tạo nhóm mới");
        createRoom.setTooltip(new Tooltip("Tạo nhóm mới"));
        createRoom.getStyleClass().addAll("primary-button", "create-group-button");
        createRoom.setOnAction(event -> showCreateGroupDialog());
        TextField roomId = new TextField();
        roomId.setPromptText("ID phòng được chia sẻ");
        Button joinRoom = new Button("Tham gia");
        joinRoom.setOnAction(event -> joinRoom(roomId.getText()));
        HBox joinControls = new HBox(6, roomId, joinRoom);
        HBox.setHgrow(roomId, Priority.ALWAYS);

        Label conversationsHeading = new Label("Tin nhắn");
        conversationsHeading.getStyleClass().add("section-title");
        HBox searchAndFilter = new HBox(6, conversationSearch, conversationFilter);
        HBox.setHgrow(conversationSearch, Priority.ALWAYS);
        HBox createAndJoin = new HBox(6, createRoom, roomId, joinRoom);
        HBox.setHgrow(roomId, Priority.ALWAYS);
        VBox sidebar = new VBox(10, conversationsHeading, searchAndFilter, conversationList,
                new Separator(), new Label("Người trò chuyện"), memberList, createAndJoin);
        sidebar.getStyleClass().add("sidebar");
        roomId.getStyleClass().add("sidebar-field");
        joinRoom.getStyleClass().add("secondary-button");
        sidebar.setPadding(new Insets(14));
        sidebar.setPrefWidth(285);
        VBox.setVgrow(conversationList, Priority.ALWAYS);
        memberList.setPlaceholder(new Label("Chọn phòng để xem thành viên"));

        title.getStyleClass().add("chat-title");
        membersLabel.getStyleClass().add("member-count");
        Button avatarButton = new Button();
        avatarButton.setGraphic(avatarView(username));
        avatarButton.setAccessibleText("Thông tin tài khoản và đăng xuất");
        avatarButton.setTooltip(new Tooltip("Tài khoản"));
        avatarButton.getStyleClass().add("profile-button");
        avatarButton.setOnAction(event -> showProfileDialog());
        Button infoButton = new Button("i");
        infoButton.setAccessibleText("Thông tin cuộc trò chuyện");
        infoButton.setTooltip(new Tooltip("Thông tin cuộc trò chuyện"));
        infoButton.getStyleClass().add("utility-button");
        infoButton.setOnAction(event -> showConversationInfo());
        leaveGroupButton.getStyleClass().add("utility-button");
        transferLeaderButton.getStyleClass().add("secondary-button");
        deleteGroupButton.getStyleClass().add("danger-button");
        leaveGroupButton.setOnAction(event -> leaveCurrentGroup());
        transferLeaderButton.setOnAction(event -> transferGroupLeadership());
        deleteGroupButton.setOnAction(event -> deleteCurrentGroup());
        setGroupActionVisible(false, false);
        HBox header = new HBox(8, avatarButton, title, spacer(), membersLabel, infoButton);
        header.getStyleClass().add("chat-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(14));

        messages.setPadding(new Insets(14));
        messageScroll.setFitToWidth(true);
        messageScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        Button attach = new Button("Đính kèm");
        attach.getStyleClass().add("secondary-button");
        attach.setOnAction(event -> uploadFile());
        Button send = new Button("Gửi");
        send.getStyleClass().add("primary-button");
        send.setDefaultButton(true);
        send.setOnAction(event -> sendMessage());
        messageInput.setPromptText("Nhập tin nhắn...");
        messageInput.getStyleClass().add("message-input");
        messageInput.setOnAction(event -> sendMessage());
        HBox composer = new HBox(8, attach, messageInput, send);
        composer.getStyleClass().add("composer");
        composer.setPadding(new Insets(12));
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        BorderPane content = new BorderPane();
        content.getStyleClass().add("chat-content");
        content.setTop(header);
        content.setCenter(messageScroll);
        content.setBottom(composer);
        BorderPane root = new BorderPane(content, null, null, null, sidebar);
        root.getStyleClass().add("chat-root");
        stage.setTitle("Realtime Chat - " + username);
        Scene scene = new Scene(root, 1100, 740);
        applyStyles(scene);
        stage.setScene(scene);
        stage.show();
        runRequest(() -> {
            refreshRooms(null);
            refreshDirectConversations(null);
        });
    }

    private void logout() {
        String logoutUrl = baseUrl + "/api/auth/logout";
        String logoutToken = token;
        WebSocket loggedOutSocket = socket;
        socket = null;
        if (loggedOutSocket != null && !loggedOutSocket.isOutputClosed()) {
            loggedOutSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Logged out");
        }
        baseUrl = null;
        username = null;
        token = null;
        activeRoomId = null;
        activeDirectConversation = false;
        activeDirectUsername = null;
        incomingText = "";
        subscriptions.clear();
        displayedMessages.clear();
        avatarCache.clear();
        avatarsWithoutImage.clear();
        avatarLoads.clear();
        roomList.getSelectionModel().clearSelection();
        roomList.getItems().clear();
        directConversationList.getItems().clear();
        memberList.getItems().clear();
        messages.getChildren().clear();
        membersLabel.setText("");
        title.setText("Chọn hoặc tham gia phòng");
        messageInput.clear();
        showLogin();

        requests.execute(() -> {
            try {
                ensureSuccess(sendJson(logoutUrl, Map.of(), logoutToken));
            } catch (Exception e) {
                Platform.runLater(() -> showError(
                        "Đã đăng xuất khỏi ứng dụng nhưng không thể thu hồi phiên trên server: "
                                + rootMessage(e)));
            }
        });
    }

    private void createRoom(String name, String memberNames) {
        if (name == null || name.isBlank()) {
            showError("Nhập tên nhóm trước khi tạo.");
            return;
        }
        List<String> invitees = Arrays.stream(memberNames == null ? new String[0] : memberNames.split(","))
                .map(String::trim).filter(member -> !member.isBlank()).distinct().toList();
        runRequest(() -> {
            Room room = JSON.readValue(sendJson(baseUrl + "/api/rooms",
                    Map.of("name", name.trim(), "members", invitees), token).body(), Room.class);
            refreshRooms(room.roomId());
        });
    }

    private StackPane groupAvatar() {
        Label mark = new Label("#");
        mark.getStyleClass().add("group-avatar-mark");
        StackPane badge = new StackPane(mark);
        badge.getStyleClass().add("group-avatar");
        badge.setMinSize(40, 40);
        badge.setPrefSize(40, 40);
        return badge;
    }

    private void rebuildConversationList() {
        if (conversationList.getScene() == null) return;
        List<ConversationEntry> all = new ArrayList<>();
        roomList.getItems().forEach(room -> all.add(new ConversationEntry(room, null)));
        directConversationList.getItems().forEach(direct -> all.add(new ConversationEntry(null, direct)));
        String query = conversationSearch.getText() == null ? "" : conversationSearch.getText().trim().toLowerCase();
        List<ConversationEntry> filtered = all.stream().filter(entry -> {
            boolean group = entry.room() != null;
            boolean typeMatches = "Tất cả".equals(conversationFilterValue)
                    || ("Nhóm".equals(conversationFilterValue) && group)
                    || ("Cá nhân".equals(conversationFilterValue) && !group);
            String name = group ? entry.room().name() : entry.direct().username();
            return typeMatches && name.toLowerCase().contains(query);
        }).toList();
        conversationList.getItems().setAll(filtered);
        String activeId = activeRoomId;
        filtered.stream().filter(entry -> activeDirectConversation
                        ? entry.direct() != null && entry.direct().conversationId().equals(activeId)
                        : entry.room() != null && entry.room().roomId().equals(activeId))
                .findFirst().ifPresent(entry -> conversationList.getSelectionModel().select(entry));
    }

    private void showCreateGroupDialog() {
        TextField name = new TextField();
        name.setPromptText("Ví dụ: Nhóm đồ án Java");
        TextField members = new TextField();
        members.setPromptText("username thành viên, cách nhau bằng dấu phẩy");
        VBox fields = new VBox(8, new Label("Tên nhóm"), name,
                new Label("Thêm thành viên (có thể để trống)"), members);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Tạo nhóm mới");
        dialog.setHeaderText("Tạo cuộc trò chuyện nhóm");
        dialog.getDialogPane().setContent(fields);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, new ButtonType("Tạo", javafx.scene.control.ButtonBar.ButtonData.OK_DONE));
        dialog.showAndWait().filter(button -> button.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                .ifPresent(button -> createRoom(name.getText(), members.getText()));
    }

    private void showProfileDialog() {
        Label user = new Label("Tài khoản: " + username);
        Button change = new Button("Đổi avatar");
        change.getStyleClass().add("secondary-button");
        Button logout = new Button("Đăng xuất");
        logout.getStyleClass().add("danger-button");
        Button close = new Button("Đóng");
        close.getStyleClass().add("utility-button");
        HBox actions = new HBox(8, change, logout, close);
        VBox content = new VBox(12, avatarView(username), user, actions);
        content.setPadding(new Insets(8));
        Alert profile = new Alert(Alert.AlertType.NONE);
        profile.setTitle("Thông tin người dùng");
        profile.setHeaderText("Tài khoản của bạn");
        profile.getDialogPane().setContent(content);
        profile.getDialogPane().getButtonTypes().setAll();
        change.setOnAction(event -> { profile.close(); changeAvatar(); });
        logout.setOnAction(event -> { profile.close(); logout(); });
        close.setOnAction(event -> profile.close());
        profile.show();
    }

    private void showConversationInfo() {
        if (activeDirectConversation) {
            new Alert(Alert.AlertType.INFORMATION, "Trò chuyện với " + activeDirectUsername
                    + "\nTrạng thái: " + membersLabel.getText()).show();
            return;
        }
        if (activeRoomId == null) {
            return;
        }
        Room room = roomList.getItems().stream().filter(item -> item.roomId().equals(activeRoomId))
                .findFirst().orElse(null);
        if (room == null) return;
        boolean leader = memberList.getItems().stream().anyMatch(member -> member.username().equals(username)
                && "LEADER".equals(member.role()));
        Label id = new Label("Mã phòng: " + room.roomId());
        Button copy = new Button("Copy mã phòng");
        copy.getStyleClass().add("secondary-button");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(room.roomId());
            Clipboard.getSystemClipboard().setContent(content);
            copy.setText("Đã sao chép");
        });
        VBox members = new VBox(4);
        memberList.getItems().forEach(member -> members.getChildren().add(new Label(
                member.username() + ("LEADER".equals(member.role()) ? " · Trưởng nhóm" : " · Thành viên"))));
        Button add = new Button("Thêm thành viên");
        add.getStyleClass().add("secondary-button");
        add.setDisable(!leader);
        add.setOnAction(event -> addMembersToCurrentGroup());
        Button leave = new Button("Rời nhóm");
        leave.getStyleClass().add("utility-button");
        leave.setOnAction(event -> leaveCurrentGroup());
        VBox actions = new VBox(7);
        if (leader) {
            Button transfer = new Button("Chuyển trưởng nhóm");
            transfer.getStyleClass().add("secondary-button");
            transfer.setOnAction(event -> transferGroupLeadership());
            Button delete = new Button("Xóa nhóm");
            delete.getStyleClass().add("danger-button");
            delete.setOnAction(event -> deleteCurrentGroup());
            actions.getChildren().addAll(transfer, delete);
        }
        actions.getChildren().addAll(add, leave);
        VBox details = new VBox(10, new Label("Nhóm: " + room.name()), id, copy,
                new Label("Thành viên"), members, actions);
        details.setPadding(new Insets(8));
        Alert info = new Alert(Alert.AlertType.NONE);
        info.setTitle("Thông tin nhóm");
        info.setHeaderText("Thông tin cuộc trò chuyện");
        info.getDialogPane().setContent(details);
        info.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        info.show();
    }

    private void addMembersToCurrentGroup() {
        String roomId = activeRoomId;
        if (roomId == null || activeDirectConversation) return;
        TextField usernames = new TextField();
        usernames.setPromptText("alice, bob");
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("Thêm thành viên");
        dialog.setHeaderText("Nhập username cần thêm (phân cách bằng dấu phẩy)");
        dialog.getDialogPane().setContent(usernames);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        List<String> invitees = Arrays.stream(usernames.getText().split(","))
                .map(String::trim).filter(value -> !value.isBlank()).distinct().toList();
        if (invitees.isEmpty()) return;
        runRequest(() -> {
            HttpResponse<String> response = sendJson(baseUrl + "/api/rooms/" + roomId + "/members",
                    Map.of("members", invitees), token);
            ensureSuccess(response);
            refreshMembers(roomId);
            refreshRooms(roomId);
        });
    }

    private void setGroupActionVisible(boolean isMember, boolean isLeader) {
        leaveGroupButton.setVisible(isMember);
        leaveGroupButton.setManaged(isMember);
        transferLeaderButton.setVisible(isLeader);
        transferLeaderButton.setManaged(isLeader);
        deleteGroupButton.setVisible(isLeader);
        deleteGroupButton.setManaged(isLeader);
    }

    private void updateGroupActions() {
        if (activeRoomId == null || activeDirectConversation) {
            setGroupActionVisible(false, false);
            return;
        }
        Member current = memberList.getItems().stream()
                .filter(member -> member.username().equals(username)).findFirst().orElse(null);
        boolean isMember = current != null;
        boolean isLeader = isMember && "LEADER".equals(current.role());
        setGroupActionVisible(isMember, isLeader);
    }

    private void leaveCurrentGroup() {
        String roomId = activeRoomId;
        if (roomId == null || activeDirectConversation) {
            return;
        }
        Member current = memberList.getItems().stream()
                .filter(member -> member.username().equals(username)).findFirst().orElse(null);
        boolean isLeader = current != null && "LEADER".equals(current.role());
        Map<String, String> requestBody = Map.of();
        if (isLeader) {
            List<String> candidates = memberList.getItems().stream()
                    .map(Member::username).filter(member -> !member.equals(username)).toList();
            if (candidates.isEmpty()) {
                showError("Bạn là thành viên cuối cùng. Hãy dùng Xóa nhóm để kết thúc nhóm.");
                return;
            }
            ChoiceDialog<String> choice = new ChoiceDialog<>(candidates.get(0), candidates);
            choice.setTitle("Chuyển quyền trưởng nhóm");
            choice.setHeaderText("Chọn người kế nhiệm trước khi rời nhóm");
            choice.setContentText("Trưởng nhóm mới:");
            String successor = choice.showAndWait().orElse(null);
            if (successor == null) {
                return;
            }
            requestBody = Map.of("successorUsername", successor);
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Bạn có chắc muốn rời nhóm này?", ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Rời nhóm");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        Map<String, String> body = requestBody;
        runRequest(() -> {
            HttpResponse<String> response = sendJson(baseUrl + "/api/rooms/" + roomId + "/leave", body, token);
            ensureSuccess(response);
            Platform.runLater(() -> closeActiveGroup(roomId));
            refreshRooms(null);
        });
    }

    private void transferGroupLeadership() {
        String roomId = activeRoomId;
        Member selected = memberList.getSelectionModel().getSelectedItem();
        if (roomId == null || activeDirectConversation || selected == null
                || selected.username().equals(username)) {
            showError("Chọn một thành viên khác trong danh sách để chuyển quyền.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Chuyển quyền trưởng nhóm cho " + selected.username() + "?",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Chuyển trưởng nhóm");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runRequest(() -> {
            HttpResponse<String> response = sendJson(baseUrl + "/api/rooms/" + roomId
                            + "/transfer-leadership",
                    Map.of("successorUsername", selected.username()), token);
            ensureSuccess(response);
            refreshMembers(roomId);
        });
    }

    private void deleteCurrentGroup() {
        String roomId = activeRoomId;
        if (roomId == null || activeDirectConversation) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Nhóm sẽ bị ẩn với các thành viên. Lịch sử được giữ lại. Tiếp tục?",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Xóa nhóm");
        confirm.setHeaderText("Chỉ trưởng nhóm mới có thể thực hiện thao tác này.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runRequest(() -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/rooms/" + roomId))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).DELETE().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
            Platform.runLater(() -> closeActiveGroup(roomId));
            refreshRooms(null);
        });
    }

    private void closeActiveGroup(String roomId) {
        if (!roomId.equals(activeRoomId)) {
            return;
        }
        if (socket != null && !socket.isOutputClosed() && subscriptions.remove("room:" + roomId)) {
            socket.sendText(toJson(Map.of("type", "unsubscribe", "roomId", roomId)), true);
        }
        activeRoomId = null;
        activeDirectConversation = false;
        activeDirectUsername = null;
        roomList.getSelectionModel().clearSelection();
        directConversationList.getSelectionModel().clearSelection();
        conversationList.getSelectionModel().clearSelection();
        title.setText("Chọn hoặc tham gia nhóm");
        membersLabel.setText("");
        messageInput.clear();
        messageInput.setDisable(true);
        memberList.getItems().clear();
        messages.getChildren().clear();
        displayedMessages.clear();
        setGroupActionVisible(false, false);
    }

    private void changeAvatar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh đại diện");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Ảnh PNG, JPEG, GIF, BMP", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            if (Files.size(file.toPath()) > 5L * 1024 * 1024) {
                showError("Ảnh đại diện vượt quá giới hạn 5 MB.");
                return;
            }
        } catch (IOException e) {
            showError("Không thể đọc ảnh: " + e.getMessage());
            return;
        }
        String requestUrl = baseUrl + "/api/profile/avatar";
        String authToken = token;
        String currentUsername = username;
        runRequest(() -> {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String boundary = "----AvatarBoundary" + System.nanoTime();
            String bodyType = Files.probeContentType(file.toPath());
            if (bodyType == null) {
                bodyType = "application/octet-stream";
            }
            byte[] body = multipartBody(boundary, file.getName(), bodyType, bytes);
            HttpRequest request = HttpRequest.newBuilder(URI.create(requestUrl))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + authToken)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
            Platform.runLater(() -> {
                if (!currentUsername.equals(username)) {
                    return;
                }
                avatarCache.remove(currentUsername);
                avatarsWithoutImage.remove(currentUsername);
                avatarLoads.remove(currentUsername);
                displayedMessages.values().forEach(this::showMessage);
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Realtime Chat");
                alert.setHeaderText(null);
                alert.setContentText("Đã cập nhật ảnh đại diện.");
                alert.show();
            });
        });
    }

    private void joinRoom(String roomId) {
        String id = roomId == null ? "" : roomId.trim();
        if (id.isBlank()) {
            showError("Nhập ID phòng cần tham gia.");
            return;
        }
        runRequest(() -> {
            Room room = JSON.readValue(sendJson(baseUrl + "/api/rooms/" + id + "/join",
                    Map.of(), token).body(), Room.class);
            refreshRooms(room.roomId());
        });
    }

    private void refreshRooms(String selectRoomId) throws Exception {
        HttpResponse<String> response = get(baseUrl + "/api/rooms", token);
        ensureSuccess(response);
        List<Room> rooms = JSON.readerForListOf(Room.class).readValue(response.body());
        Platform.runLater(() -> {
            roomList.getItems().setAll(rooms);
            rebuildConversationList();
            if (selectRoomId != null) {
                rooms.stream().filter(room -> room.roomId().equals(selectRoomId)).findFirst()
                        .ifPresent(room -> roomList.getSelectionModel().select(room));
            }
        });
    }

    private void startDirectConversation(String otherUsername) {
        if (otherUsername.equals(username)) {
            return;
        }
        runRequest(() -> {
            HttpResponse<String> response = sendJson(baseUrl + "/api/direct-conversations/"
                            + java.net.URLEncoder.encode(otherUsername, StandardCharsets.UTF_8)
                            .replace("+", "%20"),
                    Map.of(), token);
            ensureSuccess(response, "POST /api/direct-conversations/" + otherUsername);
            DirectConversation conversation = JSON.readValue(response.body(), DirectConversation.class);
            refreshDirectConversations(conversation.conversationId());
        });
    }

    private void installDirectChatMenu(StackPane avatar, String otherUsername) {
        avatar.setCursor(Cursor.HAND);
        avatar.setAccessibleText("Mở trò chuyện riêng với " + otherUsername);
        avatar.setFocusTraversable(true);
        avatar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                directChatMenu(otherUsername).show(avatar, javafx.geometry.Side.BOTTOM, 0, 0);
                event.consume();
            }
        });
        avatar.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                directChatMenu(otherUsername).show(avatar,
                        javafx.geometry.Side.BOTTOM, 0, 0);
                event.consume();
            }
        });
    }

    private ContextMenu directChatMenu(String otherUsername) {
        MenuItem chatPrivately = new MenuItem("Trò chuyện riêng");
        chatPrivately.setOnAction(event -> startDirectConversation(otherUsername));
        return new ContextMenu(chatPrivately);
    }

    private void refreshDirectConversations(String selectConversationId) throws Exception {
        HttpResponse<String> response = get(baseUrl + "/api/direct-conversations", token);
        ensureSuccess(response);
        List<DirectConversation> conversations =
                JSON.readerForListOf(DirectConversation.class).readValue(response.body());
        Platform.runLater(() -> {
            directConversationList.getItems().setAll(conversations);
            rebuildConversationList();
            if (selectConversationId != null) {
                conversations.stream()
                        .filter(conversation -> conversation.conversationId().equals(selectConversationId))
                        .findFirst().ifPresent(conversation -> {
                            rebuildConversationList();
                            conversationList.getItems().stream()
                                    .filter(entry -> entry.direct() != null
                                            && entry.direct().conversationId().equals(selectConversationId))
                                    .findFirst().ifPresent(conversationList.getSelectionModel()::select);
                        });
            }
        });
    }

    private void openRoom(Room room) {
        activeRoomId = room.roomId();
        activeDirectConversation = false;
        activeDirectUsername = null;
        setGroupActionVisible(false, false);
        directConversationList.getSelectionModel().clearSelection();
        conversationList.getItems().stream().filter(entry -> entry.room() != null
                        && entry.room().roomId().equals(room.roomId()))
                .findFirst().ifPresent(entry -> {
                    if (conversationList.getSelectionModel().getSelectedItem() != entry)
                        conversationList.getSelectionModel().select(entry);
                });
        title.setText(room.name());
        messageInput.setDisable(false);
        memberList.getItems().clear();
        messages.getChildren().clear();
        displayedMessages.clear();
        if (socket != null && socket.isOutputClosed() == false
                && subscriptions.add("room:" + room.roomId())) {
            socket.sendText(toJson(Map.of("type", "subscribe", "roomId", room.roomId())), true);
        }
        runRequest(() -> {
            HttpResponse<String> response = get(baseUrl + "/api/rooms/" + room.roomId() + "/messages", token);
            ensureSuccess(response);
            List<Message> history = JSON.readerForListOf(Message.class).readValue(response.body());
            HttpResponse<String> membersResponse = get(
                    baseUrl + "/api/rooms/" + room.roomId() + "/members", token);
            ensureSuccess(membersResponse);
            List<Member> members = JSON.readerForListOf(Member.class).readValue(membersResponse.body());
            Platform.runLater(() -> {
                if (!room.roomId().equals(activeRoomId)) {
                    return;
                }
                setMembers(members);
                history.forEach(this::showMessage);
                history.stream().filter(message -> !message.sender().equals(username))
                        .forEach(this::markRead);
                scrollToBottom();
            });
        });
    }

    private void openDirectConversation(DirectConversation conversation) {
        activeRoomId = conversation.conversationId();
        activeDirectConversation = true;
        activeDirectUsername = conversation.username();
        setGroupActionVisible(false, false);
        roomList.getSelectionModel().clearSelection();
        conversationList.getItems().stream().filter(entry -> entry.direct() != null
                        && entry.direct().conversationId().equals(conversation.conversationId()))
                .findFirst().ifPresent(entry -> {
                    if (conversationList.getSelectionModel().getSelectedItem() != entry)
                        conversationList.getSelectionModel().select(entry);
                });
        title.setText(conversation.username());
        membersLabel.setText(conversation.online() ? "Trò chuyện riêng · Đang hoạt động"
                : "Trò chuyện riêng · Ngoại tuyến");
        memberList.getItems().clear();
        messageInput.setDisable(false);
        messages.getChildren().clear();
        displayedMessages.clear();
        String subscription = "direct:" + conversation.conversationId();
        if (socket != null && !socket.isOutputClosed() && subscriptions.add(subscription)) {
            socket.sendText(toJson(Map.of("type", "direct_subscribe",
                    "conversationId", conversation.conversationId())), true);
        }
        runRequest(() -> {
            HttpResponse<String> response = get(baseUrl + "/api/direct-conversations/"
                    + conversation.conversationId() + "/messages", token);
            ensureSuccess(response, "GET /api/direct-conversations/" + conversation.conversationId()
                    + "/messages");
            List<Message> history = JSON.readerForListOf(Message.class).readValue(response.body());
            Platform.runLater(() -> {
                if (!conversation.conversationId().equals(activeRoomId)
                        || !activeDirectConversation) {
                    return;
                }
                history.forEach(this::showMessage);
                history.stream().filter(message -> !message.sender().equals(username))
                        .forEach(this::markRead);
                scrollToBottom();
            });
        });
    }

    private void sendMessage() {
        String content = messageInput.getText().trim();
        String roomId = activeRoomId;
        if (roomId == null || content.isBlank() || socket == null) {
            return;
        }
        messageInput.clear();
        if (activeDirectConversation) {
            socket.sendText(toJson(Map.of("type", "direct_message",
                    "conversationId", roomId, "content", content)), true);
        } else {
            socket.sendText(toJson(Map.of("type", "message", "roomId", roomId, "content", content)), true);
        }
    }

    private void uploadFile() {
        if (activeRoomId == null) {
            showError("Chọn phòng chat trước khi gửi file.");
            return;
        }
        if (activeDirectConversation) {
            showError("Gửi tệp trong cuộc trò chuyện riêng hiện chưa được hỗ trợ.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh hoặc tệp đính kèm");
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        try {
            if (Files.size(file.toPath()) > 10L * 1024 * 1024) {
                showError("File vượt quá giới hạn 10 MB.");
                return;
            }
        } catch (IOException e) {
            showError("Không thể đọc file: " + e.getMessage());
            return;
        }
        String roomId = activeRoomId;
        runRequest(() -> {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String boundary = "----ChatBoundary" + System.nanoTime();
            String contentType = Files.probeContentType(file.toPath());
            if (contentType == null) {
                contentType = "application/octet-stream";
            }
            byte[] body = multipartBody(boundary, file.getName(), contentType, bytes);
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            baseUrl + "/api/rooms/" + roomId + "/files"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            ensureSuccess(response);
        });
    }

    private void showMessage(Message message) {
        String key = message.messageId() > 0 ? Long.toString(message.messageId())
                : message.fileId() == null ? message.sender() + message.timestamp() + message.content()
                : message.fileId();
        if (!displayedMessages.containsKey(key)) {
            displayedMessages.put(key, message);
            VBox bubble = new VBox(5);
            bubble.setPadding(new Insets(9));
            bubble.setMaxWidth(620);
            bubble.getStyleClass().addAll("chat-bubble",
                    message.sender().equals(username) ? "own-message" : "other-message");
            Label author = new Label(message.sender() + "  ·  "
                    + TIME_FORMAT.format(Instant.ofEpochMilli(message.timestamp())));
            author.getStyleClass().add("message-author");
            bubble.getChildren().add(author);
            if (!message.content().isBlank()) {
                Label content = new Label(message.content());
                content.setWrapText(true);
                content.setMaxWidth(580);
                content.getStyleClass().add("message-content");
                bubble.getChildren().add(content);
            }
            if (message.fileId() != null) {
                bubble.getChildren().add(attachmentControl(message));
            }
            VBox receipt = new VBox(3);
            Label receiptText = new Label();
            receiptText.getStyleClass().add("message-receipt");
            HBox readers = new HBox(3);
            receipt.getChildren().addAll(receiptText, readers);
            bubble.getChildren().add(receipt);
            bubble.setUserData(receipt);
            bubble.getProperties().put("message-key", key);
            HBox row = new HBox(bubble);
            row.setAlignment(message.sender().equals(username) ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
            messages.getChildren().add(row);
            updateReadReceipt(bubble, message);
        } else {
            displayedMessages.put(key, message);
            for (var child : messages.getChildren()) {
                if (child instanceof HBox row && !row.getChildren().isEmpty()) {
                    VBox bubble = (VBox) row.getChildren().get(0);
                    Object rowKey = bubble.getProperties().get("message-key");
                    if (key.equals(rowKey)) {
                        updateReadReceipt(bubble, message);
                        break;
                    }
                }
            }
        }
    }

    private void updateReadReceipt(VBox bubble, Message message) {
        VBox receipt = (VBox) bubble.getUserData();
        Label receiptText = (Label) receipt.getChildren().get(0);
        HBox readers = (HBox) receipt.getChildren().get(1);
        receiptText.setText(readText(message));
        readers.getChildren().clear();
        if (message.sender().equals(username)) {
            message.readBy().stream().filter(reader -> !reader.equals(username))
                    .distinct().forEach(reader -> readers.getChildren().add(avatarView(reader)));
        }
        // Keep the readers row at child index 1: later receipt updates reuse it.
        readers.setVisible(!readers.getChildren().isEmpty());
        readers.setManaged(!readers.getChildren().isEmpty());
        receipt.setVisible(!receiptText.getText().isBlank() || !readers.getChildren().isEmpty());
        receipt.setManaged(receipt.isVisible());
    }

    private StackPane avatarView(String reader) {
        Circle background = new Circle(12);
        background.setStyle("-fx-fill: #d9e2ef;");
        Label initials = new Label(reader.substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        initials.setStyle("-fx-font-size: 9px; -fx-font-weight: bold; -fx-text-fill: #43536a;");
        ImageView imageView = new ImageView();
        imageView.setFitWidth(24);
        imageView.setFitHeight(24);
        imageView.setPreserveRatio(false);
        imageView.setClip(new Circle(12, 12, 12));
        imageView.setVisible(false);
        StackPane avatar = new StackPane(background, initials, imageView);
        avatar.setPrefSize(24, 24);
        avatar.setMinSize(24, 24);
        avatar.setMaxSize(24, 24);
        avatar.setUserData(reader);
        Tooltip.install(avatar, new Tooltip(reader));
        Image cached = avatarCache.get(reader);
        if (cached != null) {
            imageView.setImage(cached);
            imageView.setVisible(true);
            initials.setVisible(false);
        } else if (!avatarsWithoutImage.contains(reader)) {
            requestAvatar(reader);
        }
        return avatar;
    }

    private void requestAvatar(String reader) {
        if (!avatarLoads.add(reader)) {
            return;
        }
        String requestBaseUrl = baseUrl;
        String requestToken = token;
        requests.execute(() -> {
            Image image;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                                requestBaseUrl + "/api/users/" + reader + "/avatar"))
                        .timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + requestToken)
                        .GET().build();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 404) {
                    Platform.runLater(() -> {
                        if (requestToken.equals(token)) {
                            avatarsWithoutImage.add(reader);
                            avatarLoads.remove(reader);
                        }
                    });
                    return;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Avatar request failed with HTTP " + response.statusCode() + ".");
                }
                image = new Image(new ByteArrayInputStream(response.body()));
                if (image.isError()) {
                    throw new IOException("Server returned an invalid avatar image.");
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (requestToken.equals(token)) {
                        avatarLoads.remove(reader);
                        showError("Không thể tải avatar của " + reader + ": " + rootMessage(e));
                    }
                });
                return;
            }
            Image loaded = image;
            Platform.runLater(() -> {
                if (!requestToken.equals(token)) {
                    return;
                }
                avatarCache.put(reader, loaded);
                avatarLoads.remove(reader);
                refreshAvatarViews(reader, loaded);
            });
        });
    }

    private void refreshAvatarViews(String reader, Image image) {
        for (var row : messages.getChildren()) {
            if (row instanceof HBox messageRow && !messageRow.getChildren().isEmpty()
                    && messageRow.getChildren().get(0) instanceof VBox bubble
                    && bubble.getUserData() instanceof VBox receipt
                    && receipt.getChildren().size() > 1
                    && receipt.getChildren().get(1) instanceof HBox readers) {
                for (var node : readers.getChildren()) {
                    if (node instanceof StackPane avatar && reader.equals(avatar.getUserData())) {
                        ImageView imageView = (ImageView) avatar.getChildren().get(2);
                        imageView.setImage(image);
                        imageView.setVisible(true);
                        avatar.getChildren().get(1).setVisible(false);
                    }
                }
            }
        }
    }

    private VBox attachmentControl(Message message) {
        Label fileName = new Label(message.fileName() == null ? "Tệp đính kèm" : message.fileName());
        Button download = new Button("Tải xuống");
        HBox row = new HBox(10, fileName, download);
        row.setAlignment(Pos.CENTER_LEFT);
        download.setOnAction(event -> downloadFile(message));
        VBox attachment = new VBox(6, row);
        if (message.contentType() != null && message.contentType().startsWith("image/")) {
            runRequest(() -> {
                byte[] bytes = downloadBytes(message.fileId());
                Image image = new Image(new ByteArrayInputStream(bytes));
                Platform.runLater(() -> {
                    ImageView preview = new ImageView(image);
                    preview.setPreserveRatio(true);
                    preview.setFitWidth(280);
                    preview.setFitHeight(200);
                    preview.setOnMouseClicked(event -> previewImage(message));
                    attachment.getChildren().add(preview);
                });
            });
        }
        return attachment;
    }

    private void previewImage(Message message) {
        runRequest(() -> {
            byte[] data = downloadBytes(message.fileId());
            Image image = new Image(new ByteArrayInputStream(data));
            Platform.runLater(() -> {
                ImageView imageView = new ImageView(image);
                imageView.setPreserveRatio(true);
                imageView.setFitWidth(800);
                imageView.setFitHeight(600);
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle(message.fileName());
                alert.setHeaderText(null);
                alert.getDialogPane().setContent(imageView);
                alert.getDialogPane().setPrefSize(850, 650);
                alert.show();
            });
        });
    }

    private void downloadFile(Message message) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Lưu tệp");
        chooser.setInitialFileName(message.fileName() == null ? "attachment" : message.fileName());
        java.io.File target = chooser.showSaveDialog(stage);
        if (target != null) {
            runRequest(() -> Files.write(target.toPath(), downloadBytes(message.fileId())));
        }
    }

    private byte[] downloadBytes(String fileId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/files/" + fileId))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Tải file thất bại (HTTP " + response.statusCode() + ").");
        }
        return response.body();
    }

    private void markRead(Message message) {
        if (socket != null && message.messageId() > 0) {
            if (activeDirectConversation) {
                socket.sendText(toJson(Map.of("type", "direct_read",
                        "conversationId", message.roomId(), "messageId", message.messageId())), true);
            } else {
                socket.sendText(toJson(Map.of("type", "read", "roomId", message.roomId(),
                        "messageId", message.messageId())), true);
            }
        }
    }

    private void handleServerMessage(String payload) {
        try {
            JsonNode event = JSON.readTree(payload);
            String type = event.path("type").asText();
            if ("message".equals(type)) {
                Message message = JSON.treeToValue(event.path("message"), Message.class);
                if (message.roomId().equals(activeRoomId)) {
                    showMessage(message);
                    if (!message.sender().equals(username)) {
                        markRead(message);
                    }
                    scrollToBottom();
                }
            } else if ("direct_message".equals(type)) {
                Message message = JSON.treeToValue(event.path("message"), Message.class);
                if (activeDirectConversation && message.roomId().equals(activeRoomId)) {
                    showMessage(message);
                    if (!message.sender().equals(username)) {
                        markRead(message);
                    }
                    scrollToBottom();
                } else {
                    runRequest(() -> refreshDirectConversations(null));
                }
            } else if ("read".equals(type)) {
                long messageId = event.path("messageId").asLong();
                List<String> readers = new ArrayList<>();
                event.path("readBy").forEach(node -> readers.add(node.asText()));
                displayedMessages.replaceAll((key, message) -> message.messageId() == messageId
                        && !activeDirectConversation && message.roomId().equals(activeRoomId)
                        ? new Message(message.messageId(), message.roomId(), message.sender(), message.content(),
                        message.timestamp(), message.fileId(), message.fileName(), message.contentType(), readers)
                        : message);
                Message updated = displayedMessages.values().stream()
                        .filter(message -> message.messageId() == messageId
                                && !activeDirectConversation && message.roomId().equals(activeRoomId))
                        .findFirst().orElse(null);
                if (updated != null) {
                    showMessage(updated);
                }
            } else if ("direct_read".equals(type)) {
                long messageId = event.path("messageId").asLong();
                List<String> readers = new ArrayList<>();
                event.path("readBy").forEach(node -> readers.add(node.asText()));
                displayedMessages.replaceAll((key, message) -> message.messageId() == messageId
                        && activeDirectConversation && message.roomId().equals(activeRoomId)
                        ? new Message(message.messageId(), message.roomId(), message.sender(), message.content(),
                        message.timestamp(), message.fileId(), message.fileName(), message.contentType(), readers)
                        : message);
                Message updated = displayedMessages.values().stream()
                        .filter(message -> message.messageId() == messageId
                                && activeDirectConversation && message.roomId().equals(activeRoomId))
                        .findFirst().orElse(null);
                if (updated != null) {
                    showMessage(updated);
                }
            } else if ("direct_presence".equals(type)) {
                updateDirectPresence(event.path("conversationId").asText(),
                        event.path("username").asText(), event.path("online").asBoolean());
            } else if ("presence".equals(type)
                    && event.path("roomId").asText().equals(activeRoomId)) {
                updateMemberPresence(event.path("username").asText(),
                        event.path("online").asBoolean());
            } else if ("group_created".equals(type)) {
                runRequest(() -> refreshRooms(null));
            } else if ("group_members_changed".equals(type)) {
                String roomId = event.path("roomId").asText();
                if (roomId.equals(activeRoomId)) refreshMembers(roomId);
                runRequest(() -> refreshRooms(null));
            } else if ("group_member_left".equals(type)) {
                String roomId = event.path("roomId").asText();
                String member = event.path("username").asText();
                if (roomId.equals(activeRoomId) && member.equals(username)) {
                    closeActiveGroup(roomId);
                } else if (roomId.equals(activeRoomId)) {
                    refreshMembers(roomId);
                }
                runRequest(() -> refreshRooms(null));
            } else if ("group_leader_changed".equals(type)) {
                String roomId = event.path("roomId").asText();
                if (roomId.equals(activeRoomId)) {
                    refreshMembers(roomId);
                }
            } else if ("group_deleted".equals(type)) {
                String roomId = event.path("roomId").asText();
                if (roomId.equals(activeRoomId)) {
                    closeActiveGroup(roomId);
                }
                runRequest(() -> refreshRooms(null));
            } else if ("error".equals(type)) {
                showError(event.path("message").asText("Server request failed."));
            }
        } catch (Exception e) {
            showError("Không đọc được dữ liệu server: " + rootMessage(e));
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        if (webSocket != socket) {
            webSocket.request(1);
            return null;
        }
        incomingText += data;
        if (last) {
            String complete = incomingText;
            incomingText = "";
            Platform.runLater(() -> {
                if (webSocket == socket) {
                    handleServerMessage(complete);
                }
            });
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (webSocket == socket) {
            Platform.runLater(() -> {
                if (webSocket == socket) {
                    showError("WebSocket: " + rootMessage(error));
                }
            });
        }
    }

    private HttpResponse<String> get(String url, String bearer) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + bearer).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendJson(String url, Object body, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void ensureSuccess(HttpResponse<String> response) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(errorText(response.body()));
        }
    }

    private void ensureSuccess(HttpResponse<String> response, String operation) throws IOException {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(operation + " (HTTP " + response.statusCode() + "): "
                    + errorText(response.body()));
        }
    }

    private byte[] multipartBody(String boundary, String fileName, String contentType, byte[] bytes)
            throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\""
                + fileName.replace("\"", "") + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode request JSON.", e);
        }
    }

    private String errorText(String body) {
        try {
            JsonNode json = JSON.readTree(body);
            return json.path("message").asText(json.path("error").asText("Request failed."));
        } catch (IOException e) {
            return body == null || body.isBlank() ? "Request failed." : body;
        }
    }

    private String webSocketUrl(String httpUrl) {
        return (httpUrl.startsWith("https://") ? "wss://" + httpUrl.substring(8)
                : "ws://" + httpUrl.substring(7)) + "/ws";
    }

    private boolean isLocalHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private String readText(Message message) {
        if (!message.sender().equals(username)) {
            return "";
        }
        List<String> others = message.readBy().stream().filter(reader -> !reader.equals(username)).toList();
        return others.isEmpty() ? "Đã gửi" : "Đã xem bởi: " + String.join(", ", others);
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private void scrollToBottom() {
        Platform.runLater(() -> messageScroll.setVvalue(1.0));
    }

    private void refreshMembers(String roomId) {
        runRequest(() -> {
            HttpResponse<String> response = get(baseUrl + "/api/rooms/" + roomId + "/members", token);
            ensureSuccess(response);
            List<Member> members = JSON.readerForListOf(Member.class).readValue(response.body());
            Platform.runLater(() -> {
                if (roomId.equals(activeRoomId)) {
                    setMembers(members);
                }
            });
        });
    }

    private void setMembers(List<Member> members) {
        memberList.getItems().setAll(members);
        long onlineCount = members.stream().filter(Member::online).count();
        membersLabel.setText(members.size() + " thành viên · " + onlineCount + " online");
        updateGroupActions();
    }

    private void updateMemberPresence(String memberUsername, boolean online) {
        if (memberUsername == null || memberUsername.isBlank()) {
            return;
        }
        List<Member> updatedMembers = memberList.getItems().stream()
                .map(member -> member.username().equals(memberUsername)
                        ? new Member(member.username(), online, member.role()) : member)
                .toList();
        if (updatedMembers.stream().anyMatch(member -> member.username().equals(memberUsername))) {
            setMembers(updatedMembers);
        } else {
            refreshMembers(activeRoomId);
        }
    }

    private void updateDirectPresence(String conversationId, String otherUsername, boolean online) {
        for (int index = 0; index < directConversationList.getItems().size(); index++) {
            DirectConversation conversation = directConversationList.getItems().get(index);
            if (conversation.conversationId().equals(conversationId)
                    && conversation.username().equals(otherUsername)) {
                directConversationList.getItems().set(index,
                        new DirectConversation(conversationId, otherUsername, online));
                break;
            }
        }
        rebuildConversationList();
        if (activeDirectConversation && conversationId.equals(activeRoomId)
                && otherUsername.equals(activeDirectUsername)) {
            membersLabel.setText(online ? "Trò chuyện riêng · Đang hoạt động"
                    : "Trò chuyện riêng · Ngoại tuyến");
        }
    }

    private void runRequest(CheckedRunnable runnable) {
        requests.execute(() -> {
            try {
                runnable.run();
            } catch (Exception e) {
                Platform.runLater(() -> showError(rootMessage(e)));
            }
        });
    }

    private void showError(String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Realtime Chat");
        alert.setHeaderText("Không thể hoàn tất thao tác");
        alert.setContentText(text);
        alert.show();
    }

    private void applyStyles(Scene scene) {
        var stylesheet = getClass().getResource("/chat.css");
        if (stylesheet == null) {
            throw new IllegalStateException("Missing JavaFX stylesheet: chat.css");
        }
        scene.getStylesheets().add(stylesheet.toExternalForm());
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    public record Room(String roomId, String name, String createdBy, long createdAt) {
    }

    public record DirectConversation(String conversationId, String username, boolean online) {
    }

    private record ConversationEntry(Room room, DirectConversation direct) {
    }

    public record Member(String username, boolean online, String role) {
    }

    public record Message(long messageId, String roomId, String sender, String content,
                          long timestamp, String fileId, String fileName, String contentType,
                          List<String> readBy) {
    }

    public static void main(String[] args) {
        launch(args);
    }
}
