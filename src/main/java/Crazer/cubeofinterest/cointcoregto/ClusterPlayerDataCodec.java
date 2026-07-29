package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

public final class ClusterPlayerDataCodec {
    public static final int CODEC_VERSION = 1;

    private static final String LAST_APPLIED_TRANSFER_KEY =
            "cointcoregto:last_cluster_transfer";

    private static final Set<String> TARGET_LOCAL_KEYS = Set.of(
            "UUID",
            "Pos",
            "Motion",
            "Rotation",
            "Dimension",
            "PortalCooldown",
            "FallDistance",
            "Fire",
            "Air",
            "OnGround",
            "Invulnerable",
            "DeathTime",
            "HurtTime",
            "HurtByTimestamp",
            "SleepingX",
            "SleepingY",
            "SleepingZ",
            "SleepTimer",
            "RootVehicle",
            "Passengers",
            "Leash",
            "Tags",
            "abilities",
            "playerGameType",
            "previousPlayerGameType"
    );

    private ClusterPlayerDataCodec() {
    }

    public static Snapshot capture(
            ServerPlayer player,
            int maximumBytes,
            boolean includeForgeCapabilities
    ) throws IOException {
        CompoundTag playerTag =
                player.saveWithoutId(new CompoundTag());

        for (String key : TARGET_LOCAL_KEYS) {
            playerTag.remove(key);
        }

        if (!includeForgeCapabilities) {
            playerTag.remove("ForgeCaps");
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream(64 * 1024);

        NbtIo.writeCompressed(playerTag, output);

        byte[] compressedNbt = output.toByteArray();
        validateSize(compressedNbt, maximumBytes);

        return new Snapshot(
                CODEC_VERSION,
                compressedNbt,
                sha256(compressedNbt)
        );
    }

    public static ApplyResult apply(
            ServerPlayer player,
            String transferId,
            int codecVersion,
            byte[] compressedNbt,
            String expectedSha256,
            int maximumBytes
    ) throws IOException {
        if (compressedNbt == null || compressedNbt.length == 0) {
            return ApplyResult.noSnapshot();
        }

        if (codecVersion != CODEC_VERSION) {
            throw new IOException(
                    "Unsupported player-data codec version: "
                            + codecVersion
            );
        }

        validateSize(compressedNbt, maximumBytes);

        String actualSha256 = sha256(compressedNbt);

        if (expectedSha256 == null
                || !actualSha256.equalsIgnoreCase(expectedSha256)) {
            throw new IOException(
                    "Player-data SHA-256 mismatch for transfer "
                            + transferId
            );
        }

        CompoundTag persistentData =
                player.getPersistentData();

        if (transferId.equals(
                persistentData.getString(
                        LAST_APPLIED_TRANSFER_KEY
                )
        )) {
            return ApplyResult.alreadyApplied(
                    compressedNbt.length,
                    actualSha256
            );
        }

        CompoundTag incomingTag;

        try (ByteArrayInputStream input =
                     new ByteArrayInputStream(compressedNbt)) {
            incomingTag = NbtIo.readCompressed(input);
        }

        CompoundTag targetLocalTag =
                player.saveWithoutId(new CompoundTag());

        for (String key : TARGET_LOCAL_KEYS) {
            copyTagValue(
                    targetLocalTag,
                    incomingTag,
                    key
            );
        }

        Set<MobEffect> previousEffects = new HashSet<>();

        for (MobEffectInstance effectInstance
                : player.getActiveEffects()) {
            previousEffects.add(effectInstance.getEffect());
        }

        player.stopRiding();
        player.closeContainer();
        player.load(incomingTag);
        player.getPersistentData().putString(
                LAST_APPLIED_TRANSFER_KEY,
                transferId
        );

        synchronizeClient(player, previousEffects);

        return ApplyResult.applied(
                compressedNbt.length,
                actualSha256
        );
    }

    private static void synchronizeClient(
            ServerPlayer player,
            Set<MobEffect> previousEffects
    ) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();

        if (player.containerMenu != player.inventoryMenu) {
            player.containerMenu.broadcastFullState();
        }

        player.connection.send(
                new ClientboundSetHealthPacket(
                        player.getHealth(),
                        player.getFoodData().getFoodLevel(),
                        player.getFoodData().getSaturationLevel()
                )
        );

        player.connection.send(
                new ClientboundSetExperiencePacket(
                        player.experienceProgress,
                        player.totalExperience,
                        player.experienceLevel
                )
        );

        player.connection.send(
                new ClientboundSetCarriedItemPacket(
                        player.getInventory().selected
                )
        );

        player.onUpdateAbilities();

        Set<MobEffect> currentEffects = new HashSet<>();

        for (MobEffectInstance effectInstance
                : player.getActiveEffects()) {
            currentEffects.add(effectInstance.getEffect());
            player.connection.send(
                    new ClientboundUpdateMobEffectPacket(
                            player.getId(),
                            effectInstance
                    )
            );
        }

        for (MobEffect previousEffect : previousEffects) {
            if (!currentEffects.contains(previousEffect)) {
                player.connection.send(
                        new ClientboundRemoveMobEffectPacket(
                                player.getId(),
                                previousEffect
                        )
                );
            }
        }
    }

    private static void copyTagValue(
            CompoundTag source,
            CompoundTag target,
            String key
    ) {
        Tag value = source.get(key);

        if (value == null) {
            target.remove(key);
            return;
        }

        target.put(key, value.copy());
    }

    private static void validateSize(
            byte[] compressedNbt,
            int maximumBytes
    ) throws IOException {
        if (maximumBytes <= 0) {
            throw new IOException(
                    "Maximum player-data size must be positive"
            );
        }

        if (compressedNbt.length > maximumBytes) {
            throw new IOException(
                    "Compressed player data is too large: "
                            + compressedNbt.length
                            + " bytes, maximum is "
                            + maximumBytes
            );
        }
    }

    private static String sha256(
            byte[] data
    ) throws IOException {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(data)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    public record Snapshot(
            int codecVersion,
            byte[] compressedNbt,
            String sha256
    ) {
        public int compressedSize() {
            return compressedNbt == null
                    ? 0
                    : compressedNbt.length;
        }
    }

    public record ApplyResult(
            boolean snapshotPresent,
            boolean applied,
            boolean alreadyApplied,
            int compressedSize,
            String sha256
    ) {
        private static ApplyResult noSnapshot() {
            return new ApplyResult(
                    false,
                    false,
                    false,
                    0,
                    null
            );
        }

        private static ApplyResult applied(
                int compressedSize,
                String sha256
        ) {
            return new ApplyResult(
                    true,
                    true,
                    false,
                    compressedSize,
                    sha256
            );
        }

        private static ApplyResult alreadyApplied(
                int compressedSize,
                String sha256
        ) {
            return new ApplyResult(
                    true,
                    false,
                    true,
                    compressedSize,
                    sha256
            );
        }
    }
}
