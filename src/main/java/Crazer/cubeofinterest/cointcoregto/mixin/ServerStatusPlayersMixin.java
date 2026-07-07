package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ReservedSlots;
import net.minecraft.network.protocol.status.ServerStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerStatus.Players.class)
public abstract class ServerStatusPlayersMixin {
    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static int cointcoregto$replaceDisplayedMaxPlayers(int originalMaxPlayers) {
        return ReservedSlots.getPublicSlotsForDisplay(originalMaxPlayers);
    }
}