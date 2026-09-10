package Crazer.cubeofinterest.cointcoregto.bosssim;

import com.gregtechceu.gtceu.api.machine.multiblock.WorkableMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.handler.RecipeHandlerUnit;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

public final class BossSimulationSelectorProbe {
    private BossSimulationSelectorProbe() {}

    public record Selection(BossSimulationMode mode, ItemStack selector, int distinctModes) {
        public boolean valid() {
            return mode != null && selector != null && !selector.isEmpty() && distinctModes == 1;
        }

        public static Selection none(int distinctModes) {
            return new Selection(null, ItemStack.EMPTY, distinctModes);
        }
    }

    public static Selection detect(WorkableMultiblockMachine machine, RecipeHandlerUnit currentUnit) {
        EnumSet<BossSimulationMode> modes = EnumSet.noneOf(BossSimulationMode.class);
        Map<BossSimulationMode, ItemStack> first = new EnumMap<>(BossSimulationMode.class);

        if (machine != null && machine.getInputUnits() != null) {
            for (RecipeHandlerUnit unit : machine.getInputUnits()) {
                scanUnit(unit, modes, first);
                if (modes.size() > 1) break;
            }
        }

        if (modes.isEmpty()) {
            scanUnit(currentUnit, modes, first);
        }

        if (modes.size() != 1) {
            return Selection.none(modes.size());
        }

        BossSimulationMode mode = modes.iterator().next();
        ItemStack selector = first.getOrDefault(mode, mode.selectorStack()).copy();
        selector.setCount(1);
        return new Selection(mode, selector, 1);
    }

    private static void scanUnit(
            RecipeHandlerUnit unit,
            EnumSet<BossSimulationMode> modes,
            Map<BossSimulationMode, ItemStack> first
    ) {
        if (unit == null || modes.size() > 1) return;

        unit.fastForEachItems(false, (stack, amount) -> {
            if (stack == null || stack.isEmpty()) return;
            BossSimulationMode.fromStack(stack).ifPresent(mode -> {
                modes.add(mode);
                first.computeIfAbsent(mode, ignored -> {
                    ItemStack copy = stack.copy();
                    copy.setCount(1);
                    return copy;
                });
            });
        });
    }
}
