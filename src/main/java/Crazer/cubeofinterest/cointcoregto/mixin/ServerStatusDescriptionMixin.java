package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ReservedSlots;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ServerStatus.class)
public abstract class ServerStatusDescriptionMixin {

    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static Component cointcoregto$appendReservedSlots(
            Component originalDescription
    ) {
        MinecraftServer server =
                ServerLifecycleHooks.getCurrentServer();

        return ReservedSlots.appendReservedInfoToMotd(
                originalDescription,
                server
        );
    }
}