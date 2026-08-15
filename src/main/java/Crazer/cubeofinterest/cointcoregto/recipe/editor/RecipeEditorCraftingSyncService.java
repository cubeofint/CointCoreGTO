package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public final class RecipeEditorCraftingSyncService {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:RecipeSync");
    private static final int MAX_FILES = 4_096;

    private RecipeEditorCraftingSyncService() {
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) {
            return;
        }

        RecipeEditorNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new RecipeEditorCraftingSyncPacket(RecipeEditorCraftingSyncPacket.Action.RESET, "")
        );

        int sent = 0;
        int failed = 0;

        try {
            Files.createDirectories(CraftingRecipeLoader.RECIPE_DIRECTORY);
            List<Path> files;
            try (Stream<Path> stream = Files.walk(CraftingRecipeLoader.RECIPE_DIRECTORY)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .limit(MAX_FILES)
                        .toList();
            }

            for (Path file : files) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    if (json.isBlank() || json.length() > RecipeEditorCraftingSyncPacket.MAX_JSON_LENGTH) {
                        failed++;
                        continue;
                    }

                    RecipeEditorNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new RecipeEditorCraftingSyncPacket(RecipeEditorCraftingSyncPacket.Action.ENTRY, json)
                    );
                    sent++;
                } catch (Throwable throwable) {
                    failed++;
                    LOGGER.warn("Unable to sync crafting recipe file {} to {}",
                            file,
                            player.getGameProfile().getName(),
                            throwable);
                }
            }
        } catch (Throwable throwable) {
            failed++;
            LOGGER.error("Unable to enumerate server crafting recipes for {}",
                    player.getGameProfile().getName(),
                    throwable);
        }

        RecipeEditorNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new RecipeEditorCraftingSyncPacket(RecipeEditorCraftingSyncPacket.Action.APPLY, "")
        );

        LOGGER.info("Synced {} crafting recipe files to {} (failed={})",
                sent,
                player.getGameProfile().getName(),
                failed);
    }
}
