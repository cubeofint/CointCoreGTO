package Crazer.cubeofinterest.cointcoregto.battlepass;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collection;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BattlePassEvents {
    private BattlePassEvents() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        BattlePassConfig.reload();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BattlePassService.touchPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("battlepass")
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    BattlePassService.sendState(player, "");
                    return 1;
                })
                .then(Commands.literal("reload")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> {
                            BattlePassConfig.reload();
                            for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                                BattlePassService.sendState(player, "Конфигурация Battle Pass обновлена.");
                            }
                            context.getSource().sendSuccess(
                                    () -> Component.literal("Конфигурация Battle Pass перезагружена."),
                                    true
                            );
                            return 1;
                        }))
                .then(Commands.literal("setday")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument(
                                                "day",
                                                IntegerArgumentType.integer(1, BattlePassConfig.maxSupportedDays())
                                        )
                                        .executes(context -> {
                                            Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
                                            int requestedDay = IntegerArgumentType.getInteger(context, "day");
                                            int actualDay = requestedDay;
                                            for (ServerPlayer target : targets) {
                                                actualDay = BattlePassService.setPlayerDay(target, requestedDay);
                                                target.sendSystemMessage(Component.literal(
                                                        "Администратор установил вам день Battle Pass: " + actualDay + "."
                                                ));
                                            }
                                            int finalDay = actualDay;
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(
                                                            "День Battle Pass " + finalDay
                                                                    + " установлен для игроков: " + targets.size() + "."
                                                    ),
                                                    true
                                            );
                                            return targets.size();
                                        }))))
                .then(Commands.literal("resetclaim")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument(
                                                "day",
                                                IntegerArgumentType.integer(1, BattlePassConfig.maxSupportedDays())
                                        )
                                        .executes(context -> resetClaim(
                                                context,
                                                BattlePassService.ClaimTrack.ALL
                                        ))
                                        .then(Commands.literal("free")
                                                .executes(context -> resetClaim(
                                                        context,
                                                        BattlePassService.ClaimTrack.FREE
                                                )))
                                        .then(Commands.literal("premium")
                                                .executes(context -> resetClaim(
                                                        context,
                                                        BattlePassService.ClaimTrack.PREMIUM
                                                )))
                                        .then(Commands.literal("all")
                                                .executes(context -> resetClaim(
                                                        context,
                                                        BattlePassService.ClaimTrack.ALL
                                                ))))))
                .then(Commands.literal("resetclaims")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(context -> {
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
                                    BattlePassService.ResetAllClaimsResult result =
                                            BattlePassService.resetAllClaimed(targets);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "Сброшены все отметки получения Battle Pass для игроков: "
                                                            + result.players() + ". Изменено отметок: "
                                                            + result.resetFlags() + "."
                                            ),
                                            true
                                    );
                                    return Math.max(1, result.players());
                                })))
                .then(Commands.literal("setdayall")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument(
                                        "day",
                                        IntegerArgumentType.integer(1, BattlePassConfig.maxSupportedDays())
                                )
                                .executes(context -> {
                                    int requestedDay = IntegerArgumentType.getInteger(context, "day");
                                    BattlePassService.SetAllResult result = BattlePassService.setAllPlayersDay(
                                            context.getSource().getServer(),
                                            requestedDay
                                    );
                                    for (ServerPlayer player : context.getSource().getServer().getPlayerList().getPlayers()) {
                                        player.sendSystemMessage(Component.literal(
                                                "Для всех игроков установлен день Battle Pass: " + result.day() + "."
                                        ));
                                    }
                                    context.getSource().sendSuccess(
                                            () -> Component.literal(
                                                    "День Battle Pass " + result.day()
                                                            + " установлен глобально. Обновлено сохранённых игроков: "
                                                            + result.storedPlayers()
                                                            + ", онлайн: " + result.onlinePlayers()
                                                            + ". Новые игроки текущего месяца начнут с этого дня с учётом прошедших суток."
                                            ),
                                            true
                                    );
                                    return Math.max(1, result.storedPlayers());
                                })))
        );
    }

    private static int resetClaim(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            BattlePassService.ClaimTrack track
    ) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<ServerPlayer> targets = EntityArgument.getPlayers(context, "targets");
        int requestedDay = IntegerArgumentType.getInteger(context, "day");
        BattlePassService.ResetClaimResult result = BattlePassService.resetClaimed(
                targets,
                requestedDay,
                track
        );
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Сброшена отметка за день " + result.day()
                                + " (" + result.track().displayName() + ") для игроков: "
                                + result.players() + ". Изменено отметок: "
                                + result.resetFlags() + "."
                ),
                true
        );
        return Math.max(1, result.players());
    }
}
