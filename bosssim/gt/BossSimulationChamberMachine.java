package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationChamberRuntime;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.api.machine.multiblock.WorkableElectricMultiblockMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipeDefinition;
import com.gregtechceu.gtceu.api.recipe.handler.ICustomRecipeLogicHolder;
import com.gregtechceu.gtceu.api.recipe.handler.RecipeHandlerUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class BossSimulationChamberMachine extends WorkableElectricMultiblockMachine
        implements ICustomRecipeLogicHolder {

    private static final String NBT_AUTO_SALVAGE = "CointAutoSalvage";

    private boolean autoSalvage;

    public BossSimulationChamberMachine(MetaMachineBlockEntity holder) {
        super(holder);
    }

    @Override
    public GTRecipeDefinition createCustomRecipe(RecipeHandlerUnit unit) {
        return BossSimulationChamberRuntime.createCustomRecipe(this, unit);
    }

    @Override
    public boolean alwaysSearchRecipe() {
        return true;
    }

    @Override
    public Component getTitle() {
        return Component.literal("Камера симуляции боссов");
    }

    public boolean isAutoSalvage() {
        return autoSalvage;
    }

    private void setAutoSalvage(boolean enabled) {
        if (autoSalvage == enabled) return;
        autoSalvage = enabled;
        BossSimulationChamberRuntime.onAutoSalvageChanged(this);
        onChanged();
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel configuratorPanel) {
        super.attachConfigurators(configuratorPanel);

        IFancyConfiguratorButton.Toggle autoSalvageToggle = new IFancyConfiguratorButton.Toggle(
                GuiTextures.MACERATOR_RECYCLING_CATEGORY,
                GuiTextures.BUTTON_CHECK,
                this::isAutoSalvage,
                (clickData, enabled) -> setAutoSalvage(enabled)
        ).setTooltipsSupplier(enabled -> List.of(
                Component.literal("Автоматическая разборка")
                        .withStyle(ChatFormatting.YELLOW),
                Component.literal(enabled ? "Включена" : "Выключена")
                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED),
                Component.literal(enabled
                                ? "Все разборные награды из новых симуляций сразу превращаются в материалы Apotheosis."
                                : "Награды из новых симуляций выходят без разборки.")
                        .withStyle(ChatFormatting.GRAY),
                Component.literal("Самоцветы тоже разбираются, если это поддерживает Apotheosis. Остальные награды выходят без изменений.")
                        .withStyle(ChatFormatting.DARK_GRAY),
                Component.literal("Изменение применяется со следующего цикла.")
                        .withStyle(ChatFormatting.DARK_GRAY)
        ));

        configuratorPanel.attachConfigurators(autoSalvageToggle);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        super.addDisplayText(textList);
        textList.add(Component.literal("Автоматическая разборка: ")
                .append(Component.literal(autoSalvage ? "включена" : "выключена")
                        .withStyle(autoSalvage ? ChatFormatting.GREEN : ChatFormatting.RED)));
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        tag.putBoolean(NBT_AUTO_SALVAGE, autoSalvage);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        autoSalvage = tag.getBoolean(NBT_AUTO_SALVAGE);
    }

    @Override
    public void afterWorking() {
        super.afterWorking();
        BossSimulationChamberRuntime.onAfterWorking(this);
    }

    @Override
    public void onStructureInvalid() {
        BossSimulationChamberRuntime.onStructureInvalid(this);
        super.onStructureInvalid();
    }
}