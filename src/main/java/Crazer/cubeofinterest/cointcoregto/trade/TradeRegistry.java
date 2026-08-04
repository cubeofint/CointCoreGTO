package Crazer.cubeofinterest.cointcoregto.trade;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class TradeRegistry {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<MenuType<TradeMenu>> TRADE_MENU = MENUS.register(
            "player_trade",
            () -> IForgeMenuType.create((windowId, inventory, data) -> new TradeMenu(
                    windowId,
                    inventory,
                    data.readUUID(),
                    data.readBoolean() ? TradeSide.INITIATOR : TradeSide.TARGET
            ))
    );

    private TradeRegistry() {
    }

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
