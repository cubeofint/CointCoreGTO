package Crazer.cubeofinterest.cointcoregto.supply;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

/**
 * Supply Buffer EMI integration. Registration is called from the mod's
 * long-standing CointExchangerEmiPlugin entrypoint, so Forge/EMI only has one
 * entrypoint responsible for all CointCoreGTO drag/drop handlers.
 */
public final class SupplyBufferEmiPlugin {
    private SupplyBufferEmiPlugin() {
    }

    public static void registerHandlers(EmiRegistry registry) {
        registry.addDragDropHandler(
                SupplyBufferScreen.class,
                new EmiDragDropHandler.BoundsBased<SupplyBufferScreen>((screen, addTarget) -> {
                    if (!screen.canAcceptEmiFilterDrops()) {
                        return;
                    }

                    for (int filterIndex = 0; filterIndex < SupplyBufferMenu.FILTER_COUNT; filterIndex++) {
                        final int index = filterIndex;
                        int x = screen.getFilterScreenX(index);
                        int size = screen.getFilterSlotSize();

                        addTarget.accept(
                                new Bounds(x, screen.getItemFilterScreenY(), size, size),
                                ingredient -> setItemFilter(screen, index, ingredient)
                        );
                        addTarget.accept(
                                new Bounds(x, screen.getFluidFilterScreenY(), size, size),
                                ingredient -> setFluidFilter(screen, index, ingredient)
                        );
                    }
                })
        );
    }

    private static void setItemFilter(
            SupplyBufferScreen screen,
            int filterIndex,
            EmiIngredient ingredient
    ) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        for (EmiStack stack : ingredient.getEmiStacks()) {
            ItemStack itemStack = stack.getItemStack();
            if (!itemStack.isEmpty()
                    && screen.setItemFilterFromExternal(filterIndex, itemStack)) {
                return;
            }
        }
    }

    private static void setFluidFilter(
            SupplyBufferScreen screen,
            int filterIndex,
            EmiIngredient ingredient
    ) {
        if (ingredient == null || ingredient.isEmpty()) {
            return;
        }
        for (EmiStack stack : ingredient.getEmiStacks()) {
            Object key = stack.getKey();
            if (key instanceof Fluid fluid && fluid != Fluids.EMPTY) {
                FluidStack fluidStack = new FluidStack(fluid, 1);
                if (stack.hasNbt() && stack.getNbt() != null) {
                    fluidStack.setTag(stack.getNbt().copy());
                }
                if (screen.setFluidFilterFromExternal(filterIndex, fluidStack)) {
                    return;
                }
            }

            ItemStack itemStack = stack.getItemStack();
            if (!itemStack.isEmpty()) {
                FluidStack contained = FluidUtil.getFluidContained(itemStack).orElse(FluidStack.EMPTY);
                if (!contained.isEmpty()
                        && screen.setFluidFilterFromExternal(filterIndex, contained)) {
                    return;
                }
            }
        }
    }
}
