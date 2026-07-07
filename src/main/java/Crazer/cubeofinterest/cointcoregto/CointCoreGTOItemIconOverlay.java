package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOItemIconOverlay {
    private static final int MAX_CACHE = 600;
    private static final long CACHE_TTL_MILLIS = 10L * 60L * 1000L;

    private static final float ICON_RENDER_Z = 5000.0F;
    private static final float ICON_SCALE = 0.50F;

    private static final int ICON_X_OFFSET = -10;
    private static final int ICON_Y_OFFSET = -9;

    private static final long CLOSED_CHAT_VISIBLE_MILLIS = 9_000L;
    private static final long CLOSED_CHAT_SEEN_CLEANUP_MILLIS = 30_000L;
    private static final int CLOSED_CHAT_MAX_LINES = 10;

    private static final Map<String, CachedIcon> ITEM_CACHE = new HashMap<>();
    private static final Map<String, Long> CLOSED_CHAT_LINE_FIRST_SEEN = new HashMap<>();
    private static final Set<String> CLOSED_CHAT_EXPIRED_LINES = new HashSet<>();

    private CointCoreGTOItemIconOverlay() {
    }

    public static void queueIcon(ItemStack stack, String prefixText, String itemText) {
        if (stack == null || stack.isEmpty() || itemText == null || itemText.isBlank()) {
            return;
        }

        cacheIcon(itemText, stack);
    }

    public static void clearIcons() {
        synchronized (ITEM_CACHE) {
            ITEM_CACHE.clear();
        }

        CLOSED_CHAT_LINE_FIRST_SEEN.clear();
        CLOSED_CHAT_EXPIRED_LINES.clear();
    }

    public static void clearAllIconCache() {
        synchronized (ITEM_CACHE) {
            ITEM_CACHE.clear();
        }

        synchronized (CLOSED_CHAT_LINE_FIRST_SEEN) {
            CLOSED_CHAT_LINE_FIRST_SEEN.clear();
        }
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) {
            return;
        }

        cacheItemsFromComponent(message);
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options.hideGui) {
            return;
        }

        if (minecraft.player == null || minecraft.level == null) {
            return;
        }

        boolean chatOpen = minecraft.screen instanceof ChatScreen;

        ChatComponent chat = minecraft.gui.getChat();
        if (chat == null) {
            return;
        }

        List<GuiMessage.Line> lines = getTrimmedMessagesSafe(chat);
        if (lines == null || lines.isEmpty()) {
            return;
        }

        Font font = minecraft.font;
        GuiGraphics graphics = event.getGuiGraphics();

        double scale = chat.getScale();
        if (scale <= 0.0D) {
            scale = 1.0D;
        }

        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int scaledScreenHeight = (int) Math.floor(screenHeight / scale);

        int lineHeight = getLineHeightSafe(chat);
        int shownLines = chatOpen ? getLinesPerPageSafe(chat, lines.size()) : CLOSED_CHAT_MAX_LINES;
        int scrollbar = chatOpen ? getChatScrollbarPosSafe(chat) : 0;

        int baseY = scaledScreenHeight - 40;

        graphics.pose().pushPose();
        graphics.pose().translate(4.0F, 0.0F, 0.0F);
        graphics.pose().scale((float) scale, (float) scale, 1.0F);

        int renderedVisibleIndex = 0;

        for (int rawIndex = 0; rawIndex < lines.size(); rawIndex++) {
            int lineIndex = rawIndex + scrollbar;
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                continue;
            }

            GuiMessage.Line line = lines.get(lineIndex);
            if (line == null) {
                continue;
            }

            String lineText = formattedCharSequenceToString(line.content());
            if (lineText == null || lineText.isBlank()) {
                renderedVisibleIndex++;
                continue;
            }

            if (!chatOpen && !isLineVisibleInClosedChat(lineText)) {
                continue;
            }

            if (renderedVisibleIndex >= shownLines) {
                break;
            }

            if (!lineText.contains("[") || !lineText.contains("]")) {
                renderedVisibleIndex++;
                continue;
            }

            ItemIconMatch match = findIconForLine(lineText);
            if (match == null || match.stack() == null || match.stack().isEmpty()) {
                renderedVisibleIndex++;
                continue;
            }

            int itemStart = match.itemStart();
            if (itemStart < 0 || itemStart > lineText.length()) {
                renderedVisibleIndex++;
                continue;
            }

            String beforeItem = lineText.substring(0, itemStart);
            int beforeWidth = font.width(beforeItem);

            int y = baseY - renderedVisibleIndex * lineHeight;
            int x = 0;

            int iconX = x + beforeWidth + ICON_X_OFFSET;
            int iconY = y + ICON_Y_OFFSET;

            drawChatItem(graphics, match.stack(), iconX, iconY, ICON_SCALE, ICON_RENDER_Z);

            renderedVisibleIndex++;
        }

        graphics.pose().popPose();
    }

    public static ItemIconMatch findIconForLine(String lineText) {
        if (lineText == null || lineText.isBlank()) {
            return null;
        }

        String cleanLine = normalize(lineText);
        if (cleanLine.isBlank()) {
            return null;
        }
        ArrayList<String> bracketTokens = extractBracketTokens(cleanLine);

        for (int i = bracketTokens.size() - 1; i >= 0; i--) {
            String token = bracketTokens.get(i);
            if (!isItemToken(token)) {
                continue;
            }

            CachedIcon cachedIcon;
            synchronized (ITEM_CACHE) {
                cleanupCache(System.currentTimeMillis());
                cachedIcon = ITEM_CACHE.get(token);
            }

            if (cachedIcon != null && cachedIcon.stack() != null && !cachedIcon.stack().isEmpty()) {
                int index = findOriginalIndex(lineText, token);
                if (index >= 0) {
                    return new ItemIconMatch(cachedIcon.stack().copy(), index);
                }
            }
        }
        return findWrappedItemStartForLine(lineText);
    }

    private static ItemIconMatch findWrappedItemStartForLine(String lineText) {
        if (lineText == null || lineText.isBlank()) {
            return null;
        }

        int searchFrom = lineText.length() - 1;

        while (searchFrom >= 0) {
            int start = lineText.lastIndexOf('[', searchFrom);
            if (start < 0) {
                break;
            }

            String partialToken = lineText.substring(start).trim();
            String cleanPartial = normalize(partialToken);

            searchFrom = start - 1;

            if (cleanPartial.length() < 3 || !cleanPartial.startsWith("[")) {
                continue;
            }

            /*
             * Если токен полный, он уже обработан выше через extractBracketTokens.
             */
            if (cleanPartial.endsWith("]")) {
                continue;
            }

            if (isChatOrRankPrefixStart(cleanPartial)) {
                continue;
            }

            List<CachedIcon> cachedIcons = snapshotCache();

            for (CachedIcon icon : cachedIcons) {
                if (icon == null || icon.stack() == null || icon.stack().isEmpty()) {
                    continue;
                }

                String fullToken = normalize(icon.itemText());
                if (!isItemToken(fullToken)) {
                    continue;
                }

                /*
                 * Пример:
                 * fullToken    = [Везучести Шахтерский молот (Манасталь) Нефтяной радар]
                 * cleanPartial = [Везучести Шахтерский молот (Манасталь)
                 */
                if (fullToken.startsWith(cleanPartial)) {
                    return new ItemIconMatch(icon.stack().copy(), start);
                }
            }
        }

        return null;
    }

    private static boolean isChatOrRankPrefixStart(String tokenStart) {
        if (tokenStart == null || tokenStart.isBlank()) {
            return true;
        }

        String lowered = normalize(tokenStart).toLowerCase(java.util.Locale.ROOT);

        return lowered.startsWith("[l]")
                || lowered.startsWith("[g]")
                || lowered.startsWith("[pm]")
                || lowered.startsWith("[all]")
                || lowered.startsWith("[lv]")
                || lowered.startsWith("[hv]")
                || lowered.startsWith("[lp]")
                || lowered.startsWith("[admin]")
                || lowered.startsWith("[админ]")
                || lowered.startsWith("[curator]")
                || lowered.startsWith("[куратор]")
                || lowered.startsWith("[модер]")
                || lowered.startsWith("[moder]")
                || lowered.startsWith("[system]")
                || lowered.startsWith("[chat]");
    }

    public static void drawChatItem(GuiGraphics graphics, ItemStack stack, int x, int y, float scale, float z) {
        if (graphics == null || stack == null || stack.isEmpty()) {
            return;
        }

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, z);
        graphics.pose().scale(scale, scale, 1.0F);

        int scaledX = Math.round(x / scale);
        int scaledY = Math.round(y / scale);

        graphics.renderItem(stack, scaledX, scaledY);

        graphics.pose().popPose();
    }

    private static boolean isLineVisibleInClosedChat(String lineText) {
        if (lineText == null || lineText.isBlank()) {
            return false;
        }

        String key = normalize(lineText);

        if (key.isBlank()) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (CLOSED_CHAT_EXPIRED_LINES.contains(key)) {
            return false;
        }

        Long firstSeen = CLOSED_CHAT_LINE_FIRST_SEEN.get(key);

        if (firstSeen == null) {
            CLOSED_CHAT_LINE_FIRST_SEEN.put(key, now);
            return true;
        }

        if (now - firstSeen > CLOSED_CHAT_VISIBLE_MILLIS) {
            CLOSED_CHAT_LINE_FIRST_SEEN.remove(key);
            CLOSED_CHAT_EXPIRED_LINES.add(key);
            return false;
        }

        return true;
    }

    private static void cleanupClosedChatSeenLines(long now) {
        Iterator<Map.Entry<String, Long>> iterator = CLOSED_CHAT_LINE_FIRST_SEEN.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();

            if (entry.getValue() == null || now - entry.getValue() > CLOSED_CHAT_SEEN_CLEANUP_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static List<GuiMessage.Line> getTrimmedMessagesSafe(ChatComponent chat) {
        if (chat == null) {
            return null;
        }

        List<GuiMessage.Line> named = getListField(chat, "trimmedMessages");
        if (named != null) {
            return named;
        }

        List<GuiMessage.Line> srg = getListField(chat, "f_93762_");
        if (srg != null) {
            return srg;
        }

        Field[] fields = ChatComponent.class.getDeclaredFields();

        for (Field field : fields) {
            if (!List.class.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(chat);
                if (!(value instanceof List<?> list)) {
                    continue;
                }

                if (list.isEmpty()) {
                    continue;
                }

                Object first = list.get(0);
                if (first == null) {
                    continue;
                }

                String className = first.getClass().getName();
                if (className.equals("net.minecraft.client.GuiMessage$Line")
                        || className.endsWith("GuiMessage$Line")) {
                    @SuppressWarnings("unchecked")
                    List<GuiMessage.Line> result = (List<GuiMessage.Line>) list;
                    return result;
                }
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<GuiMessage.Line> getListField(ChatComponent chat, String fieldName) {
        try {
            Field field = ChatComponent.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(chat);
            if (value instanceof List<?> list) {
                return (List<GuiMessage.Line>) list;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int getChatScrollbarPosSafe(ChatComponent chat) {
        Integer named = getIntField(chat, "chatScrollbarPos");
        if (named != null) {
            return named;
        }

        Integer srg = getIntField(chat, "f_93763_");
        if (srg != null) {
            return srg;
        }

        return 0;
    }

    private static Integer getIntField(ChatComponent chat, String fieldName) {
        try {
            Field field = ChatComponent.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(chat);
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int getLineHeightSafe(ChatComponent chat) {
        Integer named = invokeIntMethod(chat, "getLineHeight");
        if (named != null && named > 0) {
            return named;
        }

        Integer srg = invokeIntMethod(chat, "m_93785_");
        if (srg != null && srg > 0) {
            return srg;
        }

        return 9;
    }

    private static int getLinesPerPageSafe(ChatComponent chat, int fallbackLimit) {
        Integer named = invokeIntMethod(chat, "getLinesPerPage");
        if (named != null && named > 0) {
            return named;
        }

        Integer srg = invokeIntMethod(chat, "m_93791_");
        if (srg != null && srg > 0) {
            return srg;
        }

        return Math.min(20, Math.max(1, fallbackLimit));
    }

    private static Integer invokeIntMethod(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            Object result = method.invoke(target);
            if (result instanceof Integer value) {
                return value;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static void cacheItemsFromComponent(Component component) {
        if (component == null) {
            return;
        }

        cacheItemFromStyle(component.getStyle(), component.getString());

        for (Component sibling : component.getSiblings()) {
            cacheItemsFromComponent(sibling);
        }
    }

    private static void cacheItemFromStyle(Style style, String text) {
        if (style == null || text == null || text.isBlank()) {
            return;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null) {
            return;
        }

        try {
            HoverEvent.ItemStackInfo itemInfo = hoverEvent.getValue(HoverEvent.Action.SHOW_ITEM);
            if (itemInfo == null) {
                return;
            }

            ItemStack stack = itemInfo.getItemStack();
            if (stack == null || stack.isEmpty()) {
                return;
            }

            cacheIcon(text, stack);
        } catch (Throwable ignored) {
        }
    }

    private static void cacheIcon(String itemText, ItemStack stack) {
        if (itemText == null || itemText.isBlank() || stack == null || stack.isEmpty()) {
            return;
        }

        String cleanItemText = normalize(itemText);
        if (!isItemToken(cleanItemText)) {
            return;
        }

        long now = System.currentTimeMillis();
        ItemStack copy = stack.copy();

        synchronized (ITEM_CACHE) {
            ITEM_CACHE.put(cleanItemText, new CachedIcon(cleanItemText, copy, now));

            String hoverName = stack.getHoverName().getString();
            String hoverToken = normalize("[" + hoverName + "]");
            if (isItemToken(hoverToken)) {
                ITEM_CACHE.put(hoverToken, new CachedIcon(hoverToken, copy.copy(), now));
            }

            String descriptionName = stack.getItem().getDescription().getString();
            String descriptionToken = normalize("[" + descriptionName + "]");
            if (isItemToken(descriptionToken)) {
                ITEM_CACHE.put(descriptionToken, new CachedIcon(descriptionToken, copy.copy(), now));
            }

            cleanupCache(now);

            if (ITEM_CACHE.size() > MAX_CACHE) {
                Iterator<String> iterator = ITEM_CACHE.keySet().iterator();
                while (ITEM_CACHE.size() > MAX_CACHE && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
    }

    private static List<CachedIcon> snapshotCache() {
        long now = System.currentTimeMillis();

        synchronized (ITEM_CACHE) {
            cleanupCache(now);

            ArrayList<CachedIcon> snapshot = new ArrayList<>(ITEM_CACHE.values());
            snapshot.sort(Comparator.comparingInt((CachedIcon icon) -> icon.itemText().length()).reversed());
            return snapshot;
        }
    }

    private static void cleanupCache(long now) {
        Iterator<Map.Entry<String, CachedIcon>> iterator = ITEM_CACHE.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, CachedIcon> entry = iterator.next();

            if (entry.getValue() == null || now - entry.getValue().createdMillis() > CACHE_TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static int findOriginalIndex(String lineText, String normalizedItemText) {
        if (lineText == null || lineText.isBlank() || normalizedItemText == null || normalizedItemText.isBlank()) {
            return -1;
        }

        String cleanNeedle = normalize(normalizedItemText);
        if (cleanNeedle.isBlank()) {
            return -1;
        }

        int exactIndex = lineText.indexOf(cleanNeedle);
        if (exactIndex >= 0) {
            return exactIndex;
        }

        String withoutBrackets = cleanNeedle;
        if (withoutBrackets.startsWith("[") && withoutBrackets.endsWith("]") && withoutBrackets.length() >= 2) {
            withoutBrackets = withoutBrackets.substring(1, withoutBrackets.length() - 1);
        }

        exactIndex = lineText.indexOf("[" + withoutBrackets + "]");
        if (exactIndex >= 0) {
            return exactIndex;
        }

        exactIndex = lineText.indexOf(withoutBrackets);
        if (exactIndex >= 0) {
            return exactIndex;
        }

        return normalize(lineText).indexOf(cleanNeedle);
    }

    private static ArrayList<String> extractBracketTokens(String text) {
        ArrayList<String> tokens = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return tokens;
        }

        int index = 0;
        while (index < text.length()) {
            int start = text.indexOf('[', index);
            if (start < 0) {
                break;
            }

            int end = text.indexOf(']', start + 1);
            if (end < 0) {
                break;
            }

            String token = text.substring(start, end + 1).trim();
            if (!token.isBlank()) {
                tokens.add(token);
            }

            index = end + 1;
        }

        return tokens;
    }

    private static boolean isItemToken(String token) {
        if (token == null) {
            return false;
        }

        String clean = normalize(token);
        if (clean.length() < 3) {
            return false;
        }

        if (!clean.startsWith("[") || !clean.endsWith("]")) {
            return false;
        }

        String inside = clean.substring(1, clean.length() - 1).trim();
        if (inside.isBlank()) {
            return false;
        }

        String lowered = inside.toLowerCase(java.util.Locale.ROOT);

        if (lowered.equals("l")
                || lowered.equals("g")
                || lowered.equals("pm")
                || lowered.equals("all")
                || lowered.equals("system")
                || lowered.equals("chat")
                || lowered.equals("lv")
                || lowered.equals("hv")
                || lowered.equals("lp")
                || lowered.equals("admin")
                || lowered.equals("админ")
                || lowered.equals("куратор")
                || lowered.equals("curator")) {
            return false;
        }

        if (lowered.matches("\\d{1,2}:\\d{2}.*")) {
            return false;
        }

        return true;
    }

    private static String formattedCharSequenceToString(FormattedCharSequence sequence) {
        if (sequence == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try {
            sequence.accept((int index, Style style, int codePoint) -> {
                builder.appendCodePoint(codePoint);
                return true;
            });
        } catch (Throwable ignored) {
        }

        return builder.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replaceAll("§.", "")
                .replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record CachedIcon(String itemText, ItemStack stack, long createdMillis) {
    }

    public record ItemIconMatch(ItemStack stack, int itemStart) {
    }
}