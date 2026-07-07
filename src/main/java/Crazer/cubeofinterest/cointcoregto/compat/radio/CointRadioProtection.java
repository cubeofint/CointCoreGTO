package Crazer.cubeofinterest.cointcoregto.compat.radio;

import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.fml.ModList;

public final class CointRadioProtection {
    private static final String FTB_CHUNKS_MOD_ID = "ftbchunks";

    private CointRadioProtection() {
    }

    public static boolean canUseRadio(ServerPlayer player, BlockPos pos, InteractionHand hand) {
        if (player == null || pos == null) {
            return false;
        }

        if (player.hasPermissions(2)) {
            return true;
        }

        if (!ModList.get().isLoaded(FTB_CHUNKS_MOD_ID)) {
            return true;
        }

        try {
            if (!FTBChunksAPI.api().isManagerLoaded()) {
                return true;
            }

            boolean prevent = FTBChunksAPI.api()
                    .getManager()
                    .shouldPreventInteraction(
                            player,
                            hand == null ? InteractionHand.MAIN_HAND : hand,
                            pos,
                            Protection.INTERACT_BLOCK,
                            null
                    );

            return !prevent;
        } catch (Throwable e) {
            System.out.println("[CointMusic] FTB Chunks protection check failed: " + e.getMessage());
            return true;
        }
    }

    public static boolean denyWithMessage(ServerPlayer player) {
        if (player == null) {
            return true;
        }

        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("§c[CointMusic] Это радио находится в чужом привате."),
                true
        );

        return true;
    }
}