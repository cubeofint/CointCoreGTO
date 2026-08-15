package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client transfer of the authoritative config/cointcoregto/gto_recipes JSON files. */
public record RecipeEditorGtoSyncPacket(Action action, String json) {
    static final int MAX_JSON_LENGTH = 256_000;

    public enum Action {
        RESET,
        ENTRY,
        APPLY
    }

    public RecipeEditorGtoSyncPacket {
        action = action == null ? Action.RESET : action;
        json = json == null ? "" : json;
    }

    public static void encode(RecipeEditorGtoSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeByte(packet.action.ordinal());
        buffer.writeUtf(packet.json, MAX_JSON_LENGTH);
    }

    public static RecipeEditorGtoSyncPacket decode(FriendlyByteBuf buffer) {
        int ordinal = buffer.readUnsignedByte();
        Action[] values = Action.values();
        Action action = ordinal >= 0 && ordinal < values.length ? values[ordinal] : Action.RESET;
        return new RecipeEditorGtoSyncPacket(action, buffer.readUtf(MAX_JSON_LENGTH));
    }

    public static void handle(
            RecipeEditorGtoSyncPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleGtoSync(packet)
        ));
        context.setPacketHandled(true);
    }
}
