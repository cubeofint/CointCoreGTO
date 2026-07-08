package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;

public class CointTunerItem extends Item {
    public CointTunerItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Тюнер");
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return "Тюнер";
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.literal("Тюнер радио").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Shift + ПКМ по радио: запомнить радио").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("ПКМ по динамику: связать динамик").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Создание: Shift + ПКМ по воздуху с компасом").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Нужно: компас + железный слиток + редстоун").withStyle(ChatFormatting.DARK_GRAY));

        BlockPos stored = getStoredRadioPos(stack);

        if (stored != null) {
            tooltip.add(Component.literal("Запомнено радио: " + posText(stored)).withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("Радио не запомнено").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockPos clickedPos = context.getClickedPos();

        if (player == null) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity clickedEntity = level.getBlockEntity(clickedPos);

        if (clickedEntity instanceof CointRadioBlockEntity radio) {
            if (!player.isShiftKeyDown()) {
                serverPlayer.displayClientMessage(
                        Component.literal("§e[CointMusic] Чтобы запомнить радио, используй Shift + ПКМ."),
                        true
                );
                return InteractionResult.SUCCESS;
            }

            if (!CointRadioProtection.canUseRadio(serverPlayer, clickedPos, context.getHand())) {
                CointRadioProtection.denyWithMessage(serverPlayer);
                return InteractionResult.SUCCESS;
            }

            setStoredRadioPos(stack, clickedPos);

            serverPlayer.displayClientMessage(
                    Component.literal("§a[CointMusic] Радио запомнено: §f" + posText(clickedPos)),
                    true
            );

            return InteractionResult.SUCCESS;
        }

        if (clickedEntity instanceof CointSpeakerBlockEntity speaker) {
            BlockPos radioPos = getStoredRadioPos(stack);

            if (radioPos == null) {
                serverPlayer.displayClientMessage(
                        Component.literal("§c[CointMusic] Сначала Shift + ПКМ тюнером по радио."),
                        true
                );
                return InteractionResult.SUCCESS;
            }

            if (!level.isLoaded(radioPos)) {
                serverPlayer.displayClientMessage(
                        Component.literal("§c[CointMusic] Радио не загружено."),
                        true
                );
                return InteractionResult.SUCCESS;
            }

            if (!CointRadioProtection.canUseRadio(serverPlayer, clickedPos, context.getHand())) {
                CointRadioProtection.denyWithMessage(serverPlayer);
                return InteractionResult.SUCCESS;
            }

            BlockEntity radioEntity = level.getBlockEntity(radioPos);

            if (!(radioEntity instanceof CointRadioBlockEntity radio)) {
                serverPlayer.displayClientMessage(
                        Component.literal("§c[CointMusic] Запомненное радио больше не найдено."),
                        true
                );
                return InteractionResult.SUCCESS;
            }

            speaker.setRadioPos(radioPos);
            radio.addSpeaker(clickedPos);

            serverPlayer.displayClientMessage(
                    Component.literal("§a[CointMusic] Динамик связан с радио."),
                    true
            );

            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    private static void setStoredRadioPos(ItemStack stack, BlockPos pos) {
        CompoundTag tag = stack.getOrCreateTag();

        tag.putInt("RadioX", pos.getX());
        tag.putInt("RadioY", pos.getY());
        tag.putInt("RadioZ", pos.getZ());
    }

    private static BlockPos getStoredRadioPos(ItemStack stack) {
        CompoundTag tag = stack.getTag();

        if (tag == null) {
            return null;
        }

        if (!tag.contains("RadioX") || !tag.contains("RadioY") || !tag.contains("RadioZ")) {
            return null;
        }

        return new BlockPos(
                tag.getInt("RadioX"),
                tag.getInt("RadioY"),
                tag.getInt("RadioZ")
        );
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}