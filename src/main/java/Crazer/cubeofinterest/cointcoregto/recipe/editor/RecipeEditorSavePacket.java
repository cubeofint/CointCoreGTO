package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public record RecipeEditorSavePacket(String json) {
    private static final int MAX_JSON_LENGTH = 64_000;

    public static void encode(RecipeEditorSavePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUtf(packet.json == null ? "" : packet.json, MAX_JSON_LENGTH);
    }

    public static RecipeEditorSavePacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorSavePacket(buffer.readUtf(MAX_JSON_LENGTH));
    }

    public static void handle(RecipeEditorSavePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            if (!RecipeEditorItem.canEdit(player)) {
                player.sendSystemMessage(Component.literal("Recipe Editor доступен только в Creative или игрокам с правами OP."));
                RecipeEditorNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new RecipeEditorSaveResultPacket(false, "Недостаточно прав", "", "")
                );
                return;
            }

            RecipeEditorFileService.SaveResult result = RecipeEditorFileService.saveServerCopy(packet.json);
            RecipeEditorNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new RecipeEditorSaveResultPacket(
                            result.success(),
                            result.message(),
                            result.relativePath(),
                            result.normalizedJson()
                    )
            );
        });
        context.setPacketHandled(true);
    }
}