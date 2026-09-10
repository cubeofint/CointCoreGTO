package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.gui.widget.PatternPreviewWidget;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

public final class BossSimulationHologramHooks {

    private static final ResourceLocation CUSTOM_CASING =
            new ResourceLocation("cointcoregto", "boss_simulation_casing");
    private static final ResourceLocation GTO_FALLBACK_SOLID_CASING =
            new ResourceLocation("gtceu", "solid_machine_casing");

    private BossSimulationHologramHooks() {}

    public static ItemStack fixPreviewCloneStack(
            ItemStack original,
            PatternPreviewWidget widget,
            BlockState state) {

        if (widget == null ||
                widget.controllerDefinition != BossSimulationGTRegistration.getBossSimulationChamber()) {
            return original;
        }

        ResourceLocation stateId = state == null ? null : ForgeRegistries.BLOCKS.getKey(state.getBlock());
        ResourceLocation itemId = original == null || original.isEmpty()
                ? null
                : ForgeRegistries.ITEMS.getKey(original.getItem());

        if (CUSTOM_CASING.equals(stateId) || GTO_FALLBACK_SOLID_CASING.equals(itemId)) {
            return new ItemStack(BossSimulationBlocks.BOSS_SIMULATION_CASING_ITEM.get());
        }

        return original;
    }
}
