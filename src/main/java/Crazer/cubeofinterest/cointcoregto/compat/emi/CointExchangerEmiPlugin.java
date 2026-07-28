package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerBlockEntity;
import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerScreen;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.world.item.ItemStack;

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
    }

    private static ItemStack toItemStack(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack stack = emiStack.getItemStack();
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack result = stack.copy();
            long amount = Math.max(1L, emiStack.getAmount());
            int maximum = Math.max(1, Math.min(64, result.getMaxStackSize()));
            result.setCount((int) Math.min(amount, maximum));
            return result;
        }
        return ItemStack.EMPTY;
    }
}