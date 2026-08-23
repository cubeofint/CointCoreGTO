package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SupplyLinkCardItem extends Item {
    private static final String TAG_LINK_ID = "SupplyLinkId";
    private static final String TAG_PROVIDER_NODE = "SupplyProviderNode";

    public SupplyLinkCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static boolean isBound(ItemStack stack) {
        return !getLinkId(stack).isBlank();
    }

    public static String getLinkId(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return "";
        }
        return stack.getTag().getString(TAG_LINK_ID).trim();
    }

    public static String getProviderNode(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return "";
        }
        return stack.getTag().getString(TAG_PROVIDER_NODE).trim();
    }

    public static void bind(ItemStack stack, String linkId, String providerNode) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(TAG_LINK_ID, linkId == null ? "" : linkId.trim());
        tag.putString(TAG_PROVIDER_NODE, providerNode == null ? "" : providerNode.trim());
    }

    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) {
            return;
        }
        CompoundTag tag = stack.getTag();
        tag.remove(TAG_LINK_ID);
        tag.remove(TAG_PROVIDER_NODE);
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Карта связи снабжения");
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return "Карта связи снабжения";
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return isBound(stack) || super.isFoil(stack);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, level, tooltip, flag);

        String linkId = getLinkId(stack);
        if (linkId.isBlank()) {
            tooltip.add(Component.literal("Не привязана")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Используй на непривязанном буфере, чтобы создать Provider-связь.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        tooltip.add(Component.literal("Связь: " + linkId)
                .withStyle(ChatFormatting.AQUA));
        String node = getProviderNode(stack);
        if (!node.isBlank()) {
            tooltip.add(Component.literal("Provider-нода: " + node)
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.literal("Используй на другом буфере для Remote. Shift+ПКМ — восстановить Provider.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
