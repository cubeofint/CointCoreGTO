package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class BattlePassNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "battlepass"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered;

    private BattlePassNetwork() {
    }

    public static void register() {
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
                id,
                BattlePassStatePacket.class,
                BattlePassStatePacket::encode,
                BattlePassStatePacket::decode,
                BattlePassStatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        registered = true;
    }
}
