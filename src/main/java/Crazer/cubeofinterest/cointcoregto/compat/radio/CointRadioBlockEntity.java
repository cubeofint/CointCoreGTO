package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class CointRadioBlockEntity extends BlockEntity {
    private String stationId = "";
    private String stationDisplayName = "";
    private boolean active = false;
    private String customUrl = "";
    private int radius = clampRadius(CointRadioConfig.getRadius());
    private int tickCounter = 0;

    private final Set<UUID> listeners = new HashSet<>();
    private final Set<BlockPos> speakers = new HashSet<>();
    private int playGeneration = 0;
    private boolean resolvingYouTube = false;
    private String resolvedSourceUrl = "";
    private String resolvedDirectUrl = "";
    private long youtubeResumeOffsetMs = 0L;
    private long youtubePlaybackStartedAtMs = 0L;

    public CointRadioBlockEntity(BlockPos pos, BlockState state) {
        super(CointRadioBlocks.COINT_RADIO_BLOCK_ENTITY.get(), pos, state);
    }

    public String getCustomUrl() {
        return customUrl == null ? "" : customUrl;
    }

    public boolean hasCustomUrl() {
        return customUrl != null && !customUrl.isBlank();
    }

    public String getStationDisplayName() {
        if (hasCustomUrl()) {
            return "custom URL";
        }

        if (stationDisplayName != null && !stationDisplayName.isBlank()) {
            return stationDisplayName;
        }

        String name = CointRadioConfig.getStationName(getStationId());

        if (name == null || name.isBlank()) {
            return getStationId();
        }

        return name;
    }

    private void resetYoutubeResume() {
        youtubeResumeOffsetMs = 0L;
        youtubePlaybackStartedAtMs = 0L;
    }

    private long getYoutubeCurrentOffsetMs(String sourceUrl) {
        if (!CointYouTubeResolver.isYouTubeUrl(sourceUrl)) {
            return 0L;
        }

        if (!active || youtubePlaybackStartedAtMs <= 0L) {
            return Math.max(0L, youtubeResumeOffsetMs);
        }

        long now = System.currentTimeMillis();
        long elapsed = now - youtubePlaybackStartedAtMs;

        return Math.max(0L, youtubeResumeOffsetMs + elapsed);
    }

    private void markYoutubePlaybackStarted(String sourceUrl) {
        if (!CointYouTubeResolver.isYouTubeUrl(sourceUrl)) {
            return;
        }

        youtubePlaybackStartedAtMs = System.currentTimeMillis();
    }

    private void markYoutubePlaybackStopped(String sourceUrl) {
        if (!CointYouTubeResolver.isYouTubeUrl(sourceUrl)) {
            return;
        }

        if (youtubePlaybackStartedAtMs <= 0L) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - youtubePlaybackStartedAtMs;

        youtubeResumeOffsetMs = Math.max(0L, youtubeResumeOffsetMs + elapsed);
        youtubePlaybackStartedAtMs = 0L;
    }

    public int getSpeakerCount() {
        cleanupMissingSpeakers();
        return speakers.size();
    }

    public Set<BlockPos> getSpeakersView() {
        return Set.copyOf(speakers);
    }

    public void setCustomUrl(String customUrl) {
        String clean = customUrl == null ? "" : customUrl.trim();

        if (!clean.isBlank() && !isAllowedCustomUrl(clean)) {
            return;
        }

        this.customUrl = clean;
        invalidateResolvedUrl();
        resetYoutubeResume();
        setChanged();
        syncToClient();

        if (active) {
            playToNearbyPlayers();
        }
    }

    public void clearCustomUrl() {
        this.customUrl = "";
        invalidateResolvedUrl();
        resetYoutubeResume();
        setChanged();
        syncToClient();

        if (active) {
            playToNearbyPlayers();
        }
    }

    private static boolean isAllowedCustomUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String lowered = url.toLowerCase(Locale.ROOT);

        return url.length() <= 2048
                && (lowered.startsWith("http://") || lowered.startsWith("https://"));
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
        this.stationDisplayName = CointRadioConfig.getStationName(normalized);
        invalidateResolvedUrl();
        resetYoutubeResume();

        setChanged();
        syncToClient();

        if (active) {
            playToNearbyPlayers();
        }
    }

    public String getStationUrl() {
        if (hasCustomUrl()) {
            return getCustomUrl();
        }

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
            setStationId(CointRadioConfig.getDefaultStation());

            if (!isActive()) {
                setActive(true);
            }

            return getStationId();
        }

        String current = getStationId();
        int currentIndex = -1;

        for (int i = 0; i < stations.size(); i++) {
            if (stations.get(i).equalsIgnoreCase(current)) {
                currentIndex = i;
                break;
            }
        }

        int nextIndex = currentIndex + 1;

        if (nextIndex >= stations.size()) {
            nextIndex = 0;
        }

        this.customUrl = "";

        setStationId(stations.get(nextIndex));

        if (!isActive()) {
            setActive(true);
        }

        return getStationId();
    }

    public String randomStation() {
        List<String> stations = CointRadioConfig.getStationIds();

        if (stations.isEmpty()) {
            setStationId(CointRadioConfig.getDefaultStation());

            if (!isActive()) {
                setActive(true);
            }

            return getStationId();
        }

        String current = getStationId();
        String selected = stations.get(ThreadLocalRandom.current().nextInt(stations.size()));

        if (stations.size() > 1) {
            int attempts = 0;

            while (selected.equalsIgnoreCase(current) && attempts < 8) {
                selected = stations.get(ThreadLocalRandom.current().nextInt(stations.size()));
                attempts++;
            }
        }

        this.customUrl = "";

        setStationId(selected);

        if (!isActive()) {
            setActive(true);
        }

        return getStationId();
    }

    public boolean isActive() {
        return active;
    }

    public int getRadius() {
        return clampRadius(radius);
    }

    public void setRadius(int radius) {
        int cleanRadius = clampRadius(radius);

        if (this.radius == cleanRadius) {
            return;
        }

        this.radius = cleanRadius;
        setChanged();
        syncToClient();

        refreshListenersAfterCoverageChange();
    }

    public void increaseRadius() {
        setRadius(getRadius() + 1);
    }

    public void decreaseRadius() {
        setRadius(getRadius() - 1);
    }

    private static int clampRadius(int value) {
        if (value < 0) {
            return 0;
        }

        return Math.min(value, 32);
    }

    public void addSpeaker(BlockPos speakerPos) {
        if (speakerPos == null) {
            return;
        }

        if (speakers.add(speakerPos.immutable())) {
            setChanged();
            syncToClient();
            refreshListenersAfterCoverageChange();
        }
    }

    public void removeSpeaker(BlockPos speakerPos) {
        if (speakerPos == null) {
            return;
        }

        if (speakers.remove(speakerPos)) {
            setChanged();
            syncToClient();
            refreshListenersAfterCoverageChange();
        }
    }

    private void cleanupMissingSpeakers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        boolean changed = false;

        for (BlockPos speakerPos : new HashSet<>(speakers)) {
            if (!serverLevel.isLoaded(speakerPos)) {
                continue;
            }

            BlockEntity blockEntity = serverLevel.getBlockEntity(speakerPos);

            if (!(blockEntity instanceof CointSpeakerBlockEntity speaker)) {
                speakers.remove(speakerPos);
                changed = true;
                continue;
            }

            BlockPos linkedRadio = speaker.getRadioPos();

            if (linkedRadio == null || !linkedRadio.equals(worldPosition)) {
                speakers.remove(speakerPos);
                changed = true;
            }
        }

        if (changed) {
            setChanged();
            syncToClient();
        }
    }

    private void refreshListenersAfterCoverageChange() {
        if (!active) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        String radioId = getRadioId();
        Set<UUID> nearbyNow = new HashSet<>();

        for (ServerPlayer player : getNearbyPlayers(serverLevel)) {
            UUID uuid = player.getUUID();
            nearbyNow.add(uuid);

            if (!listeners.contains(uuid)) {
                sendPlayToPlayer(player, getStationUrl(), getStationId(), radioId);
            }
        }

        for (UUID oldUuid : new HashSet<>(listeners)) {
            if (nearbyNow.contains(oldUuid)) {
                continue;
            }

            ServerPlayer oldPlayer = serverLevel.getServer().getPlayerList().getPlayer(oldUuid);

            if (oldPlayer != null) {
                CointRadioNetwork.sendStop(oldPlayer, radioId);
            }
        }

        listeners.clear();
        listeners.addAll(nearbyNow);
    }

    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }

        String sourceUrlBeforeChange = getStationUrl();

        if (!active) {
            markYoutubePlaybackStopped(sourceUrlBeforeChange);
        }

        this.active = active;

        if (active) {
            markYoutubePlaybackStarted(sourceUrlBeforeChange);
        }

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

        syncToClient();

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

    private void invalidateResolvedUrl() {
        playGeneration++;
        resolvingYouTube = false;
        resolvedSourceUrl = "";
        resolvedDirectUrl = "";
    }

    private void sendPlayToPlayer(ServerPlayer player, String sourceUrl, String station, String radioId) {
        if (CointYouTubeResolver.isYouTubeUrl(sourceUrl)) {
            if (!CointRadioExternalTools.isYouTubeAvailable()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(CointRadioExternalTools.getYouTubeDisabledMessage()),
                        true
                );
                return;
            }

            long offsetMs = getYoutubeCurrentOffsetMs(sourceUrl);

            System.out.println("[CointMusic] YouTube URL detected, sending cached MP3 through Minecraft packets. OffsetMs=" + offsetMs);

            CointRadioNetwork.sendYouTubeCachedMp3(
                    player,
                    sourceUrl,
                    station,
                    radioId,
                    offsetMs
            );
            return;
        }

        if (CointRadioTranscodeSession.shouldTranscode(sourceUrl)) {
            if (!CointRadioExternalTools.isFfmpegAvailable()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(CointRadioExternalTools.getFfmpegDisabledMessage()),
                        true
                );
                return;
            }

            System.out.println("[CointMusic] Server live transcode detected: " + sourceUrl);
            CointRadioNetwork.sendTranscodedRadio(player, sourceUrl, station, radioId);
            return;
        }

        CointRadioNetwork.sendPlay(player, sourceUrl, station, radioId);
    }

    private void startYouTubeResolveIfNeeded(String sourceUrl) {
        if (resolvingYouTube) {
            return;
        }

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        resolvingYouTube = true;

        int generation = ++playGeneration;
        BlockPos radioPos = worldPosition.immutable();

        System.out.println("[CointMusic] Resolving YouTube URL...");

        CointYouTubeResolver.resolveAsync(sourceUrl).whenComplete((directUrl, throwable) -> {
            serverLevel.getServer().execute(() -> {
                if (!(level instanceof ServerLevel currentServerLevel)) {
                    return;
                }

                if (!worldPosition.equals(radioPos)) {
                    return;
                }

                if (generation != playGeneration) {
                    return;
                }

                resolvingYouTube = false;

                if (throwable != null) {
                    System.out.println("[CointMusic] YouTube resolve failed: " + throwable.getMessage());
                    throwable.printStackTrace();

                    for (ServerPlayer player : getNearbyPlayers(currentServerLevel)) {
                        player.displayClientMessage(
                                net.minecraft.network.chat.Component.literal("§c[CointMusic] Не удалось запустить YouTube. Проверь yt-dlp на сервере."),
                                true
                        );
                    }

                    return;
                }

                if (directUrl == null || directUrl.isBlank()) {
                    System.out.println("[CointMusic] YouTube resolve returned empty URL.");
                    return;
                }

                resolvedSourceUrl = sourceUrl;
                resolvedDirectUrl = directUrl;

                if (active) {
                    playToNearbyPlayers();
                }
            });
        });
    }

    public void playToNearbyPlayers() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        String url = getStationUrl();
        String station = getStationId();
        String radioId = getRadioId();

        for (ServerPlayer player : getNearbyPlayers(serverLevel)) {
            sendPlayToPlayer(player, url, station, radioId);
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
                radio.sendPlayToPlayer(player, url, station, radioId);
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
        int radius = getRadius();

        if (radius <= 0) {
            return List.of();
        }

        cleanupMissingSpeakers();

        Set<ServerPlayer> result = new HashSet<>();

        addPlayersAround(serverLevel, worldPosition, radius, result);

        for (BlockPos speakerPos : new HashSet<>(speakers)) {
            if (!serverLevel.isLoaded(speakerPos)) {
                continue;
            }

            BlockEntity blockEntity = serverLevel.getBlockEntity(speakerPos);

            if (!(blockEntity instanceof CointSpeakerBlockEntity speaker)) {
                continue;
            }

            BlockPos linkedRadio = speaker.getRadioPos();

            if (linkedRadio == null || !linkedRadio.equals(worldPosition)) {
                continue;
            }

            addPlayersAround(serverLevel, speakerPos, radius, result);
        }

        return List.copyOf(result);
    }

    private static void addPlayersAround(
            ServerLevel serverLevel,
            BlockPos center,
            int radius,
            Set<ServerPlayer> result
    ) {
        AABB box = new AABB(center).inflate(radius);
        result.addAll(serverLevel.getEntitiesOfClass(ServerPlayer.class, box));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        tag.putString("StationId", getStationId());
        tag.putBoolean("Active", active);
        tag.putString("CustomUrl", getCustomUrl());
        tag.putInt("Radius", getRadius());

        ListTag speakersTag = new ListTag();

        for (BlockPos speakerPos : speakers) {
            CompoundTag speakerTag = new CompoundTag();
            speakerTag.putInt("X", speakerPos.getX());
            speakerTag.putInt("Y", speakerPos.getY());
            speakerTag.putInt("Z", speakerPos.getZ());
            speakersTag.add(speakerTag);
        }

        tag.put("Speakers", speakersTag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        stationId = tag.getString("StationId");
        active = tag.getBoolean("Active");
        customUrl = tag.getString("CustomUrl");

        if (tag.contains("Radius")) {
            radius = clampRadius(tag.getInt("Radius"));
        } else {
            radius = clampRadius(CointRadioConfig.getRadius());
        }

        speakers.clear();

        if (tag.contains("Speakers", Tag.TAG_LIST)) {
            ListTag speakersTag = tag.getList("Speakers", Tag.TAG_COMPOUND);

            for (int i = 0; i < speakersTag.size(); i++) {
                CompoundTag speakerTag = speakersTag.getCompound(i);

                speakers.add(new BlockPos(
                        speakerTag.getInt("X"),
                        speakerTag.getInt("Y"),
                        speakerTag.getInt("Z")
                ));
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();

        if (level != null && !level.isClientSide) {
            stationDisplayName = CointRadioConfig.getStationName(getStationId());

            BlockState state = level.getBlockState(worldPosition);

            if (state.hasProperty(CointRadioBlock.ACTIVE)) {
                level.setBlock(
                        worldPosition,
                        state.setValue(CointRadioBlock.ACTIVE, active),
                        3
                );
            }

            syncToClient();
        }
    }

    @Override
    public void setRemoved() {
        stopAllListeners();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        stopAllListeners();
        super.onChunkUnloaded();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();

        tag.putString("StationId", getStationId());
        tag.putBoolean("Active", active);
        tag.putString("CustomUrl", getCustomUrl());
        tag.putString("StationName", CointRadioConfig.getStationName(getStationId()));
        tag.putInt("Radius", getRadius());
        tag.putInt("SpeakerCount", speakers.size());

        return tag;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        handleUpdateTag(packet.getTag());
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);

        if (tag.contains("StationId")) {
            stationId = tag.getString("StationId");
        }

        if (tag.contains("Active")) {
            active = tag.getBoolean("Active");
        }

        if (tag.contains("CustomUrl")) {
            customUrl = tag.getString("CustomUrl");
        }

        if (tag.contains("StationName")) {
            stationDisplayName = tag.getString("StationName");
        }

        if (tag.contains("Radius")) {
            radius = clampRadius(tag.getInt("Radius"));
        }
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) {
            return;
        }

        BlockState state = level.getBlockState(worldPosition);

        level.sendBlockUpdated(
                worldPosition,
                state,
                state,
                3
        );
    }
}