package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class CointRadioBlockEntity extends BlockEntity {
    private String stationId = "";
    private boolean active = false;
    private int tickCounter = 0;
    private final Set<UUID> listeners = new HashSet<>();

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

    public void setStationId(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return;
        }

        String normalized = stationId.trim().toLowerCase(Locale.ROOT);
        String url = CointRadioConfig.getStationUrl(normalized);

        if (url == null || url.isBlank()) {
            return;
        }

        this.stationId = normalized;
        setChanged();

        if (active) {
            playToNearbyPlayers();
        }
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

    public boolean isActive() {
        return active;
    }

    public int getRadius() {
        return CointRadioConfig.getRadius();
    }

    public void setActive(boolean active) {
        this.active = active;
        setChanged();

        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);

            if (state.hasProperty(CointRadioBlock.ACTIVE)) {
                level.setBlock(
                        worldPosition,
                        state.setValue(CointRadioBlock.ACTIVE, active),
                        3
                );
            }
        }

        if (!active) {
            stopAllListeners();
        } else {
            playToNearbyPlayers();
        }
    }

    public void toggleActive() {
        setActive(!active);
    }

    public String getRadioId() {
        return worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    public void playToNearbyPlayers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        String url = getStationUrl();
        String station = getStationId();
        String radioId = getRadioId();

        for (ServerPlayer player : getNearbyPlayers(serverLevel)) {
            CointRadioNetwork.sendPlay(player, url, station, radioId);
            listeners.add(player.getUUID());
        }
    }

    public void stopAllListeners() {
        if (!(level instanceof ServerLevel serverLevel)) {
            listeners.clear();
            return;
        }

        String radioId = getRadioId();

        for (UUID uuid : new HashSet<>(listeners)) {
            ServerPlayer player = serverLevel.getServer().getPlayerList().getPlayer(uuid);

            if (player != null) {
                CointRadioNetwork.sendStop(player, radioId);
            }
        }

        listeners.clear();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, CointRadioBlockEntity radio) {
        if (level.isClientSide) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        radio.tickCounter++;

        if (radio.tickCounter < 20) {
            return;
        }

        radio.tickCounter = 0;

        if (!radio.active) {
            if (!radio.listeners.isEmpty()) {
                radio.stopAllListeners();
            }

            return;
        }

        Set<UUID> nearbyNow = new HashSet<>();
        String url = radio.getStationUrl();
        String station = radio.getStationId();
        String radioId = radio.getRadioId();

        for (ServerPlayer player : radio.getNearbyPlayers(serverLevel)) {
            UUID uuid = player.getUUID();
            nearbyNow.add(uuid);

            if (!radio.listeners.contains(uuid)) {
                CointRadioNetwork.sendPlay(player, url, station, radioId);
            }
        }

        for (UUID oldUuid : new HashSet<>(radio.listeners)) {
            if (nearbyNow.contains(oldUuid)) {
                continue;
            }

            ServerPlayer oldPlayer = serverLevel.getServer().getPlayerList().getPlayer(oldUuid);

            if (oldPlayer != null) {
                CointRadioNetwork.sendStop(oldPlayer, radioId);
            }
        }

        radio.listeners.clear();
        radio.listeners.addAll(nearbyNow);
    }

    private List<ServerPlayer> getNearbyPlayers(ServerLevel serverLevel) {
        int radius = CointRadioConfig.getRadius();

        AABB box = new AABB(worldPosition).inflate(radius);

        return serverLevel.getEntitiesOfClass(ServerPlayer.class, box);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("StationId", getStationId());
        tag.putBoolean("Active", active);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        stationId = tag.getString("StationId");
        active = tag.getBoolean("Active");
    }

    @Override
    public void onLoad() {
        super.onLoad();

        if (level != null && !level.isClientSide) {
            BlockState state = level.getBlockState(worldPosition);

            if (state.hasProperty(CointRadioBlock.ACTIVE)) {
                level.setBlock(
                        worldPosition,
                        state.setValue(CointRadioBlock.ACTIVE, active),
                        3
                );
            }
        }
    }
}