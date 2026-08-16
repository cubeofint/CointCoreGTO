package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class PriceCalcClientCommands {
    private PriceCalcClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cointprice")
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    PriceCalcStorage.reloadAndInvalidateComputed();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a[PriceCalc] Конфиги перечитаны, вычисленный кеш очищен: §f" + PriceCalcStorage.getDirectory()),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    PriceCalcStorage.clearComputed();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a[PriceCalc] Вычисленные цены очищены."),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("calc")
                                .executes(ctx -> {
                                    PriceCalcClient.calculateHoveredOrHeld();
                                    return 1;
                                }))
        );
    }
}
