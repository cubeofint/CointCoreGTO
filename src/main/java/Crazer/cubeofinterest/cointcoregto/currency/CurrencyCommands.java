package Crazer.cubeofinterest.cointcoregto.currency;

import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerProgression;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

public final class CurrencyCommands {
    private CurrencyCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("currency")
                        .then(Commands.literal("balance")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> showOwnBalance(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
        );
        dispatcher.register(
                Commands.literal("coins")
                        .requires(source -> source.getEntity() instanceof ServerPlayer)
                        .executes(context -> showOwnBalance(context.getSource()))
        );
        dispatcher.register(
                Commands.literal("currencyadmin")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("reload")
                                .executes(context -> reload(context.getSource())))
                        .then(Commands.literal("balance")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> showBalance(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player")
                                        ))))
                        .then(Commands.literal("tier")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> showTier(
                                                context.getSource(),
                                                EntityArgument.getPlayer(context, "player")
                                        ))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(context -> add(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "amount")
                                                )))))
                        .then(Commands.literal("take")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(context -> take(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "amount")
                                                )))))
                        .then(Commands.literal("set")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .then(Commands.argument("amount", StringArgumentType.word())
                                                .executes(context -> set(
                                                        context.getSource(),
                                                        EntityArgument.getPlayer(context, "player"),
                                                        StringArgumentType.getString(context, "amount")
                                                )))))
                        .then(Commands.literal("transfer")
                                .then(Commands.argument("source", EntityArgument.player())
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .then(Commands.argument("amount", StringArgumentType.word())
                                                        .executes(context -> transfer(
                                                                context.getSource(),
                                                                EntityArgument.getPlayer(context, "source"),
                                                                EntityArgument.getPlayer(context, "target"),
                                                                StringArgumentType.getString(context, "amount")
                                                        ))))))
        );
    }

    private static int showOwnBalance(CommandSourceStack source) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(Component.literal("§cКоманда доступна только игроку."));
            return 0;
        }
        return showBalance(source, player);
    }

    private static int showBalance(CommandSourceStack source, ServerPlayer player) {
        CurrencyBalance balance = CurrencyService.balance(player.getUUID());
        if (!balance.success()) {
            source.sendFailure(Component.literal("§cНе удалось получить баланс: " + balance.message()));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "§aБаланс §f" + player.getGameProfile().getName()
                                + "§a: §e" + CurrencyService.format(balance.amount())
                ),
                false
        );
        return 1;
    }

    private static int showTier(CommandSourceStack source, ServerPlayer player) {
        int tierIndex = ExchangerProgression.playerTier(player);
        String tier = ExchangerProgression.tierDisplayName(
                CurrencyConfig.exchangerTierOrder(),
                tierIndex
        );
        source.sendSuccess(
                () -> Component.literal(
                        "§aЭпоха §f" + player.getGameProfile().getName()
                                + "§a: §e" + tier
                                + " §7(index=" + tierIndex + ")"
                ),
                false
        );
        return tierIndex >= 0 ? 1 : 0;
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(
                () -> Component.literal(
                        "§eВалютная система: §f" + CurrencyService.statusText()
                                + "§7; registered=" + String.join(",", CurrencyApi.providerIds())
                ),
                false
        );
        return CurrencyService.available() ? 1 : 0;
    }

    private static int reload(CommandSourceStack source) {
        CurrencyConfig.reload();
        CurrencyService.reload();
        if (!CurrencyService.available()) {
            source.sendFailure(Component.literal("§cВалютная система недоступна: " + CurrencyService.lastError()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§aВалютная система перезагружена: §f" + CurrencyService.statusText()), true);
        return 1;
    }

    private static int add(CommandSourceStack source, ServerPlayer player, String amountText) {
        Long amount = parseAmount(source, amountText);
        if (amount == null || amount <= 0L) {
            return 0;
        }
        UUID operationId = UUID.randomUUID();
        CurrencyOperationResult result = CurrencyService.credit(
                player.getUUID(),
                amount,
                operationId,
                adminContext(source, "admin add", operationId, player)
        );
        return report(source, result, player, "начислено", amount);
    }

    private static int take(CommandSourceStack source, ServerPlayer player, String amountText) {
        Long amount = parseAmount(source, amountText);
        if (amount == null || amount <= 0L) {
            return 0;
        }
        UUID operationId = UUID.randomUUID();
        CurrencyOperationResult result = CurrencyService.debit(
                player.getUUID(),
                amount,
                operationId,
                adminContext(source, "admin take", operationId, player)
        );
        return report(source, result, player, "списано", amount);
    }

    private static int set(CommandSourceStack source, ServerPlayer player, String amountText) {
        Long desired = parseAmount(source, amountText);
        if (desired == null) {
            return 0;
        }
        CurrencyBalance current = CurrencyService.balance(player.getUUID());
        if (!current.success()) {
            source.sendFailure(Component.literal("§cНе удалось получить баланс: " + current.message()));
            return 0;
        }
        if (current.amount() == desired) {
            source.sendSuccess(() -> Component.literal("§eБаланс уже равен §f" + CurrencyService.format(desired)), false);
            return 1;
        }

        long delta = Math.abs(desired - current.amount());
        UUID operationId = UUID.randomUUID();
        CurrencyContext context = adminContext(source, "admin set", operationId, player);
        CurrencyOperationResult result = desired > current.amount()
                ? CurrencyService.credit(player.getUUID(), delta, operationId, context)
                : CurrencyService.debit(player.getUUID(), delta, operationId, context);
        if (!result.success()) {
            source.sendFailure(Component.literal("§cОперация отклонена: " + result.message() + " [" + result.code() + "]"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "§aБаланс §f" + player.getGameProfile().getName()
                                + "§a установлен: §e" + CurrencyService.format(desired)
                                + " §7(" + operationId + ")"
                ),
                true
        );
        return 1;
    }

    private static int transfer(
            CommandSourceStack source,
            ServerPlayer sourcePlayer,
            ServerPlayer targetPlayer,
            String amountText
    ) {
        Long amount = parseAmount(source, amountText);
        if (amount == null || amount <= 0L) {
            return 0;
        }
        UUID operationId = UUID.randomUUID();
        CurrencyOperationResult result = CurrencyService.transfer(
                sourcePlayer.getUUID(),
                targetPlayer.getUUID(),
                amount,
                operationId,
                CurrencyService.context(
                        source.getEntity() == null ? null : source.getEntity().getUUID(),
                        source.getTextName(),
                        "admin transfer",
                        "ADMIN_COMMAND",
                        operationId.toString(),
                        Map.of(
                                "source", sourcePlayer.getUUID().toString(),
                                "target", targetPlayer.getUUID().toString()
                        )
                )
        );
        if (!result.success()) {
            source.sendFailure(Component.literal("§cПеревод отклонён: " + result.message() + " [" + result.code() + "]"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "§aПереведено §e" + CurrencyService.format(amount)
                                + "§a: §f" + sourcePlayer.getGameProfile().getName()
                                + " §7-> §f" + targetPlayer.getGameProfile().getName()
                                + " §7(" + operationId + ")"
                ),
                true
        );
        return 1;
    }

    private static CurrencyContext adminContext(
            CommandSourceStack source,
            String reason,
            UUID operationId,
            ServerPlayer target
    ) {
        return CurrencyService.context(
                source.getEntity() == null ? null : source.getEntity().getUUID(),
                source.getTextName(),
                reason,
                "ADMIN_COMMAND",
                operationId.toString(),
                Map.of("target", target.getUUID().toString())
        );
    }

    private static Long parseAmount(CommandSourceStack source, String amountText) {
        try {
            return CurrencyAmounts.parse(amountText, CurrencyService.descriptor());
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.literal("§c" + exception.getMessage()));
            return null;
        }
    }

    private static int report(
            CommandSourceStack source,
            CurrencyOperationResult result,
            ServerPlayer player,
            String action,
            long amount
    ) {
        if (!result.success()) {
            source.sendFailure(Component.literal("§cОперация отклонена: " + result.message() + " [" + result.code() + "]"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal(
                        "§aИгроку §f" + player.getGameProfile().getName()
                                + "§a " + action + " §e" + CurrencyService.format(amount)
                                + "§a. Баланс: §e" + CurrencyService.format(result.targetBalance() > 0L
                                ? result.targetBalance()
                                : result.sourceBalance())
                                + " §7(" + result.operationId() + ")"
                ),
                true
        );
        return 1;
    }
}
