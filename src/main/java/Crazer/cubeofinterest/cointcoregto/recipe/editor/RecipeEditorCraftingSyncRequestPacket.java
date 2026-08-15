package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -> server request used after the client has actually entered the world.
 * The server replies with both crafting and GT/GTO recipe sets. Keeping this
 * request client-driven avoids racing EMI's initial reload.
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
                RecipeEditorGtoSyncService.sendTo(player);
            }
        });
        context.setPacketHandled(true);
    }
}
