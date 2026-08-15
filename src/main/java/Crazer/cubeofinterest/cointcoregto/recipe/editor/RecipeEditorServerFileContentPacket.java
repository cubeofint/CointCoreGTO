package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorServerFileContentPacket(
        boolean crafting,
        boolean success,
        String message,
        String relativePath,
        String json
) {
    private static final int MAX_MESSAGE = 2_048;
    private static final int MAX_PATH = 1_024;
    private static final int MAX_JSON = RecipeEditorServerFileService.MAX_READ_BYTES;

    public static void encode(RecipeEditorServerFileContentPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
        buffer.writeBoolean(packet.success);
        buffer.writeUtf(packet.message == null ? "" : packet.message, MAX_MESSAGE);
        buffer.writeUtf(packet.relativePath == null ? "" : packet.relativePath, MAX_PATH);
        buffer.writeUtf(packet.json == null ? "" : packet.json, MAX_JSON);
    }

    public static RecipeEditorServerFileContentPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorServerFileContentPacket(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readUtf(MAX_MESSAGE),
                buffer.readUtf(MAX_PATH),
                buffer.readUtf(MAX_JSON)
        );
    }

    public static void handle(RecipeEditorServerFileContentPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleServerFileContent(packet)
        ));
        context.setPacketHandled(true);
    }
}
