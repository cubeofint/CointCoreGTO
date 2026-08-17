package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcCommandPacket;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

public final class PriceCalcClientPacketHandler {
    private PriceCalcClientPacketHandler() {
    }

    public static void handle(PriceCalcCommandPacket.Action action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        try {
            PriceCalcClientEvents.setAccessAllowed(true);
            switch (action) {
                case CALC -> {
                    if (!PriceCalcClientEvents.isSystemEnabled()) {
                        minecraft.player.displayClientMessage(
                                Component.literal("§e[PriceCalc] Система расчёта выключена. Используй §f/cointprice system on§e."),
                                false
                        );
                        return;
                    }
                    PriceCalcClient.calculateHoveredOrHeld();
                }
                case RELOAD -> {
                    Path backup = PriceCalcStorage.reloadAndInvalidateComputedSafely();
                    minecraft.player.displayClientMessage(
                            Component.literal(
                                    "§a[PriceCalc] Конфиги перечитаны, вычисленный кеш очищен. §7Бекап: §f"
                                            + backup.toAbsolutePath().normalize()
                            ),
                            false
                    );
                }
                case CLEAR -> {
                    Path backup = PriceCalcStorage.clearComputedSafely();
                    minecraft.player.displayClientMessage(
                            Component.literal(
                                    "§a[PriceCalc] Вычисленные цены очищены. §7Бекап: §f"
                                            + backup.toAbsolutePath().normalize()
                            ),
                            false
                    );
                }
                case TOGGLE_TOOLTIP -> {
                    if (!PriceCalcClientEvents.isSystemEnabled()) {
                        minecraft.player.displayClientMessage(
                                Component.literal("§e[PriceCalc] Сначала включи систему: §f/cointprice system on"),
                                false
                        );
                        return;
                    }
                    boolean enabled = PriceCalcClientEvents.toggleTooltip();
                    minecraft.player.displayClientMessage(
                            Component.literal(enabled
                                    ? "§a[PriceCalc] Подсказка цены включена."
                                    : "§e[PriceCalc] Подсказка цены выключена."),
                            false
                    );
                }
                case TOGGLE_SYSTEM -> showSystemState(minecraft, PriceCalcClientEvents.toggleSystem());
                case ENABLE_SYSTEM -> showSystemState(minecraft, PriceCalcClientEvents.setSystemEnabled(true));
                case DISABLE_SYSTEM -> showSystemState(minecraft, PriceCalcClientEvents.setSystemEnabled(false));
            }
        } catch (Throwable throwable) {
            minecraft.player.displayClientMessage(
                    Component.literal(
                            "§c[PriceCalc] Операция не выполнена. §7"
                                    + safeMessage(throwable)
                    ),
                    false
            );
        }
    }

    public static void setAccessAllowed(boolean allowed) {
        PriceCalcClientEvents.setAccessAllowed(allowed);
    }

    private static void showSystemState(Minecraft minecraft, boolean enabled) {
        if (minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(
                Component.literal(enabled
                        ? "§a[PriceCalc] Система расчёта включена. §7Клавиша P активна только в игре без открытых GUI."
                        : "§e[PriceCalc] Система расчёта выключена. §7PriceCalc не реагирует на P."),
                false
        );
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return message;
    }
}
