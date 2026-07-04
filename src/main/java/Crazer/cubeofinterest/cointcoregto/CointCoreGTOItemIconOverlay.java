package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOItemIconOverlay {
    private static final int MAX_LINES = 500;
    private static final int MAX_PENDING_ICONS = 300;

    /*
     * В закрытом чате Minecraft сам скрывает сообщения через несколько секунд.
     * Поэтому иконку тоже показываем только недолго.
     */
    private static final long CLOSED_CHAT_VISIBLE_MILLIS = 9 * 1000L;

    /*
     * Но историю держим дольше, чтобы при открытии чата и после переключения вкладок
     * иконки не пропадали окончательно.
     */
    private static final long STORED_LINE_TTL_MILLIS = 10 * 60 * 1000L;
    private static final long PENDING_ICON_TTL_MILLIS = 10 * 60 * 1000L;

    private static final Deque<IconChatLine> RECENT_LINES = new ArrayDeque<>();
    private static final Map<String, PendingIcon> PENDING_ICONS = new HashMap<>();

    private CointCoreGTOItemIconOverlay() {
    }

    public static void queueIcon(ItemStack stack, String prefixText, String itemText) {
        if (stack == null || stack.isEmpty() || itemText == null || itemText.isBlank()) {
            return;
        }

        String cleanItemText = normalize(itemText);
        if (!isItemToken(cleanItemText)) {
            return;
        }

        rememberPendingIcon(cleanItemText, stack);
        attachStackToRecentLine(cleanItemText, stack);
    }

    public static void clearIcons() {
        synchronized (RECENT_LINES) {
            RECENT_LINES.clear();
        }

        /*
         * PENDING_ICONS специально НЕ чистим.
         * При переключении [ALL]/[L]/[G]/[PM] чат очищается и история replay'ится заново.
         * Если replay придёт обычным текстом без hover/packet, этот кэш позволит вернуть иконку.
         */
    }

    public static void clearAllIconCache() {
        synchronized (RECENT_LINES) {
            RECENT_LINES.clear();
        }

        synchronized (PENDING_ICONS) {
            PENDING_ICONS.clear();
        }
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) {
            return;
        }

        String text = normalize(message.getString());
        if (text.isBlank()) {
            return;
        }

        if (shouldIgnoreChatLine(text)) {
            return;
        }

        String itemToken = extractLastBracketToken(text);

        if (!isItemToken(itemToken)) {
            rememberLine(text, "", ItemStack.EMPTY);
            return;
        }

        ItemStack stack = findItemStackInComponent(message);

        if (stack.isEmpty()) {
            stack = getPendingIconStack(itemToken);
        }

        rememberLine(text, itemToken, stack);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc == null || mc.options == null || mc.options.hideGui || mc.font == null || mc.gui == null) {
            return;
        }

        boolean chatOpen = mc.screen instanceof ChatScreen;

        List<IconChatLine> lines = snapshotLines();
        if (lines.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = 9;
        int chatBottom = screenHeight - 40;

        int chatHeight = 180;
        try {
            chatHeight = Math.max(1, mc.gui.getChat().getHeight());
        } catch (Throwable ignored) {
        }

        int maxVisibleLines = Math.max(1, chatHeight / lineHeight);
        GuiGraphics graphics = event.getGuiGraphics();

        int visibleIndex = 0;

        for (IconChatLine line : lines) {
            if (line == null) {
                continue;
            }

            String lineText = line.text();
            if (lineText == null || lineText.isBlank()) {
                continue;
            }

            String clean = normalize(lineText);

            if (shouldIgnoreChatLine(clean)) {
                continue;
            }

            /*
             * Если чат закрыт, рисуем только свежие строки.
             * Если чат открыт, рисуем историю, как обычный Minecraft chat.
             */
            if (!chatOpen && now - line.createdMillis() > CLOSED_CHAT_VISIBLE_MILLIS) {
                continue;
            }

            if (visibleIndex >= maxVisibleLines) {
                break;
            }

            if (isItemToken(line.itemToken()) && !line.stack().isEmpty()) {
                int itemIndex = lineText.lastIndexOf(line.itemToken());

                if (itemIndex >= 0) {
                    String beforeItem = lineText.substring(0, itemIndex);
                    int beforeWidth = mc.font.width(beforeItem);

                    /*
                     * Под это в CointCoreGTO.java должно быть 4 пробела перед item component:
                     * fullPrefix + "    "
                     *
                     * -10 чуть правее, чем предыдущий -12.
                     */
                    int iconX = 4 + beforeWidth - 10;
                    int textY = chatBottom - (visibleIndex + 1) * lineHeight;
                    int iconY = textY + Math.max(0, (lineHeight - 8) / 2);

                    drawSmallItem(graphics, line.stack(), iconX, iconY);
                }
            }

            visibleIndex++;
        }
    }

    private static void rememberLine(String text, String itemToken, ItemStack stack) {
        long now = System.currentTimeMillis();

        synchronized (RECENT_LINES) {
            RECENT_LINES.addFirst(new IconChatLine(
                    text,
                    itemToken == null ? "" : itemToken,
                    stack == null ? ItemStack.EMPTY : stack.copy(),
                    now
            ));

            while (RECENT_LINES.size() > MAX_LINES) {
                RECENT_LINES.removeLast();
            }

            cleanupLines(now);
        }
    }

    private static void rememberPendingIcon(String itemToken, ItemStack stack) {
        if (!isItemToken(itemToken) || stack == null || stack.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        synchronized (PENDING_ICONS) {
            PENDING_ICONS.put(itemToken, new PendingIcon(itemToken, stack.copy(), now));
            cleanupPendingIcons(now);

            if (PENDING_ICONS.size() > MAX_PENDING_ICONS) {
                Iterator<String> iterator = PENDING_ICONS.keySet().iterator();
                while (PENDING_ICONS.size() > MAX_PENDING_ICONS && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    private static ItemStack getPendingIconStack(String itemToken) {
        if (!isItemToken(itemToken)) {
            return ItemStack.EMPTY;
        }

        long now = System.currentTimeMillis();

        synchronized (PENDING_ICONS) {
            cleanupPendingIcons(now);

            PendingIcon icon = PENDING_ICONS.get(itemToken);
            if (icon == null || icon.stack().isEmpty()) {
                return ItemStack.EMPTY;
            }

            return icon.stack().copy();
        }
    }

    private static void attachStackToRecentLine(String itemToken, ItemStack stack) {
        if (!isItemToken(itemToken) || stack == null || stack.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        synchronized (RECENT_LINES) {
            ArrayList<IconChatLine> rebuilt = new ArrayList<>();
            boolean attached = false;

            for (IconChatLine line : RECENT_LINES) {
                if (!attached
                        && line != null
                        && line.stack().isEmpty()
                        && itemToken.equals(line.itemToken())) {
                    rebuilt.add(new IconChatLine(
                            line.text(),
                            line.itemToken(),
                            stack.copy(),
                            line.createdMillis()
                    ));
                    attached = true;
                } else {
                    rebuilt.add(line);
                }
            }

            RECENT_LINES.clear();
            RECENT_LINES.addAll(rebuilt);
            cleanupLines(now);
        }
    }

    private static List<IconChatLine> snapshotLines() {
        long now = System.currentTimeMillis();

        synchronized (RECENT_LINES) {
            cleanupLines(now);
            return new ArrayList<>(RECENT_LINES);
        }
    }

    private static void cleanupLines(long now) {
        Iterator<IconChatLine> iterator = RECENT_LINES.iterator();

        while (iterator.hasNext()) {
            IconChatLine line = iterator.next();

            if (line == null || now - line.createdMillis() > STORED_LINE_TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static void cleanupPendingIcons(long now) {
        Iterator<Map.Entry<String, PendingIcon>> iterator = PENDING_ICONS.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, PendingIcon> entry = iterator.next();

            if (entry.getValue() == null || now - entry.getValue().createdMillis() > PENDING_ICON_TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static ItemStack findItemStackInComponent(Component component) {
        if (component == null) {
            return ItemStack.EMPTY;
        }

        ItemStack own = findItemStackInStyle(component.getStyle());
        if (!own.isEmpty()) {
            return own;
        }

        for (Component sibling : component.getSiblings()) {
            ItemStack nested = findItemStackInComponent(sibling);
            if (!nested.isEmpty()) {
                return nested;
            }
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack findItemStackInStyle(Style style) {
        if (style == null) {
            return ItemStack.EMPTY;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null) {
            return ItemStack.EMPTY;
        }

        try {
            HoverEvent.ItemStackInfo itemInfo = hoverEvent.getValue(HoverEvent.Action.SHOW_ITEM);
            if (itemInfo == null) {
                return ItemStack.EMPTY;
            }

            ItemStack stack = itemInfo.getItemStack();
            return stack == null ? ItemStack.EMPTY : stack.copy();
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static void drawSmallItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2000.0F);
        graphics.pose().scale(0.5F, 0.5F, 1.0F);
        graphics.renderItem(stack, x * 2, y * 2);
        graphics.pose().popPose();
    }

    private static String extractLastBracketToken(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        int close = text.lastIndexOf(']');
        if (close < 0) {
            return null;
        }

        int open = text.lastIndexOf('[', close);
        if (open < 0 || close <= open) {
            return null;
        }

        String token = text.substring(open, close + 1).trim();

        if (token.length() < 3) {
            return null;
        }

        if (token.equals("[L]")
                || token.equals("[G]")
                || token.equals("[PM]")
                || token.equals("[ALL]")
                || token.contains("МСК")) {
            return null;
        }

        return token;
    }

    private static boolean isItemToken(String text) {
        return text != null
                && text.length() >= 3
                && text.startsWith("[")
                && text.endsWith("]")
                && !text.equals("[L]")
                && !text.equals("[G]")
                && !text.equals("[PM]")
                && !text.equals("[ALL]")
                && !text.contains("МСК")
                && !text.startsWith("[IconDebug]");
    }

    private static boolean shouldIgnoreChatLine(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }

        String clean = normalize(text);

        return clean.startsWith("[IconDebug]")
                || clean.startsWith("Предмет отправлен в чат:")
                || clean.startsWith("Рядом никого нет.");
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        return text
                .replaceAll("§.", "")
                .replace('\u00A0', ' ')
                .trim();
    }

    private record IconChatLine(String text, String itemToken, ItemStack stack, long createdMillis) {
    }

    private record PendingIcon(String itemToken, ItemStack stack, long createdMillis) {
    }
}