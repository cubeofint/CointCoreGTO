package Crazer.cubeofinterest.cointcoregto.trade;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TradeCommands {
    private TradeCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("trade")
                        .then(Commands.literal("accept")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(context -> respond(
                                                context.getSource(),
                                                TradeService.accept(
                                                        context.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(context, "player")
                                                )
                                        ))))
                        .then(Commands.literal("deny")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(context -> respond(
                                                context.getSource(),
                                                TradeService.deny(
                                                        context.getSource().getPlayerOrException(),
                                                        StringArgumentType.getString(context, "player")
                                                )
                                        ))))
                        .then(Commands.literal("open")
                                .executes(context -> respond(
                                        context.getSource(),
                                        TradeService.openActive(context.getSource().getPlayerOrException())
                                )))
                        .then(Commands.literal("cancel")
                                .executes(context -> respond(
                                        context.getSource(),
                                        TradeService.cancel(context.getSource().getPlayerOrException())
                                )))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> respond(
                                        context.getSource(),
                                        TradeService.invite(
                                                context.getSource().getPlayerOrException(),
                                                StringArgumentType.getString(context, "player")
                                        )
                                )))
        );

        dispatcher.register(
                Commands.literal("tradeadmin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(TradeService.available()
                                                    ? "§aTrade service active on node " + TradeService.nodeId()
                                                    : "§cTrade service unavailable: " + TradeService.lastError()),
                                            false
                                    );
                                    return TradeService.available() ? 1 : 0;
                                }))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    try {
                                        TradeConfig.reload();
                                        TradeService.start(context.getSource().getServer());
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("§aКонфиг и система обмена перезагружены."),
                                                false
                                        );
                                        return 1;
                                    } catch (Exception exception) {
                                        context.getSource().sendFailure(Component.literal("§c" + exception.getMessage()));
                                        return 0;
                                    }
                                }))
                        .then(Commands.literal("history")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(context -> history(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "player")
                                        ))))
                        .then(Commands.literal("inspect")
                                .then(Commands.argument("trade_id", StringArgumentType.word())
                                        .executes(context -> inspect(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "trade_id")
                                        ))))
        );
    }

    private static int respond(CommandSourceStack source, TradeService.OperationResult result) {
        if (result.success()) {
            source.sendSuccess(() -> Component.literal("§a" + result.message()), false);
            return 1;
        }
        source.sendFailure(Component.literal("§c" + result.message()));
        return 0;
    }

    private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Optional<TradeRecord> active = TradeService.active(player.getUUID());
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7Активной сделки нет."), false);
            return 0;
        }
        TradeRecord trade = active.get();
        TradeSide side = trade.sideOf(player.getUUID());
        source.sendSuccess(() -> Component.literal(
                "§bTrade " + trade.tradeId() + " §7| §f" + trade.name(side.opposite())
                        + " §7| статус: §f" + trade.status()
                        + " §7| готовность: §f" + trade.ready(side) + "/" + trade.ready(side.opposite())
        ), false);
        return 1;
    }

    private static int history(CommandSourceStack source, String playerName) {
        Optional<UUID> uuid = TradeService.playerUuidByName(playerName);
        if (uuid.isEmpty()) {
            source.sendFailure(Component.literal("§cИгрок не найден в таблице присутствия."));
            return 0;
        }
        List<String> lines = TradeService.history(uuid.get(), 10);
        if (lines.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§7История пуста."), false);
            return 0;
        }
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal("§7" + line), false);
        }
        return lines.size();
    }

    private static int inspect(CommandSourceStack source, String rawId) {
        UUID tradeId;
        try {
            tradeId = UUID.fromString(rawId);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("§cНекорректный UUID сделки."));
            return 0;
        }
        Optional<TradeRecord> optional = TradeService.find(tradeId);
        if (optional.isEmpty()) {
            source.sendFailure(Component.literal("§cСделка не найдена."));
            return 0;
        }
        TradeRecord trade = optional.get();
        source.sendSuccess(() -> Component.literal("§b" + trade.tradeId() + " §7| §f"
                + trade.initiatorName() + " <-> " + trade.targetName() + " §7| §f" + trade.status()), false);
        source.sendSuccess(() -> Component.literal("§7Currency: " + trade.initiatorCurrency()
                + " / " + trade.targetCurrency() + ", ready: " + trade.initiatorReady()
                + " / " + trade.targetReady() + ", prepared: " + trade.initiatorPrepared()
                + " / " + trade.targetPrepared() + ", delivered: " + trade.initiatorDelivered()
                + " / " + trade.targetDelivered()), false);
        if (!trade.errorText().isBlank()) {
            source.sendSuccess(() -> Component.literal("§c" + trade.errorText()), false);
        }
        return 1;
    }
}
