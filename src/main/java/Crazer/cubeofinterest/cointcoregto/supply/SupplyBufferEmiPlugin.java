package Crazer.cubeofinterest.cointcoregto.supply;

import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

@EmiEntrypoint
public final class SupplyBufferEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(
                SupplyBufferScreen.class,
                (screen, ingredient, mouseX, mouseY) -> handleDrop(screen, ingredient, mouseX, mouseY)
        );
    }

    private static boolean handleDrop(
            SupplyBufferScreen screen,
            EmiIngredient ingredient,
            int mouseX,
            int mouseY
    ) {
        if (ingredient == null || ingredient.isEmpty()) {
            return false;
        }

        int itemFilter = screen.itemFilterAt(mouseX, mouseY);
        if (itemFilter >= 0) {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                ItemStack itemStack = stack.getItemStack();
                if (!itemStack.isEmpty() && screen.setItemFilterFromExternal(itemFilter, itemStack)) {
                    return true;
                }
            }
            return false;
        }

        int fluidFilter = screen.fluidFilterAt(mouseX, mouseY);
        if (fluidFilter >= 0) {
            for (EmiStack stack : ingredient.getEmiStacks()) {
                Object key = stack.getKey();
                if (key instanceof Fluid fluid && fluid != Fluids.EMPTY) {
                    FluidStack fluidStack = new FluidStack(fluid, 1);
                    if (stack.hasNbt() && stack.getNbt() != null) {
                        fluidStack.setTag(stack.getNbt().copy());
                    }
                    if (screen.setFluidFilterFromExternal(fluidFilter, fluidStack)) {
                        return true;
                    }
                }

                ItemStack itemStack = stack.getItemStack();
                if (!itemStack.isEmpty()) {
                    FluidStack contained = FluidUtil.getFluidContained(itemStack).orElse(FluidStack.EMPTY);
                    if (!contained.isEmpty()
                            && screen.setFluidFilterFromExternal(fluidFilter, contained)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}
