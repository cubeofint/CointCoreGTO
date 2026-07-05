package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class CointCoreGTOItemPreview {
    private static final int MAX_ITEM_NAME_CHARS = 36;

    private CointCoreGTOItemPreview() {
    }

    public static String toPlainText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "[Пусто]";
        }

        String name = getCleanItemName(stack);
        name = shortenItemName(name);

        return "[" + name + "]";
    }

    public static String normalizeDisplayText(String displayText, ItemStack stack) {
        String text = displayText == null ? "" : stripMinecraftFormatting(displayText).trim();

        if (text.startsWith("[") && text.endsWith("]") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1).trim();
        }

        if (text.isBlank()) {
            text = getCleanItemName(stack);
        }

        text = shortenItemName(text);

        return "[" + text + "]";
    }

    public static String toPlainMessage(ServerPlayer player, String message) {
        return message == null ? "" : message;
    }

    public static Component buildMessage(ServerPlayer player, String prefix, String message) {
        return Component.literal((prefix == null ? "" : prefix) + (message == null ? "" : message));
    }

    public static Component buildItemComponent(ItemStack stack) {
        return buildItemComponent(stack, toPlainText(stack));
    }

    public static Component buildItemComponent(ItemStack stack, String displayText) {
        String text = normalizeDisplayText(displayText, stack);
        ItemStack safeStack = stack == null ? ItemStack.EMPTY : stack.copy();

        return Component.literal(text)
                .withStyle(style -> style.withHoverEvent(
                        new HoverEvent(
                                HoverEvent.Action.SHOW_ITEM,
                                new HoverEvent.ItemStackInfo(safeStack)
                        )
                ));
    }

    private static String getCleanItemName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "Пусто";
        }

        String name = stack.getHoverName().getString();
        if (name == null || name.isBlank()) {
            name = stack.getItem().getDescription().getString();
        }

        if (name == null || name.isBlank()) {
            name = String.valueOf(stack.getItem());
        }

        return stripMinecraftFormatting(name).replaceAll("\\s+", " ").trim();
    }

    private static String shortenItemName(String name) {
        if (name == null || name.isBlank()) {
            return "Пусто";
        }

        String clean = stripMinecraftFormatting(name).replaceAll("\\s+", " ").trim();

        if (clean.length() <= MAX_ITEM_NAME_CHARS) {
            return clean;
        }

        return clean.substring(0, MAX_ITEM_NAME_CHARS).trim() + "...";
    }

    private static String stripMinecraftFormatting(String text) {
        return text == null ? "" : text.replaceAll("§.", "");
    }
}