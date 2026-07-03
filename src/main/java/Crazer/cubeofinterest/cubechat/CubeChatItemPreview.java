package Crazer.cubeofinterest.cubechat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CubeChatItemPreview {
    private static final Pattern ITEM_TOKEN_PATTERN = Pattern.compile("(?i)(\\[item]\\|\\[i]\\|\\[предмет])");
    private static final String EMPTY_HAND_TEXT = "[пустая рука]";

    private CubeChatItemPreview() {
    }

    public static String toPlainMessage(ServerPlayer player, String message) {
        if (message == null || message.isBlank() || player == null) {
            return message == null ? "" : message;
        }

        Matcher matcher = ITEM_TOKEN_PATTERN.matcher(message);
        if (!matcher.find()) {
            return message;
        }

        String itemText = toPlainText(getShownStack(player));
        return matcher.replaceAll(Matcher.quoteReplacement(itemText));
    }

    public static Component buildMessage(ServerPlayer player, String formattedPrefix, String rawMessage) {
        MutableComponent result = Component.literal(formattedPrefix == null ? "" : formattedPrefix);

        if (rawMessage == null || rawMessage.isBlank() || player == null) {
            return result.append(rawMessage == null ? "" : rawMessage);
        }

        Matcher matcher = ITEM_TOKEN_PATTERN.matcher(rawMessage);
        int cursor = 0;
        boolean found = false;
        ItemStack shownStack = getShownStack(player);

        while (matcher.find()) {
            found = true;

            if (matcher.start() > cursor) {
                result.append(Component.literal(rawMessage.substring(cursor, matcher.start())));
            }

            result.append(buildItemComponent(shownStack));
            cursor = matcher.end();
        }

        if (!found) {
            return result.append(Component.literal(rawMessage));
        }

        if (cursor < rawMessage.length()) {
            result.append(Component.literal(rawMessage.substring(cursor)));
        }

        return result;
    }

    public static String toPlainText(ItemStack sourceStack) {
        ItemStack stack = sourceStack == null ? ItemStack.EMPTY : sourceStack.copy();
        if (stack.isEmpty()) {
            return EMPTY_HAND_TEXT;
        }

        String name = stack.getHoverName().getString();
        if (stack.getCount() > 1) {
            return "[" + name + " x" + stack.getCount() + "]";
        }

        return "[" + name + "]";
    }

    public static Component buildItemComponent(ItemStack sourceStack) {
        ItemStack stack = sourceStack == null ? ItemStack.EMPTY : sourceStack.copy();
        if (stack.isEmpty()) {
            return Component.literal(EMPTY_HAND_TEXT).withStyle(ChatFormatting.RED);
        }

        MutableComponent itemText = Component.literal("[")
                .append(stack.getHoverName().copy())
                .append(stack.getCount() > 1 ? " x" + stack.getCount() : "")
                .append("]");

        return itemText.withStyle(style -> style
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_ITEM, new HoverEvent.ItemStackInfo(stack)))
                .withInsertion(stack.getHoverName().getString())
        );
    }

    private static ItemStack getShownStack(ServerPlayer player) {
        ItemStack mainHand = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!mainHand.isEmpty()) {
            return mainHand.copy();
        }

        ItemStack offHand = player.getItemInHand(InteractionHand.OFF_HAND);
        return offHand.isEmpty() ? ItemStack.EMPTY : offHand.copy();
    }
}
