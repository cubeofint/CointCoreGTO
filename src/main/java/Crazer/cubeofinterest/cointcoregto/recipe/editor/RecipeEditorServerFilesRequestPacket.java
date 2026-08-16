package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

public record RecipeEditorServerFilesRequestPacket(boolean crafting) {
    public static void encode(RecipeEditorServerFilesRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
    }

    public static RecipeEditorServerFilesRequestPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorServerFilesRequestPacket(buffer.readBoolean());
    }

    public static void handle(RecipeEditorServerFilesRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (!allowed(player, packet.crafting)) {
                send(player, new RecipeEditorServerFilesListPacket(packet.crafting, false, "Недостаточно прав", List.of()));
                return;
            }

            try {
                send(player, new RecipeEditorServerFilesListPacket(
                        packet.crafting,
                        true,
                        "Файлы рецептов загружены с сервера",
                        RecipeEditorServerFileService.list(packet.crafting)
                ));
            } catch (Throwable throwable) {
                send(player, new RecipeEditorServerFilesListPacket(
                        packet.crafting,
                        false,
                        throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage(),
                        List.of()
                ));
            }
        });
        context.setPacketHandled(true);
    }

    static boolean allowed(ServerPlayer player, boolean crafting) {
        return player != null && RecipeEditorItem.canEdit(player);
    }

    static void send(ServerPlayer player, Object packet) {
        if (player == null) {
            return;
        }
        RecipeEditorNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
