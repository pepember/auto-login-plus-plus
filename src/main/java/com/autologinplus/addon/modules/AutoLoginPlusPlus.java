package com.autologinplus.addon.modules;

import com.autologinplus.addon.AutoLoginAddon;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WHorizontalSeparator;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.dialog.DialogActionButtonData;
import net.minecraft.dialog.DialogCommonData;
import net.minecraft.dialog.action.DynamicCustomDialogAction;
import net.minecraft.dialog.body.DialogBody;
import net.minecraft.dialog.type.Dialog;
import net.minecraft.dialog.type.DialogInput;
import net.minecraft.dialog.type.MultiActionDialog;
import net.minecraft.dialog.type.SimpleDialog;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.common.CustomClickActionC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.common.ShowDialogS2CPacket;
import net.minecraft.util.Identifier;

import java.net.InetSocketAddress;
import java.util.*;

public class AutoLoginPlusPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // 1. Auto-recording settings at the top
    private final Setting<Boolean> autoRecord = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-record-password")
        .description("Automatically captures passwords entered in chat or DialogUi and saves/updates them.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> recordAliases = sgGeneral.add(new BoolSetting.Builder()
        .name("record-aliases")
        .description("Saves group alias instead of server IP if current server belongs to a group.")
        .defaultValue(true)
        .visible(autoRecord::get)
        .build()
    );

    // 2. Dialog and login options
    private final Setting<Boolean> hideDialog = sgGeneral.add(new BoolSetting.Builder()
        .name("hide-dialog-screen")
        .description("Suppresses the DialogUi screen when auto-logging in.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay-ticks")
        .description("Delay in ticks before sending chat login command.")
        .defaultValue(10)
        .min(0)
        .sliderMax(100)
        .build()
    );

    // 3. Hidden storage lists
    private final Setting<List<String>> accounts = sgGeneral.add(new StringListSetting.Builder()
        .name("accounts")
        .defaultValue(new ArrayList<>())
        .visible(() -> false)
        .build()
    );

    private final Setting<List<String>> serverGroups = sgGeneral.add(new StringListSetting.Builder()
        .name("server-groups")
        .defaultValue(new ArrayList<>())
        .visible(() -> false)
        .build()
    );

    private static final String[] REGISTER_KEYWORDS = {
        "/register", "/reg", "register", "зарегистрируйтесь", "/рег", "создайте пароль"
    };
    private static final String[] LOGIN_KEYWORDS = {
        "/login", "/l ", "login", "авторизуйтесь", "войдите", "/логин", "пароль"
    };
    private static final String[] AUTH_PROMPT_INDICATORS = {
        "please", "type", "use", "welcome", "введите", "используйте"
    };

    private final List<String> messageQueue = new LinkedList<>();
    private int timer = 0;
    private boolean inLobby = false;

    // Session tracking to prevent infinite loops on incorrect password
    private Object lastConnection = null;
    private boolean dialogAttemptSent = false;
    private boolean sendingOurPacket = false;
    private String lastActiveHost = "";

    // Staging for DialogUi manual recording: only commit once player actually enters the world!
    private String pendingHost = null;
    private String pendingNick = null;
    private String pendingCommand = null;

    // Collapsible section states
    private boolean accountsExpanded = true;
    private boolean groupsExpanded = true;

    // UI reference for dynamic refresh upon auto-recording
    private WAutoLoginContainer currentWidget = null;
    private GuiTheme currentTheme = null;

    public AutoLoginPlusPlus() {
        super(Categories.Misc, "auto-login-++", "Auto login module supporting Chat, DialogUi, Server Groups and Auto-Record.");
        this.runInMainMenu = true;
    }

    private static class WAutoLoginContainer extends WVerticalList {
        public WAutoLoginContainer(GuiTheme theme) {
            this.theme = theme;
        }

        @Override
        public void init() {
            super.init();
            // Automatically remove the redundant separator inserted by ModuleScreen right before this widget
            if (this.parent instanceof WContainer container) {
                for (int i = container.cells.size() - 1; i >= 0; i--) {
                    Cell<?> c = container.cells.get(i);
                    if (c.widget() instanceof WHorizontalSeparator) {
                        container.remove(c);
                        break;
                    }
                }
            }
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WAutoLoginContainer list = new WAutoLoginContainer(theme);
        this.currentWidget = list;
        this.currentTheme = theme;
        fillWidget(theme, list);
        return list;
    }

    private void fillWidget(GuiTheme theme, WAutoLoginContainer parent) {
        parent.theme = theme;
        parent.clear();

        // --- SECTION 1: ACCOUNTS (with collapsible arrow) ---
        WSection accSection = parent.add(theme.section("ACCOUNTS", accountsExpanded)).expandX().widget();
        accSection.action = () -> accountsExpanded = accSection.isExpanded();
        WTable accTable = accSection.add(theme.table()).expandX().widget();

        List<String> accList = new ArrayList<>(accounts.get());

        if (!accList.isEmpty()) {
            accTable.add(theme.label("SERVER IP / GROUP"));
            accTable.add(theme.label("NICKNAME"));
            accTable.add(theme.label("PASSWORD / COMMAND"));
            accTable.add(theme.label(""));
            accTable.row();
        }

        for (int i = 0; i < accList.size(); i++) {
            int index = i;
            String[] data = accList.get(i).split("\\|", 3);

            String valIp = data.length > 0 ? data[0] : "";
            String valNick = data.length > 1 ? data[1] : "";
            String valPass = data.length > 2 ? data[2] : "/l ";

            WTextBox wIp = accTable.add(theme.textBox(valIp)).minWidth(140).expandX().widget();
            WTextBox wNick = accTable.add(theme.textBox(valNick)).minWidth(110).expandX().widget();
            WTextBox wPass = accTable.add(theme.textBox(valPass)).minWidth(140).expandX().widget();

            Runnable update = () -> {
                accList.set(index, wIp.get() + "|" + wNick.get() + "|" + wPass.get());
                accounts.set(accList);
            };

            wIp.action = update;
            wNick.action = update;
            wPass.action = update;

            WButton del = accTable.add(theme.button(" × ")).widget();
            del.action = () -> {
                accList.remove(index);
                accounts.set(accList);
                fillWidget(theme, parent);
            };
            accTable.row();
        }

        accTable.add(theme.label(""));
        accTable.row();

        WButton addEmpty = accTable.add(theme.button(" + Add Row ")).expandX().widget();
        addEmpty.action = () -> {
            accList.add("||/login ");
            accounts.set(accList);
            fillWidget(theme, parent);
        };

        WButton autoAdd = accTable.add(theme.button(" + Current Data ")).expandX().widget();
        autoAdd.action = () -> {
            String currentHost = resolveCurrentHost(null);
            String nick = resolveCurrentNick();
            String serverName = currentHost;
            if (recordAliases.get()) {
                String groupName = findGroupNameForHost(currentHost);
                if (groupName != null) {
                    serverName = groupName;
                }
            }
            accList.add(serverName + "|" + nick + "|/login ");
            accounts.set(accList);
            fillWidget(theme, parent);
        };
        accTable.row();

        // --- SECTION 2: SERVER GROUPS (with collapsible arrow) ---
        WSection grpSection = parent.add(theme.section("SERVER GROUPS (ALIASES)", groupsExpanded)).expandX().widget();
        grpSection.action = () -> groupsExpanded = grpSection.isExpanded();
        WTable grpTable = grpSection.add(theme.table()).expandX().widget();

        List<String> grpList = new ArrayList<>(serverGroups.get());

        if (!grpList.isEmpty()) {
            grpTable.add(theme.label("GROUP NAME"));
            grpTable.add(theme.label("SERVERS (COMMA-SEPARATED)"));
            grpTable.add(theme.label(""));
            grpTable.row();
        }

        for (int i = 0; i < grpList.size(); i++) {
            int index = i;
            String[] data = grpList.get(i).split("\\|", 2);

            String valName = data.length > 0 ? data[0] : "";
            String valServers = data.length > 1 ? data[1] : "";

            WTextBox wName = grpTable.add(theme.textBox(valName)).minWidth(120).widget();
            WTextBox wServers = grpTable.add(theme.textBox(valServers)).minWidth(240).expandX().widget();

            Runnable updateGrp = () -> {
                grpList.set(index, wName.get().trim() + "|" + wServers.get().trim());
                serverGroups.set(grpList);
            };

            wName.action = updateGrp;
            wServers.action = updateGrp;

            WButton del = grpTable.add(theme.button(" × ")).widget();
            del.action = () -> {
                grpList.remove(index);
                serverGroups.set(grpList);
                fillWidget(theme, parent);
            };
            grpTable.row();
        }

        grpTable.add(theme.label(""));
        grpTable.row();

        WButton addGrp = grpTable.add(theme.button(" + Add Group ")).expandX().widget();
        addGrp.action = () -> {
            grpList.add("group_name|server1.ru, server2.ru");
            serverGroups.set(grpList);
            fillWidget(theme, parent);
        };
        grpTable.row();
    }

    @EventHandler
    private void onMessageReceive(ReceiveMessageEvent event) {
        String msg = event.getMessage().getString().replaceAll("§[0-9a-fk-or]", "").toLowerCase().trim();

        boolean foundLogin = false;
        boolean foundRegister = false;

        for (String key : REGISTER_KEYWORDS) {
            if (msg.contains(key)) {
                foundRegister = true;
                break;
            }
        }

        if (!foundRegister) {
            for (String key : LOGIN_KEYWORDS) {
                if (msg.contains(key)) {
                    foundLogin = true;
                    break;
                }
            }
        }

        boolean hasContext = false;
        for (String context : AUTH_PROMPT_INDICATORS) {
            if (msg.contains(context)) {
                hasContext = true;
                break;
            }
        }

        boolean isCommand = msg.contains("/") || msg.contains("!");

        if ((foundRegister || foundLogin) && (hasContext || isCommand)) {
            inLobby = true;
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ShowDialogS2CPacket packet) {
            handleShowDialog(event, packet);
        }
    }

    private void handleShowDialog(PacketEvent.Receive event, ShowDialogS2CPacket packet) {
        if (packet.dialog() == null) return;
        Dialog dialog;
        try {
            dialog = packet.dialog().value();
        } catch (Exception e) {
            AutoLoginAddon.LOG.error("[AutoLogin++] Failed to get dialog value: {}", e.getMessage());
            return;
        }
        if (dialog == null) return;

        DialogCommonData common = dialog.common();
        if (common == null) return;

        String titleStr = common.title() != null ? common.title().getString() : "";
        AutoLoginAddon.LOG.info("[AutoLogin++] Received ShowDialogS2CPacket. Title: '{}'", titleStr);

        String passwordKey = "password";
        if (common.inputs() != null && !common.inputs().isEmpty()) {
            for (DialogInput input : common.inputs()) {
                String k = input.key();
                if (k.toLowerCase().contains("pass")) {
                    passwordKey = k;
                    break;
                }
            }
            if (passwordKey.equals("password") && !common.inputs().isEmpty()) {
                passwordKey = common.inputs().get(0).key();
            }
        }

        String titleLower = titleStr.toLowerCase();
        boolean isAuth = titleLower.contains("вход") ||
            titleLower.contains("login") ||
            titleLower.contains("авториз") ||
            titleLower.contains("auth") ||
            titleLower.contains("пароль");

        StringBuilder bodyText = new StringBuilder();
        if (common.body() != null) {
            for (DialogBody b : common.body()) {
                bodyText.append(b.toString()).append(" ");
            }
        }
        String bodyStr = bodyText.toString().toLowerCase();
        if (bodyStr.contains("пароль") || bodyStr.contains("password") || bodyStr.contains("введи")) {
            isAuth = true;
        }

        if (!isAuth) {
            AutoLoginAddon.LOG.info("[AutoLogin++] Dialog is not an auth dialog, ignoring.");
            return;
        }

        // Connection session state check:
        if (event.connection != lastConnection) {
            lastConnection = event.connection;
            dialogAttemptSent = false;
        }

        // If the server resent ShowDialogS2CPacket after a manual submission:
        // that means the submitted password was rejected! Discard pending credentials.
        if (pendingCommand != null) {
            AutoLoginAddon.LOG.warn("[AutoLogin++] Server resent ShowDialogS2CPacket after manual submission. Discarding rejected credentials for '{}'", pendingNick);
            pendingCommand = null;
            pendingHost = null;
            pendingNick = null;
        }

        // If we already sent an auto-login attempt on this connection,
        // this second dialog means the configured password was INCORRECT!
        if (dialogAttemptSent) {
            AutoLoginAddon.LOG.warn("[AutoLogin++] Second ShowDialog received on same connection. Previous auto-login attempt failed (incorrect password). Leaving dialog open for user manual entry.");
            return; // DO NOT SEND PACKET, DO NOT CANCEL DIALOG
        }

        // Strict account lookup: only proceed if this server and nickname match!
        String passOrCmd = findMatchingPasswordOrCommand(event.connection);
        if (passOrCmd == null) {
            AutoLoginAddon.LOG.info("[AutoLogin++] Auth dialog received, but NO MATCHING ACCOUNT found for this server/nick. Leaving dialog for manual entry.");
            return;
        }

        // Matching account confirmed! Cancel incoming ShowDialog packet so dialog never opens.
        if (hideDialog.get()) {
            event.cancel();
            AutoLoginAddon.LOG.info("[AutoLogin++] Cancelled incoming ShowDialogS2CPacket to suppress dialog screen.");
        }

        String cleanPassword = extractPassword(passOrCmd);

        List<DialogActionButtonData> actionButtons = new ArrayList<>();
        if (dialog instanceof MultiActionDialog multiAction) {
            actionButtons.addAll(multiAction.actions());
            multiAction.exitAction().ifPresent(actionButtons::add);
        } else if (dialog instanceof SimpleDialog simpleDialog) {
            actionButtons.addAll(simpleDialog.getButtons());
        }

        Identifier targetActionId = null;
        Optional<NbtCompound> additions = Optional.empty();

        // 1. Look for non-cancel action button
        for (DialogActionButtonData btn : actionButtons) {
            if (btn.action().isPresent() && btn.action().get() instanceof DynamicCustomDialogAction dynamicCustom) {
                Identifier id = dynamicCustom.id();
                String path = id.getPath().toLowerCase();
                String idStr = id.toString().toLowerCase();
                String label = btn.data() != null && btn.data().label() != null ? btn.data().label().getString().toLowerCase() : "";

                if (idStr.endsWith("/cancel") || path.contains("cancel") || path.contains("close") || path.contains("exit") ||
                    label.contains("отключ") || label.contains("cancel") || label.contains("exit") || label.contains("выйти")) {
                    continue;
                }
                targetActionId = id;
                additions = dynamicCustom.additions();
                break;
            }
        }

        // 2. Fallback: if not found by name, pick the first dynamic action
        if (targetActionId == null) {
            for (DialogActionButtonData btn : actionButtons) {
                if (btn.action().isPresent() && btn.action().get() instanceof DynamicCustomDialogAction dynamicCustom) {
                    targetActionId = dynamicCustom.id();
                    additions = dynamicCustom.additions();
                    AutoLoginAddon.LOG.info("[AutoLogin++] Fallback to first available action button: {}", targetActionId);
                    break;
                }
            }
        }

        if (targetActionId == null) {
            AutoLoginAddon.LOG.warn("[AutoLogin++] No suitable action button found in dialog!");
            return;
        }

        NbtCompound payload = additions.map(NbtCompound::copy).orElseGet(NbtCompound::new);
        payload.putString(passwordKey, cleanPassword);

        CustomClickActionC2SPacket responsePacket = new CustomClickActionC2SPacket(targetActionId, Optional.of(payload));

        inLobby = false;
        timer = 0;
        dialogAttemptSent = true;

        AutoLoginAddon.LOG.info("[AutoLogin++] Sending immediate CustomClickActionC2SPacket for action: {}", targetActionId);
        sendDialogPacket(responsePacket, event.connection);
    }

    private void sendDialogPacket(CustomClickActionC2SPacket packet, ClientConnection connection) {
        AutoLoginAddon.LOG.info("[AutoLogin++] sendDialogPacket executing for action: {}", packet.id());
        boolean sent = false;
        sendingOurPacket = true;
        try {
            if (connection != null && connection.isOpen()) {
                connection.send(packet);
                AutoLoginAddon.LOG.info("[AutoLogin++] Sent CustomClickActionC2SPacket via ClientConnection.");
                sent = true;
            } else if (mc.getNetworkHandler() != null) {
                mc.getNetworkHandler().sendPacket(packet);
                AutoLoginAddon.LOG.info("[AutoLogin++] Sent CustomClickActionC2SPacket via ClientPlayNetworkHandler.");
                sent = true;
            } else {
                AutoLoginAddon.LOG.warn("[AutoLogin++] Failed to send packet: no open connection or network handler!");
            }
        } finally {
            sendingOurPacket = false;
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof CommandExecutionC2SPacket packet) {
            String cmd = packet.command().toLowerCase();
            if (cmd.startsWith("login ") || cmd.startsWith("l ") || cmd.startsWith("reg ") || cmd.startsWith("register ")) {
                inLobby = false;
                timer = 0;
            }
            checkAndRecordFromChat(packet.command(), event.connection);
        } else if (event.packet instanceof ChatMessageC2SPacket packet) {
            String msg = packet.chatMessage().toLowerCase();
            if (msg.startsWith("/login ") || msg.startsWith("/l ") || msg.startsWith("/reg ") || msg.startsWith("/register ")) {
                inLobby = false;
                timer = 0;
            }
            checkAndRecordFromChat(packet.chatMessage(), event.connection);
        } else if (event.packet instanceof CustomClickActionC2SPacket packet) {
            inLobby = false;
            timer = 0;
            if (!sendingOurPacket) {
                stageRecordFromDialog(packet, event.connection);
            }
        }
    }

    private void checkAndRecordFromChat(String rawCmd, ClientConnection connection) {
        if (!autoRecord.get() || rawCmd == null) return;
        String cmd = rawCmd.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).trim();

        String lower = cmd.toLowerCase();
        String prefix = null;
        String savedPrefix = null;

        if (lower.startsWith("register ") || lower.startsWith("регистрация ")) {
            prefix = lower.startsWith("register ") ? "register " : "регистрация ";
            savedPrefix = "/login ";
        } else if (lower.startsWith("reg ") || lower.startsWith("рег ")) {
            prefix = lower.startsWith("reg ") ? "reg " : "рег ";
            savedPrefix = "/l ";
        } else if (lower.startsWith("login ") || lower.startsWith("логин ")) {
            prefix = lower.startsWith("login ") ? "login " : "логин ";
            savedPrefix = "/login ";
        } else if (lower.startsWith("l ") || lower.startsWith("л ")) {
            prefix = lower.startsWith("l ") ? "l " : "л ";
            savedPrefix = "/l ";
        }

        if (prefix == null) return;

        String remainder = cmd.substring(prefix.length()).trim();
        if (remainder.isEmpty()) return;

        // Extract first token for passwords with confirmations (e.g. /reg pass pass)
        String password = remainder.split("\\s+")[0].trim();
        if (password.isEmpty()) return;

        String finalCommand = savedPrefix + password;
        String host = resolveCurrentHost(connection);
        String nick = resolveCurrentNick();

        recordAccount(host, nick, finalCommand);
    }

    private void stageRecordFromDialog(CustomClickActionC2SPacket packet, ClientConnection connection) {
        if (!autoRecord.get() || packet == null) return;
        if (packet.payload().isPresent() && packet.payload().get() instanceof NbtCompound payload) {
            String capturedPass = null;
            if (payload.contains("password")) {
                capturedPass = payload.getString("password").orElse("");
            } else {
                for (String key : payload.getKeys()) {
                    if (key.toLowerCase().contains("pass")) {
                        capturedPass = payload.getString(key).orElse("");
                        break;
                    }
                }
            }
            if (capturedPass != null && !capturedPass.trim().isEmpty()) {
                pendingHost = resolveCurrentHost(connection);
                pendingNick = resolveCurrentNick();
                // Default to /login for DialogUi as requested
                pendingCommand = "/login " + capturedPass.trim();
                AutoLoginAddon.LOG.info("[AutoLogin++] Staged pending DialogUi credentials for host='{}', nick='{}'",
                    pendingHost, pendingNick);
            }
        }
    }

    private synchronized void recordAccount(String rawServer, String nick, String savedCommand) {
        if (nick == null || nick.trim().isEmpty()) return;
        if (savedCommand == null || savedCommand.trim().isEmpty()) return;

        nick = nick.trim();
        savedCommand = savedCommand.trim();

        String serverKey = cleanHost(rawServer);
        if (serverKey.isEmpty()) {
            serverKey = resolveCurrentHost(null);
        }

        if (recordAliases.get()) {
            String groupName = findGroupNameForHost(serverKey);
            if (groupName != null) {
                serverKey = groupName;
            }
        }

        List<String> list = new ArrayList<>(accounts.get());
        boolean updated = false;

        for (int i = 0; i < list.size(); i++) {
            String[] data = list.get(i).split("\\|", 3);
            if (data.length < 3) continue;

            String entryServer = data[0].trim();
            String entryNick = data[1].trim();

            if (entryServer.equalsIgnoreCase(serverKey) && entryNick.equalsIgnoreCase(nick)) {
                list.set(i, serverKey + "|" + nick + "|" + savedCommand);
                updated = true;
                break;
            }
        }

        if (!updated) {
            list.add(serverKey + "|" + nick + "|" + savedCommand);
        }

        accounts.set(list);

        AutoLoginAddon.LOG.info("[AutoLogin++] Auto-recorded credentials: server='{}', nick='{}', cmd='{}'",
            serverKey, nick, savedCommand);

        if (currentWidget != null && currentTheme != null) {
            mc.execute(() -> {
                if (currentWidget != null && currentTheme != null) {
                    fillWidget(currentTheme, currentWidget);
                }
            });
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            inLobby = false;
            timer = 0;
            messageQueue.clear();
            return;
        }

        // If player has successfully entered world, commit any pending DialogUi credentials!
        if (pendingCommand != null) {
            AutoLoginAddon.LOG.info("[AutoLogin++] Player joined world successfully. Committing pending credentials for host='{}', nick='{}'", pendingHost, pendingNick);
            recordAccount(pendingHost, pendingNick, pendingCommand);
            pendingCommand = null;
            pendingHost = null;
            pendingNick = null;
        }

        if (inLobby) {
            timer++;
            if (timer >= delay.get()) {
                executeChatLogin();
                inLobby = false;
                timer = 0;
            }
        }

        if (!messageQueue.isEmpty()) {
            ChatUtils.sendPlayerMsg(messageQueue.removeFirst());
        }
    }

    private void executeChatLogin() {
        String passOrCmd = findMatchingPasswordOrCommand(null);
        if (passOrCmd != null) {
            String cmd = formatChatCommand(passOrCmd);
            messageQueue.add(cmd);
        }
    }

    private String findMatchingPasswordOrCommand(ClientConnection connection) {
        List<String> list = accounts.get();
        if (list == null || list.isEmpty()) {
            AutoLoginAddon.LOG.warn("[AutoLogin++] No accounts configured in Auto Login++!");
            return null;
        }

        String currentHost = resolveCurrentHost(connection);
        String currentNick = resolveCurrentNick();

        AutoLoginAddon.LOG.info("[AutoLogin++] Finding matching account: currentHost='{}', currentNick='{}'",
            currentHost, currentNick);

        for (String entry : list) {
            String[] data = entry.split("\\|", 3);
            if (data.length < 3) continue;

            String entryServer = data[0].trim();
            String entryNick = data[1].trim();
            String entryPass = data[2].trim();

            if (entryPass.isEmpty()) continue;

            // Server matching: direct host match OR server group match
            if (!isHostMatchingGroupOrDirect(entryServer, currentHost)) {
                continue;
            }

            // Nickname matching: exact match if nick is specified
            if (!entryNick.isEmpty()) {
                if (currentNick.isEmpty() || !entryNick.equalsIgnoreCase(currentNick)) {
                    continue;
                }
            }

            AutoLoginAddon.LOG.info("[AutoLogin++] Matched account: server='{}', nick='{}'", entryServer, entryNick);
            return entryPass;
        }

        AutoLoginAddon.LOG.info("[AutoLogin++] No matching account found for currentHost='{}', currentNick='{}'.", currentHost, currentNick);
        return null;
    }

    private boolean isHostMatchingGroupOrDirect(String entryServer, String currentHost) {
        if (entryServer.isEmpty()) return true; // Wildcard
        String cleanEntry = cleanHost(entryServer);
        String cleanCurrent = cleanHost(currentHost);

        // 1. Direct match
        if (cleanEntry.equalsIgnoreCase(cleanCurrent)) {
            return true;
        }

        // 2. Group match: entryServer might be a defined group name
        Map<String, List<String>> groups = getGroupsMap();
        List<String> servers = groups.get(cleanEntry);
        if (servers != null && servers.contains(cleanCurrent)) {
            return true;
        }

        return false;
    }

    private Map<String, List<String>> getGroupsMap() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String entry : serverGroups.get()) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length < 2) continue;
            String groupName = parts[0].trim().toLowerCase();
            if (groupName.isEmpty()) continue;

            String[] hosts = parts[1].split(",");
            List<String> list = new ArrayList<>();
            for (String h : hosts) {
                String clean = cleanHost(h);
                if (!clean.isEmpty()) list.add(clean);
            }
            map.put(groupName, list);
        }
        return map;
    }

    private String findGroupNameForHost(String host) {
        if (host == null || host.isEmpty()) return null;
        String clean = cleanHost(host);
        Map<String, List<String>> map = getGroupsMap();
        for (Map.Entry<String, List<String>> entry : map.entrySet()) {
            if (entry.getValue().contains(clean)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private String resolveCurrentHost(ClientConnection connection) {
        if (connection != null && connection.getAddress() instanceof InetSocketAddress inet) {
            String host = inet.getHostString();
            if (host != null && !host.isEmpty()) {
                String clean = cleanHost(host);
                if (!clean.isEmpty()) {
                    lastActiveHost = clean;
                    return clean;
                }
            }
        }
        if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null && !mc.getCurrentServerEntry().address.isEmpty()) {
            String clean = cleanHost(mc.getCurrentServerEntry().address);
            if (!clean.isEmpty()) {
                lastActiveHost = clean;
                return clean;
            }
        }
        if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getServerInfo() != null && mc.getNetworkHandler().getServerInfo().address != null) {
            String clean = cleanHost(mc.getNetworkHandler().getServerInfo().address);
            if (!clean.isEmpty()) {
                lastActiveHost = clean;
                return clean;
            }
        }
        String worldName = Utils.getWorldName();
        if (worldName != null && !worldName.isEmpty()) {
            String clean = cleanHost(worldName);
            if (!clean.isEmpty()) {
                lastActiveHost = clean;
                return clean;
            }
        }
        return lastActiveHost;
    }

    private String resolveCurrentNick() {
        if (mc.player != null && mc.player.getGameProfile() != null && mc.player.getGameProfile().name() != null) {
            String nick = mc.player.getGameProfile().name().trim();
            if (!nick.isEmpty()) return nick;
        }
        if (mc.getSession() != null && mc.getSession().getUsername() != null) {
            return mc.getSession().getUsername().trim();
        }
        return "";
    }

    private String cleanHost(String host) {
        if (host == null) return "";
        host = host.trim().toLowerCase();
        if (host.startsWith("http://")) host = host.substring(7);
        if (host.startsWith("https://")) host = host.substring(8);
        int slash = host.indexOf('/');
        if (slash != -1) host = host.substring(0, slash);
        int colon = host.lastIndexOf(':');
        if (colon != -1) {
            host = host.substring(0, colon);
        }
        return host.trim();
    }

    private String extractPassword(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();

        for (String prefix : new String[]{"/login ", "/l ", "/reg ", "/register ", "/auth "}) {
            if (lower.startsWith(prefix)) {
                String remainder = trimmed.substring(prefix.length()).trim();
                int spaceIdx = remainder.indexOf(' ');
                return spaceIdx > 0 ? remainder.substring(0, spaceIdx) : remainder;
            }
        }
        return trimmed;
    }

    private String formatChatCommand(String input) {
        if (input == null) return "";
        String trimmed = input.trim();
        if (trimmed.startsWith("/")) return trimmed;
        return "/l " + trimmed;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetState();
    }

    @Override
    public void onActivate() {
        AutoLoginAddon.LOG.info("[AutoLogin++] Module activated! (runInMainMenu = {})", runInMainMenu);
        resetState();
    }

    @Override
    public void onDeactivate() {
        AutoLoginAddon.LOG.info("[AutoLogin++] Module deactivated.");
        resetState();
    }

    private void resetState() {
        timer = 0;
        inLobby = false;
        messageQueue.clear();
        lastConnection = null;
        dialogAttemptSent = false;
        sendingOurPacket = false;
        pendingHost = null;
        pendingNick = null;
        pendingCommand = null;
    }
}
