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
import java.util.Iterator;
import java.util.List;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOItemIconOverlay {
    private static final int MAX_CACHE = 300;
    private static final long CACHE_TTL_MILLIS = 5L * 60L * 1000L;

    private static final float ICON_RENDER_Z = 5000.0F;
    private static final float ICON_SCALE = 0.50F;

    private static final int ICON_X_OFFSET = -10;
    private static final int ICON_Y_OFFSET = -9;

    private static final int CLOSED_CHAT_MAX_LINES = 10;

    private static final List<CachedLineIcon> CACHED_LINES = new ArrayList<>();

    private CointCoreGTOItemIconOverlay() {
    }

    public static void queueIcon(ItemStack stack, String prefixText, String itemText) {
    }

    public static void clearIcons() {
        clearAllIconCache();
    }

    public static void clearAllIconCache() {
        synchronized (CACHED_LINES) {
            CACHED_LINES.clear();
        }
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event) {
        Component message = event.getMessage();
        if (message == null) {
            return;
        }

        String fullMessageText = normalize(message.getString());
        if (fullMessageText.isBlank()) {
            return;
        }

        cacheItemsFromComponent(message, fullMessageText);
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

        if (minecraft.gui == null) {
            return;
        }

        ChatComponent chat = minecraft.gui.getChat();
        if (chat == null) {
            return;
        }

        List<GuiMessage.Line> lines = getTrimmedMessagesSafe(chat);
        if (lines == null || lines.isEmpty()) {
            return;
        }

        boolean chatOpen = minecraft.screen instanceof ChatScreen;

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

        cleanupCache(System.currentTimeMillis());

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
            String cleanLineText = normalize(lineText);

            if (cleanLineText.isBlank()) {
                renderedVisibleIndex++;
                continue;
            }



            if (renderedVisibleIndex >= shownLines) {
                break;
            }

            ItemIconMatch match = findIconForLine(cleanLineText);
            if (match == null || match.stack() == null || match.stack().isEmpty()) {
                renderedVisibleIndex++;
                continue;
            }

            if (!chatOpen && System.currentTimeMillis() - match.createdMillis() > 10_000L) {
                renderedVisibleIndex++;
                continue;
            }

            int itemStart = findOriginalIndex(lineText, match.itemText());
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

    private static void cacheItemsFromComponent(Component component, String fullMessageText) {
        if (component == null) {
            return;
        }

        cacheItemFromStyle(component.getStyle(), component.getString(), fullMessageText);

        for (Component sibling : component.getSiblings()) {
            cacheItemsFromComponent(sibling, fullMessageText);
        }
    }

    private static void cacheItemFromStyle(Style style, String componentText, String fullMessageText) {
        if (style == null || componentText == null || componentText.isBlank()) {
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

            String itemText = normalize(componentText);
            if (!isItemToken(itemText)) {
                return;
            }

            long now = System.currentTimeMillis();

            synchronized (CACHED_LINES) {
                CACHED_LINES.add(new CachedLineIcon(
                        fullMessageText,
                        itemText,
                        stack.copy(),
                        now
                ));

                while (CACHED_LINES.size() > MAX_CACHE) {
                    CACHED_LINES.remove(0);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static ItemIconMatch findIconForLine(String cleanLineText) {
        if (cleanLineText == null || cleanLineText.isBlank()) {
            return null;
        }

        long now = System.currentTimeMillis();

        synchronized (CACHED_LINES) {
            for (int i = CACHED_LINES.size() - 1; i >= 0; i--) {
                CachedLineIcon cached = CACHED_LINES.get(i);

                if (cached == null || now - cached.createdMillis() > CACHE_TTL_MILLIS) {
                    continue;
                }

                if (cached.stack() == null || cached.stack().isEmpty()) {
                    continue;
                }

                if (!cleanLineText.contains(cached.itemText())) {
                    continue;
                }

                if (!cached.fullMessageText().contains(cleanLineText)
                        && !cleanLineText.contains(cached.fullMessageText())) {
                    continue;
                }

                return new ItemIconMatch(cached.stack().copy(), cached.itemText(), cached.createdMillis());
            }
        }

        return null;
    }

    private static void cleanupCache(long now) {
        synchronized (CACHED_LINES) {
            Iterator<CachedLineIcon> iterator = CACHED_LINES.iterator();

            while (iterator.hasNext()) {
                CachedLineIcon cached = iterator.next();

                if (cached == null || now - cached.createdMillis() > CACHE_TTL_MILLIS) {
                    iterator.remove();
                }
            }
        }
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

        return !lowered.equals("l")
                && !lowered.equals("g")
                && !lowered.equals("pm")
                && !lowered.equals("all")
                && !lowered.equals("system")
                && !lowered.equals("chat")
                && !lowered.equals("lv")
                && !lowered.equals("hv")
                && !lowered.equals("lp")
                && !lowered.equals("admin")
                && !lowered.equals("админ")
                && !lowered.equals("куратор")
                && !lowered.equals("curator");
    }

    private static void drawChatItem(GuiGraphics graphics, ItemStack stack, int x, int y, float scale, float z) {
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

    private static int findOriginalIndex(String lineText, String itemText) {
        if (lineText == null || lineText.isBlank() || itemText == null || itemText.isBlank()) {
            return -1;
        }

        int exact = lineText.indexOf(itemText);
        if (exact >= 0) {
            return exact;
        }

        String cleanLine = normalize(lineText);
        String cleanItem = normalize(itemText);

        return cleanLine.indexOf(cleanItem);
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

    private record CachedLineIcon(String fullMessageText, String itemText, ItemStack stack, long createdMillis) {
    }

    private record ItemIconMatch(ItemStack stack, String itemText, long createdMillis) {
    }
}