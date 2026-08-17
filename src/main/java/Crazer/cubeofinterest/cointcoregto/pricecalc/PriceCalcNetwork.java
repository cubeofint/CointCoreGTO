package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PriceCalcNetwork {
    private static final String PROTOCOL_VERSION = "3";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "pricecalc"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );
    private static int packetId;
    private static boolean initialized;

    private PriceCalcNetwork() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(PriceCalcNetwork::register);
    }

    private static synchronized void register() {
        if (initialized) {
            return;
        }
        initialized = true;
        CHANNEL.registerMessage(
                packetId++,
                PriceCalcCommandPacket.class,
                PriceCalcCommandPacket::encode,
                PriceCalcCommandPacket::decode,
                PriceCalcCommandPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                PriceCalcAccessPacket.class,
                PriceCalcAccessPacket::encode,
                PriceCalcAccessPacket::decode,
                PriceCalcAccessPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                packetId++,
                PriceCalcRequestPacket.class,
                PriceCalcRequestPacket::encode,
                PriceCalcRequestPacket::decode,
                PriceCalcRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }

    public static void sendTo(ServerPlayer player, PriceCalcCommandPacket.Action action) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PriceCalcCommandPacket(action)
        );
    }

    public static void sendAccess(ServerPlayer player, boolean allowed) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new PriceCalcAccessPacket(allowed)
        );
    }

    public static void requestCalculation() {
        CHANNEL.sendToServer(new PriceCalcRequestPacket(PriceCalcRequestPacket.Action.CALC));
    }

    public static void requestAccessState() {
        CHANNEL.sendToServer(new PriceCalcRequestPacket(PriceCalcRequestPacket.Action.QUERY_ACCESS));
    }
}
