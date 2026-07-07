package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
public final class CointRadioClientCommands {
    private CointRadioClientCommands() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cointmusic")
                        .then(Commands.literal("play")
                                .then(Commands.argument("url", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String url = StringArgumentType.getString(ctx, "url").trim();

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("§a[CointMusic] Загружаю музыку..."),
                                                    false
                                            );

                                            CointRadioPlayer.play(
                                                    url,
                                                    message -> ctx.getSource().sendSuccess(() -> message, false)
                                            );

                                            return 1;
                                        })))

                        .then(Commands.literal("stop")
                                .executes(ctx -> {
                                    CointRadioPlayer.stop();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§c[CointMusic] Музыка остановлена."),
                                            false
                                    );
                                    return 1;
                                }))

                        .then(Commands.literal("volume")
                                .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                        .executes(ctx -> {
                                            int percent = IntegerArgumentType.getInteger(ctx, "percent");
                                            CointRadioPlayer.setVolumePercent(percent);

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("§a[CointMusic] Громкость: §f" + percent + "%"),
                                                    false
                                            );

                                            return 1;
                                        })))

                        .then(Commands.literal("clearcache")
                                .executes(ctx -> {
                                    CointRadioPlayer.clearCache();

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§a[CointMusic] Кеш музыки очищен."),
                                            false
                                    );

                                    return 1;
                                }))

                        .then(Commands.literal("cachestats")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§e[CointMusic] Cache: §f" + CointRadioPlayer.getCacheStats()),
                                            false
                                    );

                                    return 1;
                                }))
        );
    }
}