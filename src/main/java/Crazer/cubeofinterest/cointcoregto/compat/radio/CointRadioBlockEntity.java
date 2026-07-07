package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class CointRadioBlockEntity extends BlockEntity {
    private String stationId = "";

    public CointRadioBlockEntity(BlockPos pos, BlockState state) {
        super(CointRadioBlocks.COINT_RADIO_BLOCK_ENTITY.get(), pos, state);
    }

    public String getStationId() {
        if (stationId == null || stationId.isBlank()) {
            stationId = CointRadioConfig.getDefaultStation();
        }

        String url = CointRadioConfig.getStationUrl(stationId);

        if (url == null || url.isBlank()) {
            stationId = CointRadioConfig.getDefaultStation();
        }

        return stationId;
    }

    public String getStationUrl() {
        String id = getStationId();
        String url = CointRadioConfig.getStationUrl(id);

        if (url != null && !url.isBlank()) {
            return url;
        }

        return CointRadioConfig.getDefaultUrl();
    }

    public String nextStation() {
        List<String> stations = CointRadioConfig.getStationIds();

        if (stations.isEmpty()) {
            stationId = CointRadioConfig.getDefaultStation();
            setChanged();
            return stationId;
        }

        String current = getStationId();
        int index = stations.indexOf(current);

        if (index < 0) {
            stationId = stations.get(0);
        } else {
            stationId = stations.get((index + 1) % stations.size());
        }

        setChanged();
        return stationId;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("StationId", getStationId());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        stationId = tag.getString("StationId");
    }
}