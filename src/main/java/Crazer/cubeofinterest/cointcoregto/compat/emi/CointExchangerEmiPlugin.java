package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerBlockEntity;
import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorScreen;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

@EmiEntrypoint
public final class CointExchangerEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(
                ExchangerScreen.class,
                new EmiDragDropHandler.BoundsBased<ExchangerScreen>((screen, addTarget) -> {
                    if (!screen.canAcceptEmiTemplates()) {
                        return;
                    }

                    addTarget.accept(
                            new Bounds(
                                    screen.getTemplateSlotScreenX(ExchangerBlockEntity.SLOT_PRODUCT) - 1,
                                    screen.getTemplateSlotScreenY(ExchangerBlockEntity.SLOT_PRODUCT) - 1,
                                    18,
                                    18
                            ),
                            ingredient -> screen.setTemplateFromEmi(
                                    ExchangerBlockEntity.SLOT_PRODUCT,
                                    toItemStack(ingredient)
                            )
                    );

                    addTarget.accept(
                            new Bounds(
                                    screen.getTemplateSlotScreenX(ExchangerBlockEntity.SLOT_PRICE) - 1,
                                    screen.getTemplateSlotScreenY(ExchangerBlockEntity.SLOT_PRICE) - 1,
                                    18,
                                    18
                            ),
                            ingredient -> screen.setTemplateFromEmi(
                                    ExchangerBlockEntity.SLOT_PRICE,
                                    toItemStack(ingredient)
                            )
                    );
                })
        );

        registry.addDragDropHandler(
                RecipeEditorScreen.class,
                new EmiDragDropHandler.BoundsBased<RecipeEditorScreen>((screen, addTarget) -> {
                    
                    for (int index = 0; index < RecipeEditorMenu.GHOST_SLOT_COUNT; index++) {
                        final int slotIndex = index;
                        addTarget.accept(
                                new Bounds(
                                        screen.getItemTargetScreenX(slotIndex),
                                        screen.getItemTargetScreenY(slotIndex),
                                        screen.getItemTargetWidth(),
                                        screen.getItemTargetHeight()
                                ),
                                ingredient -> {
                                    ItemDrop item = toItemDrop(ingredient);
                                    if (item != null) {
                                        screen.setItemFromEmi(slotIndex, item.stack(), item.amount());
                                    }
                                }
                        );
                    }

                    
                    for (int index = 0; index < RecipeEditorScreen.FLUID_SLOT_COUNT; index++) {
                        final int fluidIndex = index;
                        addTarget.accept(
                                new Bounds(
                                        screen.getFluidTargetScreenX(fluidIndex),
                                        screen.getFluidTargetScreenY(fluidIndex),
                                        screen.getFluidTargetWidth(),
                                        screen.getFluidTargetHeight()
                                ),
                                ingredient -> {
                                    FluidDrop fluid = toFluid(ingredient);
                                    if (fluid != null) {
                                        screen.setFluidFromEmi(fluidIndex, fluid.id(), fluid.amount());
                                    }
                                }
                        );
                    }
                })
        );

        registry.addDragDropHandler(
                CraftingRecipeEditorScreen.class,
                new EmiDragDropHandler.BoundsBased<CraftingRecipeEditorScreen>((screen, addTarget) -> {
                    for (int index = 0; index < CraftingRecipeEditorMenu.GHOST_SLOT_COUNT; index++) {
                        final int slotIndex = index;
                        addTarget.accept(
                                new Bounds(
                                        screen.getItemTargetScreenX(slotIndex),
                                        screen.getItemTargetScreenY(slotIndex),
                                        18,
                                        18
                                ),
                                ingredient -> {
                                    ItemDrop item = toItemDrop(ingredient);
                                    if (item != null) {
                                        screen.setItemFromEmi(slotIndex, item.stack(), item.amount());
                                    }
                                }
                        );
                    }
                })
        );
    }

    private static ItemDrop toItemDrop(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack stack = emiStack.getItemStack();
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack template = stack.copy();
            template.setCount(1);

            long amount = emiStack.getAmount();
            if (amount <= 0L) {
                amount = Math.max(1, stack.getCount());
            }
            return new ItemDrop(template, Math.max(1L, amount));
        }
        return null;
    }

    private static FluidDrop toFluid(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            Object key = emiStack.getKey();
            if (!(key instanceof Fluid fluid)) {
                continue;
            }

            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null) {
                continue;
            }

            
            
            long amount = emiStack.getAmount();
            if (amount <= 0L) {
                amount = 1000L;
            }
            return new FluidDrop(id, amount);
        }
        return null;
    }

    private static ItemStack toItemStack(EmiIngredient ingredient) {
        ItemDrop drop = toItemDrop(ingredient);
        if (drop == null) {
            return ItemStack.EMPTY;
        }

        ItemStack result = drop.stack().copy();
        int maximum = Math.max(1, Math.min(64, result.getMaxStackSize()));
        result.setCount((int) Math.min(drop.amount(), maximum));
        return result;
    }

    private record ItemDrop(ItemStack stack, long amount) {
    }

    private record FluidDrop(ResourceLocation id, long amount) {
    }
}