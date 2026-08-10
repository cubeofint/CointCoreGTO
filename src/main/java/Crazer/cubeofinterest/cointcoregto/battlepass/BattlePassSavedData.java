package Crazer.cubeofinterest.cointcoregto.battlepass;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BattlePassSavedData extends SavedData {
    private static final String DATA_NAME = "cointcoregto_battlepass";
    private final Map<UUID, PlayerProgress> players = new HashMap<>();
    private final Map<String, SeasonStartOverride> seasonStartOverrides = new HashMap<>();

    public static BattlePassSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                BattlePassSavedData::load,
                BattlePassSavedData::new,
                DATA_NAME
        );
    }

    public static BattlePassSavedData load(CompoundTag root) {
        BattlePassSavedData data = new BattlePassSavedData();
        CompoundTag playersTag = root.getCompound("Players");
        for (String key : playersTag.getAllKeys()) {
            try {
                UUID uuid = UUID.fromString(key);
                CompoundTag tag = playersTag.getCompound(key);
                PlayerProgress progress = new PlayerProgress();
                progress.seasonId = tag.getString("SeasonId");
                progress.lastSeenEpochDay = tag.contains("LastSeenEpochDay")
                        ? tag.getLong("LastSeenEpochDay")
                        : Long.MIN_VALUE;
                progress.streak = Math.max(0, Math.min(BattlePassConfig.maxSupportedDays(), tag.getInt("Streak")));
                progress.freeClaimed = BitSet.valueOf(tag.getLongArray("FreeClaimed"));
                progress.premiumClaimed = BitSet.valueOf(tag.getLongArray("PremiumClaimed"));
                data.players.put(uuid, progress);
            } catch (IllegalArgumentException ignored) {
            }
        }

        CompoundTag startsTag = root.getCompound("SeasonStartOverrides");
        for (String seasonId : startsTag.getAllKeys()) {
            CompoundTag tag = startsTag.getCompound(seasonId);
            int day = Math.max(1, Math.min(BattlePassConfig.maxSupportedDays(), tag.getInt("Day")));
            long epochDay = tag.contains("EpochDay") ? tag.getLong("EpochDay") : Long.MIN_VALUE;
            if (!seasonId.isBlank() && epochDay != Long.MIN_VALUE) {
                data.seasonStartOverrides.put(seasonId, new SeasonStartOverride(day, epochDay));
            }
        }
        return data;
    }

    public PlayerProgress getOrCreate(UUID uuid) {
        return this.players.computeIfAbsent(uuid, ignored -> new PlayerProgress());
    }

    public int initialDayForSeason(String seasonId, long currentEpochDay, int maximumDay) {
        SeasonStartOverride override = this.seasonStartOverrides.get(seasonId);
        if (override == null) {
            return 1;
        }
        long elapsedDays = Math.max(0L, currentEpochDay - override.epochDay());
        long calculatedDay = (long) override.day() + elapsedDays;
        return (int) Math.max(1L, Math.min(maximumDay, calculatedDay));
    }

    public void setSeasonStartOverride(String seasonId, int day, long epochDay) {
        this.seasonStartOverrides.put(
                seasonId,
                new SeasonStartOverride(
                        Math.max(1, Math.min(BattlePassConfig.maxSupportedDays(), day)),
                        epochDay
                )
        );
        setDirty();
    }

    public int setAllStoredPlayersDay(String seasonId, int day, long epochDay) {
        int changed = 0;
        for (PlayerProgress progress : this.players.values()) {
            if (!progress.isSeason(seasonId)) {
                progress.resetForSeason(seasonId);
            }
            progress.setStreak(day);
            progress.setLastSeenEpochDay(epochDay);
            changed++;
        }
        if (changed > 0) {
            setDirty();
        }
        return changed;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        CompoundTag playersTag = new CompoundTag();
        for (Map.Entry<UUID, PlayerProgress> entry : this.players.entrySet()) {
            PlayerProgress progress = entry.getValue();
            CompoundTag tag = new CompoundTag();
            tag.putString("SeasonId", progress.seasonId);
            tag.putLong("LastSeenEpochDay", progress.lastSeenEpochDay);
            tag.putInt("Streak", progress.streak);
            tag.putLongArray("FreeClaimed", progress.freeClaimed.toLongArray());
            tag.putLongArray("PremiumClaimed", progress.premiumClaimed.toLongArray());
            playersTag.put(entry.getKey().toString(), tag);
        }
        root.put("Players", playersTag);

        CompoundTag startsTag = new CompoundTag();
        for (Map.Entry<String, SeasonStartOverride> entry : this.seasonStartOverrides.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Day", entry.getValue().day());
            tag.putLong("EpochDay", entry.getValue().epochDay());
            startsTag.put(entry.getKey(), tag);
        }
        root.put("SeasonStartOverrides", startsTag);
        return root;
    }

    private record SeasonStartOverride(int day, long epochDay) {
    }

    public static final class PlayerProgress {
        private String seasonId = "";
        private long lastSeenEpochDay = Long.MIN_VALUE;
        private int streak;
        private BitSet freeClaimed = new BitSet(BattlePassConfig.maxSupportedDays());
        private BitSet premiumClaimed = new BitSet(BattlePassConfig.maxSupportedDays());

        public boolean isSeason(String expectedSeasonId) {
            return this.seasonId.equals(expectedSeasonId);
        }

        public void resetForSeason(String newSeasonId) {
            this.seasonId = newSeasonId;
            this.lastSeenEpochDay = Long.MIN_VALUE;
            this.streak = 0;
            this.freeClaimed.clear();
            this.premiumClaimed.clear();
        }

        public long lastSeenEpochDay() {
            return this.lastSeenEpochDay;
        }

        public void setLastSeenEpochDay(long value) {
            this.lastSeenEpochDay = value;
        }

        public int streak() {
            return this.streak;
        }

        public void setStreak(int value) {
            this.streak = Math.max(0, Math.min(BattlePassConfig.maxSupportedDays(), value));
        }

        public boolean isFreeClaimed(int dayIndex) {
            return this.freeClaimed.get(dayIndex);
        }

        public boolean isPremiumClaimed(int dayIndex) {
            return this.premiumClaimed.get(dayIndex);
        }

        public void claimFree(int dayIndex) {
            this.freeClaimed.set(dayIndex);
        }

        public void claimPremium(int dayIndex) {
            this.premiumClaimed.set(dayIndex);
        }

        public boolean resetFreeClaim(int dayIndex) {
            boolean wasClaimed = this.freeClaimed.get(dayIndex);
            this.freeClaimed.clear(dayIndex);
            return wasClaimed;
        }

        public boolean resetPremiumClaim(int dayIndex) {
            boolean wasClaimed = this.premiumClaimed.get(dayIndex);
            this.premiumClaimed.clear(dayIndex);
            return wasClaimed;
        }

        public int resetAllClaims() {
            int resetCount = this.freeClaimed.cardinality() + this.premiumClaimed.cardinality();
            this.freeClaimed.clear();
            this.premiumClaimed.clear();
            return resetCount;
        }
    }
}
