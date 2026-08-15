package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorServerFileDeleteResultPacket(
        boolean crafting,
        boolean success,
        String message,
        String relativePath
) {
    private static final int MAX_MESSAGE = 2_048;
    private static final int MAX_PATH = 1_024;

    public static void encode(RecipeEditorServerFileDeleteResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
        buffer.writeBoolean(packet.success);
        buffer.writeUtf(packet.message == null ? "" : packet.message, MAX_MESSAGE);
        buffer.writeUtf(packet.relativePath == null ? "" : packet.relativePath, MAX_PATH);
    }

    public static RecipeEditorServerFileDeleteResultPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorServerFileDeleteResultPacket(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readUtf(MAX_MESSAGE),
                buffer.readUtf(MAX_PATH)
        );
    }

    public static void handle(RecipeEditorServerFileDeleteResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleServerFileDeleteResult(packet)
        ));
        context.setPacketHandled(true);
    }
}
