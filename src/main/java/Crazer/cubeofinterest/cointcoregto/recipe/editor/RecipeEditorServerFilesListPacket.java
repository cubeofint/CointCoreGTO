package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record RecipeEditorServerFilesListPacket(
        boolean crafting,
        boolean success,
        String message,
        List<RecipeEditorServerFileService.Entry> entries
) {
    private static final int MAX_MESSAGE = 2_048;
    private static final int MAX_PATH = 1_024;
    private static final int MAX_FIELD = 256;

    public static void encode(RecipeEditorServerFilesListPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.crafting);
        buffer.writeBoolean(packet.success);
        buffer.writeUtf(packet.message == null ? "" : packet.message, MAX_MESSAGE);

        List<RecipeEditorServerFileService.Entry> values = packet.entries == null ? List.of() : packet.entries;
        int count = Math.min(values.size(), RecipeEditorServerFileService.MAX_LIST_ENTRIES);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            RecipeEditorServerFileService.Entry entry = values.get(i);
            buffer.writeUtf(entry.relativePath(), MAX_PATH);
            buffer.writeUtf(entry.recipeId(), MAX_FIELD);
            buffer.writeUtf(entry.recipeType(), MAX_FIELD);
            buffer.writeLong(entry.size());
            buffer.writeLong(entry.modified());
            buffer.writeBoolean(entry.validJson());
        }
    }

    public static RecipeEditorServerFilesListPacket decode(FriendlyByteBuf buffer) {
        boolean crafting = buffer.readBoolean();
        boolean success = buffer.readBoolean();
        String message = buffer.readUtf(MAX_MESSAGE);
        int count = Math.max(0, Math.min(buffer.readVarInt(), RecipeEditorServerFileService.MAX_LIST_ENTRIES));
        List<RecipeEditorServerFileService.Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new RecipeEditorServerFileService.Entry(
                    buffer.readUtf(MAX_PATH),
                    buffer.readUtf(MAX_FIELD),
                    buffer.readUtf(MAX_FIELD),
                    buffer.readLong(),
                    buffer.readLong(),
                    buffer.readBoolean()
            ));
        }
        return new RecipeEditorServerFilesListPacket(crafting, success, message, List.copyOf(entries));
    }

    public static void handle(RecipeEditorServerFilesListPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> RecipeEditorClient.handleServerFilesList(packet)
        ));
        context.setPacketHandled(true);
    }
}
