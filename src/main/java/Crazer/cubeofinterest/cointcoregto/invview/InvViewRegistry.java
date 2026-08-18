package Crazer.cubeofinterest.cointcoregto.invview;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class InvViewRegistry {
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<MenuType<InvViewMenu>> MENU = MENUS.register(
            "inv_view",
            () -> IForgeMenuType.create(InvViewMenu::new)
    );

    private InvViewRegistry() {
    }

    public static void register(IEventBus eventBus) {
        MENUS.register(eventBus);
    }
}
