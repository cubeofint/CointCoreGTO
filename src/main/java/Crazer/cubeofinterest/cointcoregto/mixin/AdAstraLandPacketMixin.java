package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.AdAstraLandingDeniedPacket;
import Crazer.cubeofinterest.cointcoregto.CointCoreGTONetwork;
import Crazer.cubeofinterest.cointcoregto.DimensionQuestAccessHandler;
import earth.terrarium.adastra.common.network.messages.ServerboundLandPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(
        targets = "earth.terrarium.adastra.common.network.messages.ServerboundLandPacket$Type",
        remap = false
)
public abstract class AdAstraLandPacketMixin {

    @Inject(
            method = "lambda$handle$0",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void cointcoregto$checkPlanetQuest(
            ServerboundLandPacket packet,
            Player player,
            CallbackInfo ci
    ) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (DimensionQuestAccessHandler.canLandOnDimension(
                serverPlayer,
                packet.dimension().location()
        )) {
            return;
        }

        CointCoreGTONetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> serverPlayer),
                new AdAstraLandingDeniedPacket()
        );

        ci.cancel();
    }
}