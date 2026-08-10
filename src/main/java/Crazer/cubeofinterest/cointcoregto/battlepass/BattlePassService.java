package Crazer.cubeofinterest.cointcoregto.battlepass;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassNetwork;
import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassStatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BattlePassService {
    private BattlePassService() {
    }

    public static void touchPlayer(ServerPlayer player) {
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        BattlePassSavedData data = BattlePassSavedData.get(player.server);
        BattlePassSavedData.PlayerProgress progress = data.getOrCreate(player.getUUID());
        if (!progress.isSeason(config.seasonId())) {
            progress.resetForSeason(config.seasonId());
            data.setDirty();
        }
        long today = LocalDate.now(config.streakZone()).toEpochDay();
        long lastSeen = progress.lastSeenEpochDay();

        if (lastSeen == today) {
            return;
        }

        if (lastSeen == Long.MIN_VALUE) {
            progress.setStreak(data.initialDayForSeason(
                    config.seasonId(),
                    today,
                    Math.max(1, config.rewards().size())
            ));
        } else if (lastSeen < today) {
            long missedDays = Math.max(0L, today - lastSeen - 1L);
            if (missedDays > config.resetAfterMissedDays()) {
                progress.setStreak(1);
            } else {
                // A player can advance by only one Battle Pass day per unique login day.
                // Missed calendar days are not skipped and cannot be claimed all at once.
                progress.setStreak(progress.streak() + 1);
            }
        }
        progress.setLastSeenEpochDay(today);
        data.setDirty();
    }

    public static int setPlayerDay(ServerPlayer player, int requestedDay) {
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        BattlePassSavedData data = BattlePassSavedData.get(player.server);
        BattlePassSavedData.PlayerProgress progress = data.getOrCreate(player.getUUID());
        if (!progress.isSeason(config.seasonId())) {
            progress.resetForSeason(config.seasonId());
        }

        int day = clampDay(requestedDay, config);
        progress.setStreak(day);
        progress.setLastSeenEpochDay(LocalDate.now(config.streakZone()).toEpochDay());
        data.setDirty();
        return day;
    }

    public static SetAllResult setAllPlayersDay(MinecraftServer server, int requestedDay) {
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        BattlePassSavedData data = BattlePassSavedData.get(server);
        long today = LocalDate.now(config.streakZone()).toEpochDay();
        int day = clampDay(requestedDay, config);

        data.setSeasonStartOverride(config.seasonId(), day, today);
        int storedPlayers = data.setAllStoredPlayersDay(config.seasonId(), day, today);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            BattlePassSavedData.PlayerProgress progress = data.getOrCreate(player.getUUID());
            if (!progress.isSeason(config.seasonId())) {
                progress.resetForSeason(config.seasonId());
            }
            progress.setStreak(day);
            progress.setLastSeenEpochDay(today);
        }
        data.setDirty();
        return new SetAllResult(day, storedPlayers, server.getPlayerList().getPlayerCount());
    }

    public static ResetClaimResult resetClaimed(
            Collection<ServerPlayer> targets,
            int requestedDay,
            ClaimTrack track
    ) {
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        int day = clampDay(requestedDay, config);
        int dayIndex = day - 1;
        int resetFlags = 0;

        for (ServerPlayer target : targets) {
            BattlePassSavedData data = BattlePassSavedData.get(target.server);
            BattlePassSavedData.PlayerProgress progress = data.getOrCreate(target.getUUID());
            if (!progress.isSeason(config.seasonId())) {
                progress.resetForSeason(config.seasonId());
            }

            if (track == ClaimTrack.FREE || track == ClaimTrack.ALL) {
                resetFlags += progress.resetFreeClaim(dayIndex) ? 1 : 0;
            }
            if (track == ClaimTrack.PREMIUM || track == ClaimTrack.ALL) {
                resetFlags += progress.resetPremiumClaim(dayIndex) ? 1 : 0;
            }
            data.setDirty();

            sendState(
                    target,
                    "Администратор сбросил отметку получения за день " + day
                            + " (" + track.displayName() + ")."
            );
        }

        return new ResetClaimResult(day, targets.size(), resetFlags, track);
    }

    public static ResetAllClaimsResult resetAllClaimed(Collection<ServerPlayer> targets) {
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        int resetFlags = 0;

        for (ServerPlayer target : targets) {
            BattlePassSavedData data = BattlePassSavedData.get(target.server);
            BattlePassSavedData.PlayerProgress progress = data.getOrCreate(target.getUUID());
            if (!progress.isSeason(config.seasonId())) {
                progress.resetForSeason(config.seasonId());
            }

            resetFlags += progress.resetAllClaims();
            data.setDirty();
            sendState(target, "Администратор сбросил все отметки получения наград Battle Pass.");
        }

        return new ResetAllClaimsResult(targets.size(), resetFlags);
    }

    public static void sendState(ServerPlayer player, String statusMessage) {
        touchPlayer(player);
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        BattlePassSavedData.PlayerProgress progress = BattlePassSavedData.get(player.server)
                .getOrCreate(player.getUUID());
        boolean premiumUnlocked = !config.premiumPermission().isBlank()
                && CointCoreGTO.hasPermissionNode(player, config.premiumPermission());

        int rewardCount = config.rewards().size();
        List<BattlePassStatePacket.DayState> days = new ArrayList<>(rewardCount);
        for (int index = 0; index < rewardCount; index++) {
            BattlePassReward reward = config.rewards().get(index);
            days.add(new BattlePassStatePacket.DayState(
                    reward.freeRewards(),
                    reward.premiumRewards(),
                    progress.isFreeClaimed(index),
                    progress.isPremiumClaimed(index)
            ));
        }

        BattlePassNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new BattlePassStatePacket(
                        config.enabled(),
                        config.title(),
                        config.premiumLabel(),
                        config.visibleDays(),
                        progress.streak(),
                        premiumUnlocked,
                        statusMessage == null ? "" : statusMessage,
                        days
                )
        );
    }

    public static void claimCurrent(ServerPlayer player) {
        touchPlayer(player);
        BattlePassConfig.Snapshot config = BattlePassConfig.get();
        if (!config.enabled()) {
            sendState(player, "Боевой пропуск временно отключён.");
            return;
        }

        BattlePassSavedData data = BattlePassSavedData.get(player.server);
        BattlePassSavedData.PlayerProgress progress = data.getOrCreate(player.getUUID());
        int dayIndex = Math.max(0, Math.min(config.rewards().size() - 1, progress.streak() - 1));
        BattlePassReward reward = config.rewards().get(dayIndex);
        boolean premiumUnlocked = !config.premiumPermission().isBlank()
                && CointCoreGTO.hasPermissionNode(player, config.premiumPermission());

        List<ItemStack> toGive = new ArrayList<>();
        boolean claimFree = !progress.isFreeClaimed(dayIndex) && !reward.freeRewards().isEmpty();
        boolean claimPremium = premiumUnlocked
                && !progress.isPremiumClaimed(dayIndex)
                && !reward.premiumRewards().isEmpty();

        if (claimFree) {
            toGive.addAll(reward.freeRewards());
        }
        if (claimPremium) {
            toGive.addAll(reward.premiumRewards());
        }

        if (!claimFree && !claimPremium) {
            sendState(player, "Награда за текущий день уже получена.");
            return;
        }
        if (!BattlePassInventory.canFitAll(player.getInventory(), toGive)) {
            sendState(player, "Освободите место в основном инвентаре и попробуйте снова.");
            return;
        }

        BattlePassInventory.giveAll(player.getInventory(), toGive);
        if (claimFree) {
            progress.claimFree(dayIndex);
        }
        if (claimPremium) {
            progress.claimPremium(dayIndex);
        }
        data.setDirty();
        sendState(player, "Награда за день " + (dayIndex + 1) + " получена!");
    }

    private static int clampDay(int requestedDay, BattlePassConfig.Snapshot config) {
        return Math.max(1, Math.min(Math.max(1, config.rewards().size()), requestedDay));
    }

    public enum ClaimTrack {
        FREE("бесплатная линия"),
        PREMIUM("премиум-линия"),
        ALL("обе линии");

        private final String displayName;

        ClaimTrack(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return this.displayName;
        }
    }

    public record SetAllResult(int day, int storedPlayers, int onlinePlayers) {
    }

    public record ResetClaimResult(int day, int players, int resetFlags, ClaimTrack track) {
    }

    public record ResetAllClaimsResult(int players, int resetFlags) {
    }
}
