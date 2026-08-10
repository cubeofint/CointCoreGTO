package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorGhostUpdatePacket(int slot, ItemStack stack) {
    public static void encode(RecipeEditorGhostUpdatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.slot);
        buffer.writeItem(packet.stack == null ? ItemStack.EMPTY : packet.stack);
    }

    public static RecipeEditorGhostUpdatePacket decode(FriendlyByteBuf buffer) {
        return new RecipeEditorGhostUpdatePacket(buffer.readVarInt(), buffer.readItem());
    }

    public static void handle(RecipeEditorGhostUpdatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !RecipeEditorItem.canEdit(player)) {
                return;
            }
            if (player.containerMenu instanceof RecipeEditorMenu menu) {
                if (RecipeEditorMenu.isGhostSlot(packet.slot)) {
                    menu.setGhostItem(packet.slot, packet.stack);
                }
                return;
            }
            if (player.containerMenu instanceof CraftingRecipeEditorMenu menu
                    && CraftingRecipeEditorMenu.isGhostSlot(packet.slot)) {
                menu.setGhostItem(packet.slot, packet.stack);
            }
        });
        context.setPacketHandled(true);
    }
}