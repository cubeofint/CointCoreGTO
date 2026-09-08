package Crazer.cubeofinterest.cointcoregto.bosssim;

import Crazer.cubeofinterest.cointcoregto.bosssim.gt.BossSimulationChamberMachine;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeBuilder;
import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gregtechceu.gtceu.api.recipe.handler.RecipeHandlerUnit;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gregtechceu.gtceu.utils.GTUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class BossSimulationChamberRuntime {
    private static final ResourceLocation RUNTIME_RECIPE_ID =
            new ResourceLocation(BossSimulationMode.MOD_ID, "boss_simulation_chamber_runtime");

    private static final Map<Object, PendingSimulation> PENDING =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> REPORTED_ERRORS = ConcurrentHashMap.newKeySet();

    private BossSimulationChamberRuntime() {}

    public static GTRecipeDefinition createCustomRecipe(
            WorkableElectricMultiblockMachine machine,
            RecipeHandlerUnit recipeHandlerUnit
    ) {
        if (machine == null || recipeHandlerUnit == null) return null;

        try {
            long voltage = machine.getOverclockVoltage();
            if (voltage < 1L) {
                clearPending(machine);
                return null;
            }

            int hatchTier = machine.getTier();
            if (hatchTier < GTValues.LV) {
                clearPending(machine);
                return null;
            }

            int lootMultiplier = Math.max(1, hatchTier - GTValues.LV + 1);

            BossSimulationSelectorProbe.Selection selection =
                    BossSimulationSelectorProbe.detect(machine, recipeHandlerUnit);
            if (!selection.valid()) {
                clearPending(machine);
                return null;
            }

            if (!(machine.getLevel() instanceof ServerLevel physicalLevel)) {
                return null;
            }

            BossSimulationMode mode = selection.mode();
            ServerLevel simulatedLevel = mode.resolve(physicalLevel);
            if (simulatedLevel == null) {
                clearPending(machine);
                return null;
            }

            int powerTier = Math.max(0, GTUtil.getFloorTierByVoltage(voltage));
            ResourceLocation simulatedDimension = simulatedLevel.dimension().location();
            boolean autoSalvage = machine instanceof BossSimulationChamberMachine chamber
                    && chamber.isAutoSalvage();

            PendingSimulation pending = getPending(machine);
            if (pending == null || !pending.matches(
                    mode,
                    powerTier,
                    hatchTier,
                    lootMultiplier,
                    simulatedDimension,
                    autoSalvage
            )) {
                float luck = (float) (powerTier << 2);
                BlockPos pos = machine.getPos();
                List<ItemStack> outputs = new ArrayList<>();

                for (int roll = 0; roll < lootMultiplier; roll++) {
                    ApotheosisBossSimulator.SimulationResult result =
                            ApotheosisBossSimulator.simulateMachine(simulatedLevel, luck, pos);

                    for (ItemStack generated : copyNonEmpty(result.outputs())) {
                        appendOutput(physicalLevel, generated, autoSalvage, outputs);
                    }
                }

                if (outputs.isEmpty()) {
                    clearPending(machine);
                    return null;
                }

                pending = new PendingSimulation(
                        mode,
                        powerTier,
                        hatchTier,
                        lootMultiplier,
                        simulatedDimension,
                        autoSalvage,
                        List.copyOf(outputs)
                );
                putPending(machine, pending);
            }

            int duration = Math.max(5, 400 / (powerTier + 1));

            GTRecipeBuilder builder = GTRecipeTypes.DUMMY_RECIPES
                    .recipeBuilder(RUNTIME_RECIPE_ID)
                    .notConsumable(selection.selector())
                    .duration(duration)
                    .EUt(voltage);

            for (ItemStack output : pending.outputs()) {
                builder.outputItems(output.copy());
            }

            return builder.build();
        } catch (ApotheosisBossSimulator.NoEligibleBossException noBoss) {
            clearPending(machine);
            return null;
        } catch (Throwable throwable) {
            reportOnce("createCustomRecipe", throwable);
            clearPending(machine);
            return null;
        }
    }

    private static void appendOutput(
            ServerLevel level,
            ItemStack generated,
            boolean autoSalvage,
            List<ItemStack> target
    ) {
        if (generated == null || generated.isEmpty()) return;

        if (autoSalvage && ApotheosisSalvageBridge.canSalvage(level, generated)) {
            List<ItemStack> salvaged = copyNonEmpty(ApotheosisSalvageBridge.salvage(level, generated.copy()));
            if (!salvaged.isEmpty()) {
                target.addAll(salvaged);
                return;
            }
        }

        target.add(generated.copy());
    }

    public static void onAutoSalvageChanged(Object machine) {
        if (machine != null) clearPending(machine);
    }

    public static void onAfterWorking(Object machine) {
        if (machine != null) clearPending(machine);
    }

    public static void onStructureInvalid(Object machine) {
        if (machine != null) clearPending(machine);
    }

    private static List<ItemStack> copyNonEmpty(List<ItemStack> stacks) {
        List<ItemStack> result = new ArrayList<>();
        if (stacks == null) return result;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) result.add(stack.copy());
        }
        return result;
    }

    private static PendingSimulation getPending(Object machine) {
        synchronized (PENDING) {
            return PENDING.get(machine);
        }
    }

    private static void putPending(Object machine, PendingSimulation pending) {
        synchronized (PENDING) {
            PENDING.put(machine, pending);
        }
    }

    private static void clearPending(Object machine) {
        synchronized (PENDING) {
            PENDING.remove(machine);
        }
    }

    private static void reportOnce(String stage, Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String key = stage + ":" + root.getClass().getName() + ":" + String.valueOf(root.getMessage());
        if (REPORTED_ERRORS.add(key)) {
            System.err.println("[CointCoreGTO] Boss Simulation Chamber failed at " + stage
                    + ": " + root.getClass().getSimpleName() + ": " + root.getMessage());
            root.printStackTrace(System.err);
        }
    }

    private record PendingSimulation(
            BossSimulationMode mode,
            int powerTier,
            int hatchTier,
            int lootMultiplier,
            ResourceLocation dimension,
            boolean autoSalvage,
            List<ItemStack> outputs
    ) {
        private boolean matches(
                BossSimulationMode requestedMode,
                int requestedPowerTier,
                int requestedHatchTier,
                int requestedLootMultiplier,
                ResourceLocation requestedDimension,
                boolean requestedAutoSalvage
        ) {
            return mode == requestedMode
                    && powerTier == requestedPowerTier
                    && hatchTier == requestedHatchTier
                    && lootMultiplier == requestedLootMultiplier
                    && autoSalvage == requestedAutoSalvage
                    && dimension.equals(requestedDimension);
        }
    }
}
