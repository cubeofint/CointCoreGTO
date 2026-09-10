package Crazer.cubeofinterest.cointcoregto.bosssim;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;

@Mod.EventBusSubscriber(modid = BossSimulationMode.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BossSimulationItems {
    private BossSimulationItems() {}

    @SubscribeEvent
    public static void registerItems(RegisterEvent event) {
        event.register(ForgeRegistries.Keys.ITEMS, helper -> {
            helper.register(
                    new ResourceLocation(BossSimulationMode.MOD_ID, "overworld_simulation"),
                    selector("Симуляция Овера")
            );
            helper.register(
                    new ResourceLocation(BossSimulationMode.MOD_ID, "nether_simulation"),
                    selector("Симуляция Ада")
            );
            helper.register(
                    new ResourceLocation(BossSimulationMode.MOD_ID, "end_simulation"),
                    selector("Симуляция Энда")
            );
        });
    }

    private static Item selector(String name) {
        return new NamedSelectorItem(
                new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON),
                name
        );
    }
}
