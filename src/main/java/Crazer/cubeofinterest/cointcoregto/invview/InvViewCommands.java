package Crazer.cubeofinterest.cointcoregto.invview;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InvViewCommands {
    private InvViewCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("inv")
                        .then(Commands.literal("view")
                                .requires(source -> source.hasPermission(2)
                                        || source.getEntity() instanceof ServerPlayer player
                                        && CointCoreGTO.hasPermissionNode(player, InvViewService.VIEW_PERMISSION))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            UUID viewerId = context.getSource().getEntity() instanceof ServerPlayer player
                                                    ? player.getUUID()
                                                    : null;
                                            return SharedSuggestionProvider.suggest(
                                                    InvViewService.getKnownPlayerNames(context.getSource().getServer(), viewerId),
                                                    builder
                                            );
                                        })
                                        .executes(context -> {
                                            ServerPlayer viewer = context.getSource().getPlayerOrException();
                                            String name = StringArgumentType.getString(context, "player");
                                            return InvViewService.open(viewer, name) ? 1 : 0;
                                        })))
        );
    }
}
