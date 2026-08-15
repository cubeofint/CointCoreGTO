package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorServerFileReadPacket(boolean crafting, String relativePath) {
    private static final int MAX_PATH = 1_024;

    public static void encode(RecipeEditorServerFileReadPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
        buffer.writeUtf(packet.relativePath == null ? "" : packet.relativePath, MAX_PATH);
    }

    public static RecipeEditorServerFileReadPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorServerFileReadPacket(buffer.readBoolean(), buffer.readUtf(MAX_PATH));
    }

    public static void handle(RecipeEditorServerFileReadPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!RecipeEditorServerFilesRequestPacket.allowed(player, packet.crafting)) {
                RecipeEditorServerFilesRequestPacket.send(player, new RecipeEditorServerFileContentPacket(
                        packet.crafting, false, "Недостаточно прав", packet.relativePath, ""
                ));
                return;
            }

            RecipeEditorServerFileService.ReadResult result = RecipeEditorServerFileService.read(
                    packet.crafting,
                    packet.relativePath
            );
            RecipeEditorServerFilesRequestPacket.send(player, new RecipeEditorServerFileContentPacket(
                    packet.crafting,
                    result.success(),
                    result.message(),
                    result.relativePath(),
                    result.json()
            ));
        });
        context.setPacketHandled(true);
    }
}
