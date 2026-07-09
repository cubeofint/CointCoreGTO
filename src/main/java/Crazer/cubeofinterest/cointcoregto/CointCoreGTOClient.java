package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public class CointCoreGTOClient {
    public static final int CLIENT_CHAT_LINE_LIMIT = 5000;
    private static final int NON_CUBECHAT_HISTORY_LIMIT = 500;
    private static final Deque<Component> NON_CUBECHAT_HISTORY = new ArrayDeque<>();

    public static int getClientChatLineLimit() {
        return CLIENT_CHAT_LINE_LIMIT;
    }

    private static final int PANEL_PADDING_X = 6;
    private static final int PANEL_PADDING_Y = 4;
    private static final int BUTTON_GAP = 6;
    private static final int CHAT_LEFT = 4;

    private static final ButtonArea allButton = new ButtonArea();
    private static final ButtonArea localButton = new ButtonArea();
    private static final ButtonArea globalButton = new ButtonArea();
    private static final ButtonArea privateButton = new ButtonArea();

    private static long lastChatButtonClickMillis = 0L;

    private static final long MENTION_SOUND_COOLDOWN_MILLIS = 1500L;

    private static long lastMentionSoundMillis = 0L;

    @SubscribeEvent
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) {
            return;
        }

        String text = message.getString();
        if (text == null || text.isBlank()) {
            return;
        }

        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (CointCoreGTO.shouldHideJoinLeaveMessages() && isVanillaJoinMessage(lower)) {
            event.setCanceled(true);
            return;
        }

        playMentionSoundIfNeeded(text);

        if (!isCointCoreGTOText(text)) {
            rememberNonCointCoreGTOMessage(message);
        }
    }

    private static void playMentionSoundIfNeeded(String rawText) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.player == null) {
            return;
        }

        if (rawText == null || rawText.isBlank()) {
            return;
        }

        String playerName = minecraft.player.getGameProfile().getName();
        if (playerName == null || playerName.isBlank()) {
            return;
        }

        String cleanText = stripMinecraftFormatting(rawText);
        if (cleanText.isBlank()) {
            return;
        }

        if (isProbablyOwnNormalChatMessage(cleanText, playerName)) {
            return;
        }

        boolean privateMessage = cleanText.contains("[PM]")
                || cleanText.contains("[ЛС]")
                || cleanText.toLowerCase(java.util.Locale.ROOT).contains("private");

        if (privateMessage && isOutgoingPrivateMessage(cleanText)) {
            return;
        }

        boolean incomingPrivateMessage = privateMessage && isIncomingPrivateMessage(cleanText);

        String messageBody = getMessageBodyAfterColon(cleanText);
        boolean mentionedByName = containsIgnoreCase(messageBody, playerName);

        if (!incomingPrivateMessage && !mentionedByName) {
            return;
        }

        playMentionSound();
    }

    private static boolean isOutgoingPrivateMessage(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) {
            return false;
        }

        String lowered = cleanText.toLowerCase(java.util.Locale.ROOT);

        return lowered.contains("вы ->")
                || lowered.contains("you ->")
                || lowered.contains("я ->")
                || lowered.contains("me ->");
    }

    private static boolean isIncomingPrivateMessage(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) {
            return false;
        }

        String lowered = cleanText.toLowerCase(java.util.Locale.ROOT);

        return lowered.contains("-> вы")
                || lowered.contains("-> you")
                || lowered.contains("-> мне")
                || lowered.contains("-> me");
    }

    private static void playMentionSound() {
        long now = System.currentTimeMillis();

        if (now - lastMentionSoundMillis < MENTION_SOUND_COOLDOWN_MILLIS) {
            return;
        }

        lastMentionSoundMillis = now;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getSoundManager() == null) {
            return;
        }
        minecraft.execute(() -> minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_PLING.value(),
                        1.0F,
                        1.0F
                )
        ));
    }

    private static boolean isProbablyOwnNormalChatMessage(String cleanText, String playerName) {
        if (cleanText == null || playerName == null || playerName.isBlank()) {
            return false;
        }

        int colonIndex = cleanText.lastIndexOf(':');
        if (colonIndex < 0) {
            return false;
        }

        String beforeColon = cleanText.substring(0, colonIndex).trim();

        if (beforeColon.contains("->") || beforeColon.contains("→")) {
            return false;
        }

        String loweredBeforeColon = beforeColon.toLowerCase(java.util.Locale.ROOT);
        String loweredPlayerName = playerName.toLowerCase(java.util.Locale.ROOT);

        return loweredBeforeColon.endsWith(loweredPlayerName);
    }

    private static String getMessageBodyAfterColon(String cleanText) {
        if (cleanText == null || cleanText.isBlank()) {
            return "";
        }

        int colonIndex = cleanText.lastIndexOf(':');
        if (colonIndex < 0 || colonIndex + 1 >= cleanText.length()) {
            return cleanText;
        }

        return cleanText.substring(colonIndex + 1).trim();
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        if (text == null || needle == null || needle.isBlank()) {
            return false;
        }

        return text.toLowerCase(java.util.Locale.ROOT)
                .contains(needle.toLowerCase(java.util.Locale.ROOT));
    }

    private static String stripMinecraftFormatting(String text) {
        if (text == null) {
            return "";
        }

        return text.replaceAll("§.", "");
    }

    private static boolean isVanillaJoinMessage(String lower) {
        return lower.contains("присоединился к игре")
                || lower.contains("присоединилась к игре")
                || lower.contains("joined the game")
                || lower.contains("покинул игру")
                || lower.contains("покинула игру")
                || lower.contains("left the game");
    }

    @SubscribeEvent
    public static void onRenderChatScreen(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);

        String allText = "[ALL]";
        String localText = "[L]";
        String globalText = "[G]";
        String privateText = "[PM]";

        int buttonsWidth = mc.font.width(allText)
                + BUTTON_GAP
                + mc.font.width(localText)
                + BUTTON_GAP
                + mc.font.width(globalText)
                + BUTTON_GAP
                + mc.font.width(privateText);

        int panelWidth = buttonsWidth + PANEL_PADDING_X * 2;
        int panelHeight = mc.font.lineHeight + PANEL_PADDING_Y * 2;
        int chatHeight = mc.gui.getChat().getHeight();
        int panelX = CHAT_LEFT;
        int panelY = event.getScreen().height - 40 - chatHeight - 16;
        int x = panelX + PANEL_PADDING_X;
        int y = panelY + PANEL_PADDING_Y;

        graphics.fill(
                panelX,
                panelY,
                panelX + panelWidth,
                panelY + panelHeight,
                0xDD000000
        );

        x = drawButton(graphics, mc, x, y, allText, 0xFFFFFFFF, allButton);
        x += BUTTON_GAP;
        x = drawButton(graphics, mc, x, y, localText, 0xFF55FF55, localButton);
        x += BUTTON_GAP;
        x = drawButton(graphics, mc, x, y, globalText, 0xFFFFAA00, globalButton);
        x += BUTTON_GAP;
        drawButton(graphics, mc, x, y, privateText, 0xFFFF55FF, privateButton);

        graphics.pose().popPose();
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.connection == null) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        long now = System.currentTimeMillis();
        if (now - lastChatButtonClickMillis < 200L) {
            event.setCanceled(true);
            return;
        }

        if (allButton.contains(mouseX, mouseY)) {
            lastChatButtonClickMillis = now;
            mc.player.connection.sendCommand("chat all");
            event.setCanceled(true);
            return;
        }

        if (localButton.contains(mouseX, mouseY)) {
            lastChatButtonClickMillis = now;
            mc.player.connection.sendCommand("chat local");
            event.setCanceled(true);
            return;
        }

        if (globalButton.contains(mouseX, mouseY)) {
            lastChatButtonClickMillis = now;
            mc.player.connection.sendCommand("chat global");
            event.setCanceled(true);
            return;
        }

        if (privateButton.contains(mouseX, mouseY)) {
            lastChatButtonClickMillis = now;
            mc.player.connection.sendCommand("chat pm");
            event.setCanceled(true);
        }
    }

    public static void clearChatMessages() {
        clearChatMessages(true);
    }

    public static void clearChatMessages(boolean keepSystemMessages) {
        CointCoreGTOItemIconOverlay.clearIcons();

        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) {
            return;
        }

        ChatComponent chat = mc.gui.getChat();
        if (chat == null) {
            return;
        }

        if (!keepSystemMessages) {
            chat.clearMessages(false);
            return;
        }

        if (clearOnlyCointCoreGTOMessages(chat)) {
            return;
        }

        List<Component> nonCubeMessages = snapshotNonCointCoreGTOMessages();
        chat.clearMessages(false);

        for (Component component : nonCubeMessages) {
            if (component != null) {
                chat.addMessage(component);
            }
        }
    }

    private static void rememberNonCointCoreGTOMessage(Component message) {
        if (message == null) {
            return;
        }

        synchronized (NON_CUBECHAT_HISTORY) {
            NON_CUBECHAT_HISTORY.addLast(message.copy());

            while (NON_CUBECHAT_HISTORY.size() > NON_CUBECHAT_HISTORY_LIMIT) {
                NON_CUBECHAT_HISTORY.removeFirst();
            }
        }
    }

    private static List<Component> snapshotNonCointCoreGTOMessages() {
        synchronized (NON_CUBECHAT_HISTORY) {
            return new ArrayList<>(NON_CUBECHAT_HISTORY);
        }
    }

    private static boolean clearOnlyCointCoreGTOMessages(ChatComponent chat) {
        try {
            Field allMessagesField = findField(ChatComponent.class, "allMessages", "f_93761_");
            if (allMessagesField == null) {
                return false;
            }
            allMessagesField.setAccessible(true);

            Object value = allMessagesField.get(chat);
            if (!(value instanceof List<?> rawList)) {
                return false;
            }

            @SuppressWarnings("unchecked")
            List<Object> allMessages = (List<Object>) rawList;
            allMessages.removeIf(CointCoreGTOClient::isCointCoreGTOGuiMessage);

            Method refreshMethod = findMethod(ChatComponent.class, "refreshTrimmedMessages", "m_93796_");
            if (refreshMethod == null) {
                return false;
            }
            refreshMethod.setAccessible(true);
            refreshMethod.invoke(chat);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Method findMethod(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getDeclaredMethod(name);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static boolean isCointCoreGTOGuiMessage(Object message) {
        if (message == null) {
            return false;
        }

        try {
            Component component;

            if (message instanceof GuiMessage guiMessage) {
                component = guiMessage.content();
            } else {
                Object content = message.getClass().getMethod("content").invoke(message);
                if (!(content instanceof Component reflectedComponent)) {
                    return false;
                }
                component = reflectedComponent;
            }

            return isCointCoreGTOText(component.getString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isCointCoreGTOText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String clean = text.replaceAll("§.", "").trim();
        return clean.contains("[L]")
                || clean.contains("[G]")
                || clean.contains("[PM]")
                || clean.contains("[D]")
                || clean.startsWith("Теперь вы видите все чаты")
                || clean.startsWith("Теперь вы видите только локальный чат")
                || clean.startsWith("Теперь вы видите только глобальный чат")
                || clean.startsWith("Теперь вы видите только личные сообщения")
                || clean.startsWith("Время в чате включено")
                || clean.startsWith("Время в чате выключено")
                || clean.startsWith("Откройте чат")
                || clean.startsWith("Рядом никого нет")
                || clean.startsWith("Нельзя написать самому себе")
                || clean.startsWith("Некому ответить")
                || clean.startsWith("Игрок уже не в сети")
                || clean.startsWith("Введите сообщение после")
                || clean.startsWith("Ванильные личные сообщения отключены")
                || clean.startsWith("Личные сообщения отправляются так")
                || clean.startsWith("Команда /me отключена");
    }

    private static int drawButton(
            GuiGraphics graphics,
            Minecraft mc,
            int x,
            int y,
            String text,
            int color,
            ButtonArea area
    ) {
        int width = mc.font.width(text);
        graphics.drawString(mc.font, Component.literal(text), x, y, color, true);
        area.x1 = x;
        area.y1 = y;
        area.x2 = x + width;
        area.y2 = y + mc.font.lineHeight;
        return x + width;
    }

    private static class ButtonArea {
        int x1;
        int y1;
        int x2;
        int y2;

        boolean contains(double x, double y) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}