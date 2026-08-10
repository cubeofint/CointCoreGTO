package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorSaveResultPacket(
        boolean success,
        String message,
        String relativePath,
        String normalizedJson
) {
    private static final int MAX_MESSAGE_LENGTH = 2_048;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_JSON_LENGTH = 64_000;

    public static void encode(RecipeEditorSaveResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.success);
        buffer.writeUtf(packet.message == null ? "" : packet.message, MAX_MESSAGE_LENGTH);
        buffer.writeUtf(packet.relativePath == null ? "" : packet.relativePath, MAX_PATH_LENGTH);
        buffer.writeUtf(packet.normalizedJson == null ? "" : packet.normalizedJson, MAX_JSON_LENGTH);
    }

    public static RecipeEditorSaveResultPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorSaveResultPacket(
                buffer.readBoolean(),
                buffer.readUtf(MAX_MESSAGE_LENGTH),
                buffer.readUtf(MAX_PATH_LENGTH),
                buffer.readUtf(MAX_JSON_LENGTH)
        );
    }

    public static void handle(RecipeEditorSaveResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleSaveResult(packet)
        ));
        context.setPacketHandled(true);
    }
}