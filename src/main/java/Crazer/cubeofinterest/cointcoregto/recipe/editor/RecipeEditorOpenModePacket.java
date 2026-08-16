package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorOpenModePacket(boolean crafting) {
    public static void encode(RecipeEditorOpenModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
    }

    public static RecipeEditorOpenModePacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorOpenModePacket(buffer.readBoolean());
    }

    public static void handle(RecipeEditorOpenModePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !RecipeEditorItem.canEdit(player)) {
                return;
            }
            if (packet.crafting) {
                RecipeEditorItem.openCraftingEditor(player);
            } else {
                RecipeEditorItem.openEditor(player, RecipeEditorMenu.DEFAULT_RECIPE_TYPE);
            }
        });
        context.setPacketHandled(true);
    }
}
