package Crazer.cubeofinterest.cointcoregto.compat.jade.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

public enum CointRadioJadeProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
    INSTANCE;

    private static final ResourceLocation UID =
            new ResourceLocation(CointCoreGTO.MODID, "radio_station");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        CompoundTag serverData = accessor.getServerData();

        String stationId = serverData.getString("CointRadioStation");
        boolean active = serverData.getBoolean("CointRadioActive");
        int radius = serverData.getInt("CointRadioRadius");

        if (stationId != null && !stationId.isBlank()) {
            tooltip.add(Component.literal("§7Станция: §f" + stationId));
        }

        tooltip.add(Component.literal("§7Состояние: " + (active ? "§aвключено" : "§cвыключено")));

        if (radius > 0) {
            tooltip.add(Component.literal("§7Радиус: §f" + radius));
        }
    }

    @Override
    public void appendServerData(CompoundTag data, BlockAccessor accessor) {
        if (!(accessor.getBlockEntity() instanceof CointRadioBlockEntity radio)) {
            return;
        }

        data.putString("CointRadioStation", radio.getStationId());
        data.putBoolean("CointRadioActive", radio.isActive());
        data.putInt("CointRadioRadius", radio.getRadius());
    }

    @Override
    public ResourceLocation getUid() {
        return UID;
    }
}