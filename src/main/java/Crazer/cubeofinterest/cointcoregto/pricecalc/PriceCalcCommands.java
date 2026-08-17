package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PriceCalcCommands {
    public static final String PERMISSION = "cointcoregto.pricecalc.command";

    private PriceCalcCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cointprice")
                        .requires(PriceCalcCommands::hasAccess)
                        .then(Commands.literal("reload")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.RELOAD)))
                        .then(Commands.literal("clear")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.CLEAR)))
                        .then(Commands.literal("calc")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.CALC)))
                        .then(Commands.literal("tooltip")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.TOGGLE_TOOLTIP)))
                        .then(Commands.literal("blacklist")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.OPEN_BLACKLIST)))
                        .then(Commands.literal("status")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.SHOW_STATUS)))
                        .then(Commands.literal("system")
                                .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.TOGGLE_SYSTEM))
                                .then(Commands.literal("on")
                                        .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.ENABLE_SYSTEM)))
                                .then(Commands.literal("off")
                                        .executes(context -> execute(context.getSource(), PriceCalcCommandPacket.Action.DISABLE_SYSTEM))))
        );
    }

    private static boolean hasAccess(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        return source.hasPermission(2) || hasAccess(player);
    }

    static boolean hasAccess(ServerPlayer player) {
        return player.hasPermissions(2) || CointCoreGTO.hasPermissionNode(player, PERMISSION);
    }

    private static int execute(CommandSourceStack source, PriceCalcCommandPacket.Action action) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception ignored) {
            return 0;
        }
        PriceCalcNetwork.sendAccess(player, true);
        PriceCalcNetwork.sendTo(player, action);
        return 1;
    }
}
