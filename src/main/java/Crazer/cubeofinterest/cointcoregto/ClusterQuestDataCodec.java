package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.net.SyncTeamDataMessage;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class ClusterQuestDataCodec {
    public static final int CODEC_VERSION = 2;
    public static final int LEGACY_CODEC_VERSION = 1;
    public static final String SCOPE_PLAYER = "PLAYER";
    public static final String SCOPE_TEAM = "TEAM";

    private static final String ENVELOPE_MARKER = "cointcoregto_quest_envelope";
    private static final String QUEST_DATA_KEY = "quest_data";
    private static final String TEAM_DATA_KEY = "team_data";

    private ClusterQuestDataCodec() {
    }

    public static Snapshot capture(
            ServerPlayer player,
            int maximumBytes
    ) throws IOException {
        Objects.requireNonNull(player, "player");

        TeamData teamData = TeamData.get(player);
        if (teamData == null) {
            throw new IOException("FTB Quests TeamData is unavailable");
        }

        String scope = teamData.getTeamId().equals(player.getUUID())
                ? SCOPE_PLAYER
                : SCOPE_TEAM;
        CompoundTag teamState = ClusterTeamDataCodec.capture(player);

        return capture(
                teamData,
                scope,
                maximumBytes,
                teamState
        );
    }

    public static Snapshot capture(
            TeamData teamData,
            String scope,
            int maximumBytes
    ) throws IOException {
        return capture(teamData, scope, maximumBytes, null);
    }

    private static Snapshot capture(
            TeamData teamData,
            String scope,
            int maximumBytes,
            CompoundTag teamState
    ) throws IOException {
        Objects.requireNonNull(teamData, "teamData");

        String normalizedScope = normalizeScope(scope);
        CompoundTag envelope = new CompoundTag();
        envelope.putInt(ENVELOPE_MARKER, CODEC_VERSION);
        envelope.put(QUEST_DATA_KEY, copyToCompound(teamData.serializeNBT()));
        if (teamState != null && !teamState.isEmpty()) {
            envelope.put(TEAM_DATA_KEY, teamState.copy());
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        NbtIo.writeCompressed(envelope, output);

        byte[] compressedNbt = output.toByteArray();
        validateSize(compressedNbt, maximumBytes);

        return new Snapshot(
                teamData.getTeamId(),
                normalizedScope,
                teamData.getName(),
                CODEC_VERSION,
                compressedNbt,
                sha256(compressedNbt)
        );
    }

    public static void validateForPlayer(
            ServerPlayer player,
            Collection<Snapshot> snapshots,
            int maximumBytes
    ) throws IOException {
        Objects.requireNonNull(player, "player");

        List<Snapshot> uniqueSnapshots = uniqueSnapshots(snapshots);
        if (uniqueSnapshots.isEmpty()) {
            return;
        }

        applyEmbeddedTeamState(player, uniqueSnapshots, maximumBytes);

        TeamData localData = TeamData.get(player);
        if (localData == null) {
            throw new IOException("FTB Quests TeamData is unavailable");
        }

        Snapshot firstSnapshot = uniqueSnapshots.get(0);
        UUID subjectUuid = firstSnapshot.subjectUuid();
        String scope = normalizeScope(firstSnapshot.scope());

        if (!localData.getTeamId().equals(subjectUuid)) {
            throw new IOException(
                    "FTB Quests subject mismatch after FTB Teams sync: active="
                            + localData.getTeamId()
                            + ", incoming="
                            + subjectUuid
                            + ", scope="
                            + scope
            );
        }

        for (Snapshot snapshot : uniqueSnapshots) {
            if (!subjectUuid.equals(snapshot.subjectUuid())) {
                throw new IOException(
                        "FTB Quests snapshot collection contains different subjects"
                );
            }
            if (!scope.equals(normalizeScope(snapshot.scope()))) {
                throw new IOException(
                        "FTB Quests snapshot collection contains different scopes"
                );
            }
            decode(snapshot, localData.getFile(), maximumBytes);
        }
    }

    public static ApplyResult merge(
            ServerPlayer player,
            Collection<Snapshot> snapshots,
            int maximumBytes
    ) throws IOException {
        Objects.requireNonNull(player, "player");

        List<Snapshot> uniqueSnapshots = uniqueSnapshots(snapshots);
        if (!uniqueSnapshots.isEmpty()) {
            applyEmbeddedTeamState(player, uniqueSnapshots, maximumBytes);
        }

        TeamData localData = TeamData.get(player);
        if (localData == null) {
            throw new IOException("FTB Quests TeamData is unavailable");
        }

        Collection<ServerPlayer> onlineMembers = localData.getOnlineMembers();
        Iterable<ServerPlayer> recipients = onlineMembers == null
                || onlineMembers.isEmpty()
                ? List.of(player)
                : onlineMembers;

        return merge(localData, uniqueSnapshots, maximumBytes, recipients);
    }

    public static ApplyResult merge(
            TeamData localData,
            Collection<Snapshot> snapshots,
            int maximumBytes,
            Iterable<ServerPlayer> recipients
    ) throws IOException {
        Objects.requireNonNull(localData, "localData");

        List<Snapshot> uniqueSnapshots = uniqueSnapshots(snapshots);
        if (uniqueSnapshots.isEmpty()) {
            return ApplyResult.noSnapshots(localData.getTeamId());
        }

        Snapshot firstSnapshot = uniqueSnapshots.get(0);
        UUID subjectUuid = firstSnapshot.subjectUuid();
        String mergeScope = normalizeScope(firstSnapshot.scope());

        if (!localData.getTeamId().equals(subjectUuid)) {
            throw new IOException(
                    "FTB Quests subject mismatch: active="
                            + localData.getTeamId()
                            + ", incoming="
                            + subjectUuid
                            + ", scope="
                            + mergeScope
            );
        }

        Snapshot before = capture(localData, mergeScope, maximumBytes);
        int mergedSnapshots = 0;
        int totalCompressedBytes = 0;

        for (Snapshot snapshot : uniqueSnapshots) {
            if (!subjectUuid.equals(snapshot.subjectUuid())) {
                throw new IOException(
                        "FTB Quests snapshot collection contains different subjects: "
                                + subjectUuid
                                + " and "
                                + snapshot.subjectUuid()
                );
            }

            if (!mergeScope.equals(normalizeScope(snapshot.scope()))) {
                throw new IOException(
                        "FTB Quests scope mismatch for subject "
                                + subjectUuid
                                + ": "
                                + mergeScope
                                + " and "
                                + snapshot.scope()
                );
            }

            TeamData incoming = decode(
                    snapshot,
                    localData.getFile(),
                    maximumBytes
            );
            localData.mergeData(incoming);
            mergedSnapshots++;
            totalCompressedBytes += snapshot.compressedSize();
        }

        Snapshot after = capture(localData, mergeScope, maximumBytes);
        boolean changed = !before.sha256().equalsIgnoreCase(after.sha256());

        if (changed) {
            localData.markDirty();
            localData.saveIfChanged();

            if (recipients != null) {
                new SyncTeamDataMessage(localData, true).sendTo(recipients);
            }
        }

        return new ApplyResult(
                true,
                changed,
                localData.getTeamId(),
                mergeScope,
                mergedSnapshots,
                totalCompressedBytes,
                after
        );
    }

    public static TeamData decode(
            Snapshot snapshot,
            dev.ftb.mods.ftbquests.quest.BaseQuestFile questFile,
            int maximumBytes
    ) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(questFile, "questFile");

        CompoundTag decoded = decodeRoot(snapshot, maximumBytes);
        CompoundTag questData = extractQuestData(snapshot, decoded);

        SNBTCompoundTag snbt = new SNBTCompoundTag();
        for (String key : questData.getAllKeys()) {
            Tag value = questData.get(key);
            if (value != null) {
                snbt.put(key, value.copy());
            }
        }

        TeamData data = new TeamData(
                snapshot.subjectUuid(),
                questFile,
                snapshot.subjectName()
        );
        data.deserializeNBT(snbt);
        return data;
    }

    public static void validateSnapshot(
            Snapshot snapshot,
            int maximumBytes
    ) throws IOException {
        if (snapshot.codecVersion() != CODEC_VERSION
                && snapshot.codecVersion() != LEGACY_CODEC_VERSION) {
            throw new IOException(
                    "Unsupported FTB Quests codec version: "
                            + snapshot.codecVersion()
            );
        }

        byte[] compressedNbt = snapshot.compressedNbt();
        if (compressedNbt == null || compressedNbt.length == 0) {
            throw new IOException("FTB Quests snapshot is empty");
        }

        validateSize(compressedNbt, maximumBytes);

        String actualSha256 = sha256(compressedNbt);
        if (snapshot.sha256() == null
                || !actualSha256.equalsIgnoreCase(snapshot.sha256())) {
            throw new IOException(
                    "FTB Quests SHA-256 mismatch for subject "
                            + snapshot.subjectUuid()
            );
        }
    }

    public static String normalizeScope(String scope) {
        return SCOPE_PLAYER.equalsIgnoreCase(scope)
                ? SCOPE_PLAYER
                : SCOPE_TEAM;
    }

    private static void applyEmbeddedTeamState(
            ServerPlayer player,
            List<Snapshot> snapshots,
            int maximumBytes
    ) throws IOException {
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            Snapshot snapshot = snapshots.get(i);
            CompoundTag root = decodeRoot(snapshot, maximumBytes);
            if (isEnvelope(root)
                    && root.contains(TEAM_DATA_KEY, Tag.TAG_COMPOUND)) {
                ClusterTeamDataCodec.ApplyResult result =
                        ClusterTeamDataCodec.apply(
                                player,
                                root.getCompound(TEAM_DATA_KEY)
                        );
                if (!snapshot.subjectUuid().equals(result.activeTeamUuid())) {
                    throw new IOException(
                            "FTB Teams snapshot subject mismatch: quest="
                                    + snapshot.subjectUuid()
                                    + ", team="
                                    + result.activeTeamUuid()
                    );
                }
                return;
            }
        }
    }

    private static CompoundTag decodeRoot(
            Snapshot snapshot,
            int maximumBytes
    ) throws IOException {
        validateSnapshot(snapshot, maximumBytes);

        try (ByteArrayInputStream input =
                     new ByteArrayInputStream(snapshot.compressedNbt())) {
            return NbtIo.readCompressed(input);
        }
    }

    private static CompoundTag extractQuestData(
            Snapshot snapshot,
            CompoundTag decoded
    ) throws IOException {
        if (snapshot.codecVersion() == LEGACY_CODEC_VERSION) {
            return decoded;
        }
        if (!isEnvelope(decoded)
                || !decoded.contains(QUEST_DATA_KEY, Tag.TAG_COMPOUND)) {
            throw new IOException("FTB Quests envelope is invalid");
        }
        return decoded.getCompound(QUEST_DATA_KEY);
    }

    private static boolean isEnvelope(CompoundTag root) {
        return root.contains(ENVELOPE_MARKER, Tag.TAG_INT)
                && root.getInt(ENVELOPE_MARKER) == CODEC_VERSION;
    }

    private static CompoundTag copyToCompound(
            SNBTCompoundTag source
    ) {
        CompoundTag copy = new CompoundTag();
        for (String key : source.getAllKeys()) {
            Tag value = source.get(key);
            if (value != null) {
                copy.put(key, value.copy());
            }
        }
        return copy;
    }

    private static List<Snapshot> uniqueSnapshots(
            Collection<Snapshot> snapshots
    ) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }

        Map<String, Snapshot> unique = new LinkedHashMap<>();
        for (Snapshot snapshot : snapshots) {
            if (snapshot == null) {
                continue;
            }

            String key = snapshot.subjectUuid()
                    + ":"
                    + normalizeScope(snapshot.scope())
                    + ":"
                    + String.valueOf(snapshot.sha256()).toLowerCase();
            unique.putIfAbsent(key, snapshot);
        }
        return List.copyOf(unique.values());
    }

    private static void validateSize(
            byte[] compressedNbt,
            int maximumBytes
    ) throws IOException {
        if (maximumBytes <= 0) {
            throw new IOException(
                    "Maximum FTB Quests data size must be positive"
            );
        }

        if (compressedNbt.length > maximumBytes) {
            throw new IOException(
                    "Compressed FTB Quests and Teams data is too large: "
                            + compressedNbt.length
                            + " bytes, maximum is "
                            + maximumBytes
            );
        }
    }

    private static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    public record Snapshot(
            UUID subjectUuid,
            String scope,
            String subjectName,
            int codecVersion,
            byte[] compressedNbt,
            String sha256
    ) {
        public Snapshot {
            Objects.requireNonNull(subjectUuid, "subjectUuid");
            scope = normalizeScope(scope);
            subjectName = subjectName == null ? "" : subjectName;
            compressedNbt = compressedNbt == null
                    ? null
                    : compressedNbt.clone();
        }

        @Override
        public byte[] compressedNbt() {
            return compressedNbt == null ? null : compressedNbt.clone();
        }

        public int compressedSize() {
            return compressedNbt == null ? 0 : compressedNbt.length;
        }
    }

    public record ApplyResult(
            boolean snapshotsPresent,
            boolean changed,
            UUID subjectUuid,
            String scope,
            int mergedSnapshots,
            int totalCompressedBytes,
            Snapshot mergedSnapshot
    ) {
        private static ApplyResult noSnapshots(UUID subjectUuid) {
            return new ApplyResult(
                    false,
                    false,
                    subjectUuid,
                    null,
                    0,
                    0,
                    null
            );
        }
    }
}
