package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public final class CointCoreGTONetwork {
    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL =
            NetworkRegistry.newSimpleChannel(
                    new ResourceLocation(
                            CointCoreGTO.MODID,
                            "landing_messages"
                    ),
                    () -> PROTOCOL_VERSION,
                    PROTOCOL_VERSION::equals,
                    PROTOCOL_VERSION::equals
            );

    private static int packetId = 0;
    private static boolean initialized = false;

    private CointCoreGTONetwork() {
    }

    public static void register() {
        if (initialized) {
            return;
        }

        initialized = true;

        CHANNEL.registerMessage(
                packetId++,
                AdAstraLandingDeniedPacket.class,
                AdAstraLandingDeniedPacket::encode,
                AdAstraLandingDeniedPacket::decode,
                AdAstraLandingDeniedPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}