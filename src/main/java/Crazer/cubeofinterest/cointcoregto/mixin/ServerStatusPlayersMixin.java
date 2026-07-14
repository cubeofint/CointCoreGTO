package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ReservedSlots;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

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

    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1
    )
    private static int cointcoregto$replaceDisplayedOnlinePlayers(int originalOnlinePlayers) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        return ReservedSlots.getRegularOnlineForDisplay(server, originalOnlinePlayers);
    }

    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private static List<GameProfile> cointcoregto$addReservedStatusMarker(List<GameProfile> originalSample) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        return ReservedSlots.addReservedStatusMarker(originalSample, server);
    }
}