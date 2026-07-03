package Crazer.cubeofinterest.cubechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(
        modid = CubeChat.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CubeChatItemIconOverlay {
    private static final int MAX_CACHE = 500;
    private static final long CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    /**
     * Key: visible item text, for example "[Diamond Sword]".
     * We draw icons from the actually visible vanilla chat lines every frame.
     * This means icons automatically disappear when CubeChat switches tabs,
     * clears chat, or hides non-selected channels.
     */
    private static final Map<String, CachedIcon> ITEM_CACHE = new HashMap<>();

    private CubeChatItemIconOverlay() {
    }

    public static void queueIcon(ItemStack stack, String prefixText, String itemText) {
        cacheIcon(itemText, stack);
    }

    public static void clearIcons() {
        synchronized (ITEM_CACHE) {
            ITEM_CACHE.clear();
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
    public static void onRenderGuiOverlay(RenderGuiOverlayEvent.Post event) {
        try {
            if (!VanillaGuiOverlay.CHAT_PANEL.id().equals(event.getOverlay().id())) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null || mc.options.hideGui || mc.font == null || mc.gui == null) {
            return;
        }

        ChatComponent chat = mc.gui.getChat();
        if (chat == null) {
            return;
        }

        List<String> visibleLines = getVisibleChatLines(chat, mc);
        if (visibleLines.isEmpty()) {
            return;
        }

        List<CachedIcon> cachedIcons = snapshotCache();
        if (cachedIcons.isEmpty()) {
            return;
        }

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = getChatLineHeight(chat);
        int chatHeight = getChatHeight(chat);
        int maxVisibleLines = Math.max(1, chatHeight / Math.max(1, lineHeight));
        int chatBottom = screenHeight - 40;

        GuiGraphics graphics = event.getGuiGraphics();

        int count = Math.min(Math.min(visibleLines.size(), maxVisibleLines), maxVisibleLines + 2);
        for (int lineIndex = 0; lineIndex < count; lineIndex++) {
            String lineText = visibleLines.get(lineIndex);
            if (lineText == null || lineText.isBlank()) {
                continue;
            }

            IconMatch match = findIconForLine(lineText, cachedIcons);
            if (match == null || match.stack.isEmpty()) {
                continue;
            }

            String beforeItem = lineText.substring(0, match.itemStart);
            int beforeWidth = mc.font.width(beforeItem);
            int iconX = Math.max(2, 4 + beforeWidth - 10);
            int textY = chatBottom - (lineIndex + 1) * lineHeight;
            int iconY = textY + Math.max(0, (lineHeight - 8) / 2);

            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 500.0F);
            graphics.pose().scale(0.5F, 0.5F, 1.0F);
            graphics.renderItem(match.stack, iconX * 2, iconY * 2);
            graphics.pose().popPose();
        }
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
            cacheIcon(text, stack);
        } catch (Throwable ignored) {
        }
    }

    private static void cacheIcon(String itemText, ItemStack stack) {
        if (itemText == null || itemText.isBlank() || stack == null || stack.isEmpty()) {
            return;
        }

        String cleanItemText = normalize(itemText);
        if (cleanItemText.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        synchronized (ITEM_CACHE) {
            ITEM_CACHE.put(cleanItemText, new CachedIcon(cleanItemText, stack.copy(), now));
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
            snapshot.sort(Comparator.comparingInt((CachedIcon icon) -> icon.itemText.length()).reversed());
            return snapshot;
        }
    }

    private static void cleanupCache(long now) {
        Iterator<Map.Entry<String, CachedIcon>> iterator = ITEM_CACHE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CachedIcon> entry = iterator.next();
            if (now - entry.getValue().createdMillis > CACHE_TTL_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static IconMatch findIconForLine(String lineText, List<CachedIcon> cachedIcons) {
        String cleanLine = normalize(lineText);
        if (cleanLine.isBlank()) {
            return null;
        }

        for (CachedIcon icon : cachedIcons) {
            int index = cleanLine.indexOf(icon.itemText);
            if (index >= 0) {
                return new IconMatch(icon.stack.copy(), index);
            }
        }

        return null;
    }

    private static List<String> getVisibleChatLines(ChatComponent chat, Minecraft mc) {
        ArrayList<String> lines = new ArrayList<>();

        try {
            Field trimmedMessagesField = findField(ChatComponent.class, "trimmedMessages", "f_93762_", "field_2064");
            if (trimmedMessagesField == null) {
                return lines;
            }
            trimmedMessagesField.setAccessible(true);

            Object rawValue = trimmedMessagesField.get(chat);
            if (!(rawValue instanceof List<?> rawLines) || rawLines.isEmpty()) {
                return lines;
            }

            int lineHeight = getChatLineHeight(chat);
            int chatHeight = getChatHeight(chat);
            int maxVisibleLines = Math.max(1, chatHeight / Math.max(1, lineHeight));
            int scroll = Math.max(0, getChatScroll(chat));
            int end = Math.min(rawLines.size(), scroll + maxVisibleLines + 2);

            for (int i = scroll; i < end; i++) {
                String text = lineToString(rawLines.get(i));
                lines.add(text == null ? "" : text);
            }
        } catch (Throwable ignored) {
        }

        return lines;
    }

    private static String lineToString(Object line) {
        if (line == null) {
            return "";
        }

        try {
            Object content;
            try {
                content = line.getClass().getMethod("content").invoke(line);
            } catch (Throwable ignored) {
                Field contentField = findField(line.getClass(), "content", "f_242248_", "field_40678");
                if (contentField == null) {
                    return "";
                }
                contentField.setAccessible(true);
                content = contentField.get(line);
            }

            if (content instanceof FormattedCharSequence sequence) {
                StringBuilder builder = new StringBuilder();
                sequence.accept((index, style, codePoint) -> {
                    builder.appendCodePoint(codePoint);
                    return true;
                });
                return builder.toString();
            }

            if (content instanceof Component component) {
                return component.getString();
            }

            return content == null ? "" : content.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int getChatScroll(ChatComponent chat) {
        try {
            Field field = findField(ChatComponent.class, "chatScrollbarPos", "f_93763_", "field_2062");
            if (field == null) {
                return 0;
            }
            field.setAccessible(true);
            Object value = field.get(chat);
            if (value instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    private static int getChatHeight(ChatComponent chat) {
        try {
            Method method = chat.getClass().getMethod("getHeight");
            Object result = method.invoke(chat);
            if (result instanceof Integer value && value > 0) {
                return value;
            }
        } catch (Throwable ignored) {
        }

        return 180;
    }

    private static int getChatLineHeight(ChatComponent chat) {
        try {
            Method method = chat.getClass().getMethod("getLineHeight");
            Object result = method.invoke(chat);
            if (result instanceof Integer value && value > 0) {
                return value;
            }
        } catch (Throwable ignored) {
        }

        return 9;
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

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00A0', ' ').replaceAll("§.", "").trim();
    }

    private record CachedIcon(String itemText, ItemStack stack, long createdMillis) {
    }

    private record IconMatch(ItemStack stack, int itemStart) {
    }
}
