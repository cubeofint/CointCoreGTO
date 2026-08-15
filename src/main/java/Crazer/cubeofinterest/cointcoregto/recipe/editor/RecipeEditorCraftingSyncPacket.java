package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record RecipeEditorCraftingSyncPacket(Action action, String json) {
    static final int MAX_JSON_LENGTH = 64_000;

    public enum Action {
        RESET,
        ENTRY,
        APPLY
    }

    public RecipeEditorCraftingSyncPacket {
        action = action == null ? Action.RESET : action;
        json = json == null ? "" : json;
    }

    public static void encode(RecipeEditorCraftingSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeByte(packet.action.ordinal());
        buffer.writeUtf(packet.json, MAX_JSON_LENGTH);
    }

    public static RecipeEditorCraftingSyncPacket decode(FriendlyByteBuf buffer) {
        int ordinal = buffer.readUnsignedByte();
        Action[] values = Action.values();
        Action action = ordinal >= 0 && ordinal < values.length ? values[ordinal] : Action.RESET;
        return new RecipeEditorCraftingSyncPacket(action, buffer.readUtf(MAX_JSON_LENGTH));
    }

    public static void handle(
            RecipeEditorCraftingSyncPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleCraftingSync(packet)
        ));
        context.setPacketHandled(true);
    }
}
