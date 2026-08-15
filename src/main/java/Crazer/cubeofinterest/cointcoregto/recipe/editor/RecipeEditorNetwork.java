package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class RecipeEditorNetwork {
    private static final String PROTOCOL = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "recipe_editor"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered;

    private RecipeEditorNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        int id = 0;
        CHANNEL.messageBuilder(RecipeEditorSavePacket.class, id++)
                .encoder(RecipeEditorSavePacket::encode)
                .decoder(RecipeEditorSavePacket::decode)
                .consumerMainThread(RecipeEditorSavePacket::handle)
                .add();

        CHANNEL.messageBuilder(RecipeEditorSaveResultPacket.class, id++)
                .encoder(RecipeEditorSaveResultPacket::encode)
                .decoder(RecipeEditorSaveResultPacket::decode)
                .consumerMainThread(RecipeEditorSaveResultPacket::handle)
                .add();

        CHANNEL.messageBuilder(RecipeEditorGhostUpdatePacket.class, id++)
                .encoder(RecipeEditorGhostUpdatePacket::encode)
                .decoder(RecipeEditorGhostUpdatePacket::decode)
                .consumerMainThread(RecipeEditorGhostUpdatePacket::handle)
                .add();

        CHANNEL.messageBuilder(RecipeEditorCraftingSyncPacket.class, id++)
                .encoder(RecipeEditorCraftingSyncPacket::encode)
                .decoder(RecipeEditorCraftingSyncPacket::decode)
                .consumerMainThread(RecipeEditorCraftingSyncPacket::handle)
                .add();

        CHANNEL.messageBuilder(RecipeEditorCraftingSyncRequestPacket.class, id)
                .encoder(RecipeEditorCraftingSyncRequestPacket::encode)
                .decoder(RecipeEditorCraftingSyncRequestPacket::decode)
                .consumerMainThread(RecipeEditorCraftingSyncRequestPacket::handle)
                .add();

        registered = true;
    }
}
