package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server request used after the client has actually entered the world.
 *
 * The server also sends recipes on PlayerLoggedInEvent, but that packet can arrive
 * while EMI is still doing its first reload. This request gives us a deterministic
 * second sync once the client connection/world are ready.
 */
public record RecipeEditorCraftingSyncRequestPacket() {
    public static void encode(RecipeEditorCraftingSyncRequestPacket packet, FriendlyByteBuf buffer) {
        // No payload.
    }

    public static RecipeEditorCraftingSyncRequestPacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorCraftingSyncRequestPacket();
    }

    public static void handle(
            RecipeEditorCraftingSyncRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                RecipeEditorCraftingSyncService.sendTo(player);
            }
        });
        context.setPacketHandled(true);
    }
}
