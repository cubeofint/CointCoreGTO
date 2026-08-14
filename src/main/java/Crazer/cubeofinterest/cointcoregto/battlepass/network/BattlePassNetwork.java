package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.battlepass.BattlePassConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class BattlePassNetwork {

    // Protocol changed because a new packet was added. Old client/server pairs
    // should fail cleanly instead of silently disagreeing about message ids.
    private static final String PROTOCOL = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("cointcoregto", "battlepass"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered;

    private BattlePassNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }

        int id = 0;

        CHANNEL.registerMessage(
                id++,
                BattlePassOpenPacket.class,
                BattlePassOpenPacket::encode,
                BattlePassOpenPacket::decode,
                BattlePassOpenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                id++,
                BattlePassClaimPacket.class,
                BattlePassClaimPacket::encode,
                BattlePassClaimPacket::decode,
                BattlePassClaimPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );

        CHANNEL.registerMessage(
                id++,
                BattlePassStatePacket.class,
                BattlePassStatePacket::encode,
                BattlePassStatePacket::decode,
                BattlePassStatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                id,
                BattlePassAvailabilityPacket.class,
                BattlePassAvailabilityPacket::encode,
                BattlePassAvailabilityPacket::decode,
                BattlePassAvailabilityPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        registered = true;
    }

    public static void sendAvailability(ServerPlayer player) {
        if (player == null) {
            return;
        }

        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new BattlePassAvailabilityPacket(BattlePassConfig.get().enabled())
        );
    }

    public static void broadcastAvailability(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendAvailability(player);
        }
    }
}
