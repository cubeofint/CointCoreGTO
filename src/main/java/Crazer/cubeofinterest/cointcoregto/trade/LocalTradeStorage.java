package Crazer.cubeofinterest.cointcoregto.trade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

final class LocalTradeStorage extends TradeStorage {
    private static final int FORMAT_VERSION = 1;
    private static final long ONLINE_TIMEOUT_SECONDS = 45L;

    private final Path statePath;
    private final Path auditPath;
    private final Map<UUID, LocalPresence> players = new HashMap<>();
    private final Map<UUID, MutableTrade> trades = new HashMap<>();

    LocalTradeStorage(Path statePath) {
        this.statePath = statePath;
        this.auditPath = statePath.resolveSibling("cointcoregto-local-trade-audit.log");
    }

    @Override
    synchronized void initialize() throws Exception {
        Files.createDirectories(statePath.getParent());
        load();
        for (LocalPresence presence : players.values()) {
            presence.online = false;
        }
        save();
    }

    @Override
    synchronized void heartbeat(UUID uuid, String name, String nodeId, int tierIndex, boolean online) throws Exception {
        LocalPresence presence = players.computeIfAbsent(uuid, ignored -> new LocalPresence());
        Instant now = Instant.now();
        String normalizedName = safe(name);
        String normalizedNode = safe(nodeId);
        boolean persist = presence.uuid == null
                || !normalizedName.equals(presence.name)
                || !normalizedNode.equals(presence.nodeId)
                || presence.tierIndex != tierIndex
                || presence.online != online
                || presence.heartbeatAt == null
                || !presence.heartbeatAt.plusSeconds(30L).isAfter(now);
        presence.uuid = uuid;
        presence.name = normalizedName;
        presence.nodeId = normalizedNode;
        presence.tierIndex = tierIndex;
        presence.online = online;
        presence.heartbeatAt = now;
        if (persist) {
            save();
        }
    }

    @Override
    synchronized Optional<PlayerPresence> findOnlinePlayer(String name) {
        Instant threshold = Instant.now().minusSeconds(ONLINE_TIMEOUT_SECONDS);
        return players.values().stream()
                .filter(presence -> presence.online)
                .filter(presence -> presence.heartbeatAt != null && !presence.heartbeatAt.isBefore(threshold))
                .filter(presence -> presence.name.equalsIgnoreCase(safe(name)))
                .max(Comparator.comparing(presence -> presence.heartbeatAt))
                .map(LocalPresence::snapshot);
    }

    @Override
    synchronized Optional<PlayerPresence> findPlayerByName(String name) {
        return players.values().stream()
                .filter(presence -> presence.name.equalsIgnoreCase(safe(name)))
                .max(Comparator.comparing(presence -> presence.heartbeatAt == null ? Instant.EPOCH : presence.heartbeatAt))
                .map(LocalPresence::snapshot);
    }

    @Override
    synchronized Optional<PlayerPresence> findPlayer(UUID uuid) {
        LocalPresence presence = players.get(uuid);
        return presence == null ? Optional.empty() : Optional.of(presence.snapshot());
    }

    @Override
    synchronized UUID createInvite(PlayerPresence initiator, PlayerPresence target, int ttlSeconds) throws Exception {
        expireInvitesInternal();
        if (hasActiveTrade(initiator.uuid()) || hasActiveTrade(target.uuid())) {
            throw new IOException("Один из игроков уже участвует в другой сделке");
        }
        Instant now = Instant.now();
        MutableTrade trade = new MutableTrade();
        trade.tradeId = UUID.randomUUID();
        trade.initiatorUuid = initiator.uuid();
        trade.initiatorName = safe(initiator.name());
        trade.initiatorNode = safe(initiator.nodeId());
        trade.targetUuid = target.uuid();
        trade.targetName = safe(target.name());
        trade.targetNode = safe(target.nodeId());
        trade.status = TradeStatus.INVITED;
        trade.expiresAt = now.plusSeconds(Math.max(30, ttlSeconds));
        trade.createdAt = now;
        trade.updatedAt = now;
        trades.put(trade.tradeId, trade);
        save();
        return trade.tradeId;
    }

    @Override
    synchronized Optional<TradeRecord> pendingInvite(UUID targetUuid, String initiatorName) throws Exception {
        if (expireInvitesInternal() > 0) {
            save();
        }
        return trades.values().stream()
                .filter(trade -> trade.status == TradeStatus.INVITED)
                .filter(trade -> trade.targetUuid.equals(targetUuid))
                .filter(trade -> trade.initiatorName.equalsIgnoreCase(safe(initiatorName)))
                .filter(trade -> trade.expiresAt.isAfter(Instant.now()))
                .max(Comparator.comparing(trade -> trade.createdAt))
                .map(MutableTrade::snapshot);
    }

    @Override
    synchronized boolean accept(UUID tradeId, UUID targetUuid, String targetNode) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null
                || trade.status != TradeStatus.INVITED
                || !trade.targetUuid.equals(targetUuid)
                || !trade.expiresAt.isAfter(Instant.now())) {
            return false;
        }
        trade.status = TradeStatus.OPEN;
        trade.targetNode = safe(targetNode);
        trade.expiresAt = Instant.now().plusSeconds(86_400L);
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean deny(UUID tradeId, UUID targetUuid) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || trade.status != TradeStatus.INVITED || !trade.targetUuid.equals(targetUuid)) {
            return false;
        }
        trade.status = TradeStatus.DENIED;
        trade.errorText = "Приглашение отклонено";
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean cancel(UUID tradeId, UUID actorUuid, String reason) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null
                || (trade.status != TradeStatus.INVITED && trade.status != TradeStatus.OPEN)
                || (!trade.initiatorUuid.equals(actorUuid) && !trade.targetUuid.equals(actorUuid))) {
            return false;
        }
        trade.status = TradeStatus.CANCELLED;
        trade.errorText = truncate(reason, 1000);
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized Optional<TradeRecord> findActive(UUID playerUuid) {
        return trades.values().stream()
                .filter(trade -> trade.initiatorUuid.equals(playerUuid) || trade.targetUuid.equals(playerUuid))
                .filter(trade -> isActiveStorageStatus(trade.status))
                .max(Comparator.comparing(trade -> trade.updatedAt))
                .map(MutableTrade::snapshot);
    }

    @Override
    synchronized Optional<TradeRecord> find(UUID tradeId) {
        MutableTrade trade = trades.get(tradeId);
        return trade == null ? Optional.empty() : Optional.of(trade.snapshot());
    }

    @Override
    synchronized boolean updateOffer(UUID tradeId, TradeSide side, List<ItemStack> items, long currency) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || trade.status != TradeStatus.OPEN) {
            return false;
        }
        if (side == TradeSide.INITIATOR) {
            trade.initiatorOffer = copyStacks(items);
            trade.initiatorCurrency = Math.max(0L, currency);
        } else {
            trade.targetOffer = copyStacks(items);
            trade.targetCurrency = Math.max(0L, currency);
        }
        trade.initiatorReady = false;
        trade.targetReady = false;
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean setReady(UUID tradeId, TradeSide side, boolean ready) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || trade.status != TradeStatus.OPEN) {
            return false;
        }
        if (side == TradeSide.INITIATOR) {
            trade.initiatorReady = ready;
        } else {
            trade.targetReady = ready;
        }
        if (trade.initiatorReady && trade.targetReady) {
            trade.status = TradeStatus.PREPARING;
        }
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean markPrepared(UUID tradeId, TradeSide side) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || (trade.status != TradeStatus.PREPARING && trade.status != TradeStatus.CANCELLED)) {
            return false;
        }
        if (side == TradeSide.INITIATOR) {
            if (trade.initiatorPrepared) {
                return false;
            }
            trade.initiatorPrepared = true;
        } else {
            if (trade.targetPrepared) {
                return false;
            }
            trade.targetPrepared = true;
        }
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean claimSettlement(UUID tradeId) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null
                || trade.status != TradeStatus.PREPARING
                || !trade.initiatorPrepared
                || !trade.targetPrepared) {
            return false;
        }
        trade.status = TradeStatus.SETTLING;
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized void markCommitting(UUID tradeId) throws Exception {
        updateStatus(tradeId, TradeStatus.COMMITTING, "");
    }

    @Override
    synchronized void markCancelled(UUID tradeId, String error) throws Exception {
        updateStatus(tradeId, TradeStatus.CANCELLED, error);
    }

    @Override
    synchronized boolean markDelivered(UUID tradeId, TradeSide side) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || trade.status != TradeStatus.COMMITTING) {
            return false;
        }
        if (side == TradeSide.INITIATOR) {
            if (trade.initiatorDelivered) {
                return false;
            }
            trade.initiatorDelivered = true;
        } else {
            if (trade.targetDelivered) {
                return false;
            }
            trade.targetDelivered = true;
        }
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized boolean markReturned(UUID tradeId, TradeSide side) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null || trade.status != TradeStatus.CANCELLED) {
            return false;
        }
        if (side == TradeSide.INITIATOR) {
            if (trade.initiatorReturned) {
                return false;
            }
            trade.initiatorReturned = true;
        } else {
            if (trade.targetReturned) {
                return false;
            }
            trade.targetReturned = true;
        }
        touch(trade);
        save();
        return true;
    }

    @Override
    synchronized void finishIfComplete(UUID tradeId) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade != null
                && trade.status == TradeStatus.COMMITTING
                && trade.initiatorDelivered
                && trade.targetDelivered) {
            trade.status = TradeStatus.COMPLETED;
            touch(trade);
            save();
        }
    }

    @Override
    synchronized void finishCancelledIfReturned(UUID tradeId) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade != null
                && trade.status == TradeStatus.CANCELLED
                && (!trade.initiatorPrepared || trade.initiatorReturned)
                && (!trade.targetPrepared || trade.targetReturned)) {
            trade.status = TradeStatus.EXPIRED;
            touch(trade);
            save();
        }
    }

    @Override
    synchronized int expireInvites() throws Exception {
        int expired = expireInvitesInternal();
        if (expired > 0) {
            save();
        }
        return expired;
    }

    @Override
    synchronized void audit(
            UUID tradeId,
            String eventType,
            String nodeId,
            UUID actorUuid,
            String actorName,
            String details,
            boolean suspicious
    ) throws Exception {
        Files.createDirectories(auditPath.getParent());
        String line = Instant.now()
                + "\t" + safe(tradeId == null ? "" : tradeId.toString())
                + "\t" + truncate(eventType, 32)
                + "\t" + truncate(nodeId, 64)
                + "\t" + safe(actorUuid == null ? "" : actorUuid.toString())
                + "\t" + truncate(actorName, 64)
                + "\t" + suspicious
                + "\t" + safe(details).replace('\n', ' ').replace('\r', ' ')
                + System.lineSeparator();
        Files.writeString(
                auditPath,
                line,
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
        );
    }

    @Override
    synchronized List<String> history(UUID playerUuid, int limit) {
        return trades.values().stream()
                .filter(trade -> trade.initiatorUuid.equals(playerUuid) || trade.targetUuid.equals(playerUuid))
                .sorted(Comparator.comparing((MutableTrade trade) -> trade.updatedAt).reversed())
                .limit(Math.max(1, Math.min(50, limit)))
                .map(trade -> trade.tradeId + " | " + trade.initiatorName + " <-> " + trade.targetName
                        + " | " + trade.status + " | " + trade.updatedAt)
                .toList();
    }

    @Override
    String mode() {
        return "local-file";
    }

    private void updateStatus(UUID tradeId, TradeStatus status, String error) throws Exception {
        MutableTrade trade = trades.get(tradeId);
        if (trade == null) {
            return;
        }
        trade.status = status;
        trade.errorText = truncate(error, 1000);
        touch(trade);
        save();
    }

    private int expireInvitesInternal() {
        Instant now = Instant.now();
        int expired = 0;
        for (MutableTrade trade : trades.values()) {
            if (trade.status == TradeStatus.INVITED && !trade.expiresAt.isAfter(now)) {
                trade.status = TradeStatus.EXPIRED;
                trade.errorText = "Срок приглашения истёк";
                trade.updatedAt = now;
                expired++;
            }
        }
        return expired;
    }

    private boolean hasActiveTrade(UUID uuid) {
        return trades.values().stream()
                .anyMatch(trade -> (trade.initiatorUuid.equals(uuid) || trade.targetUuid.equals(uuid))
                        && isActiveStorageStatus(trade.status));
    }

    private static boolean isActiveStorageStatus(TradeStatus status) {
        return status == TradeStatus.INVITED
                || status == TradeStatus.OPEN
                || status == TradeStatus.PREPARING
                || status == TradeStatus.SETTLING
                || status == TradeStatus.COMMITTING
                || status == TradeStatus.CANCELLED;
    }

    private static void touch(MutableTrade trade) {
        trade.updatedAt = Instant.now();
    }

    private void load() throws IOException {
        players.clear();
        trades.clear();
        if (!Files.exists(statePath)) {
            return;
        }
        String content = Files.readString(statePath, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return;
        }
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();
        int version = getInt(root, "version", FORMAT_VERSION);
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported local trade state version: " + version);
        }
        JsonArray playerArray = array(root, "players");
        for (JsonElement element : playerArray) {
            JsonObject object = element.getAsJsonObject();
            LocalPresence presence = new LocalPresence();
            presence.uuid = UUID.fromString(getString(object, "uuid", ""));
            presence.name = getString(object, "name", "");
            presence.nodeId = getString(object, "node", "local");
            presence.tierIndex = getInt(object, "tier", -1);
            presence.online = getBoolean(object, "online", false);
            presence.heartbeatAt = parseInstant(getString(object, "heartbeat", Instant.EPOCH.toString()));
            players.put(presence.uuid, presence);
        }
        JsonArray tradeArray = array(root, "trades");
        for (JsonElement element : tradeArray) {
            MutableTrade trade = MutableTrade.fromJson(element.getAsJsonObject());
            trades.put(trade.tradeId, trade);
        }
    }

    private void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray playerArray = new JsonArray();
        players.values().stream()
                .sorted(Comparator.comparing(presence -> presence.uuid.toString()))
                .forEach(presence -> playerArray.add(presence.toJson()));
        root.add("players", playerArray);
        JsonArray tradeArray = new JsonArray();
        trades.values().stream()
                .sorted(Comparator.comparing(trade -> trade.createdAt))
                .forEach(trade -> tradeArray.add(trade.toJson()));
        root.add("trades", tradeArray);

        Files.createDirectories(statePath.getParent());
        Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
        Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    private static String getString(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsInt();
    }

    private static long getLong(JsonObject object, String name, long fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    private static boolean getBoolean(JsonObject object, String name, boolean fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsBoolean();
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return Instant.EPOCH;
        }
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        ArrayList<ItemStack> result = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            result.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return List.copyOf(result);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int maximum) {
        String safe = safe(value);
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private static final class LocalPresence {
        private UUID uuid;
        private String name = "";
        private String nodeId = "local";
        private int tierIndex = -1;
        private boolean online;
        private Instant heartbeatAt = Instant.EPOCH;

        private PlayerPresence snapshot() {
            return new PlayerPresence(uuid, name, nodeId, tierIndex);
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("uuid", uuid.toString());
            object.addProperty("name", name);
            object.addProperty("node", nodeId);
            object.addProperty("tier", tierIndex);
            object.addProperty("online", online);
            object.addProperty("heartbeat", heartbeatAt.toString());
            return object;
        }
    }

    private static final class MutableTrade {
        private UUID tradeId;
        private UUID initiatorUuid;
        private String initiatorName = "";
        private String initiatorNode = "local";
        private UUID targetUuid;
        private String targetName = "";
        private String targetNode = "local";
        private TradeStatus status = TradeStatus.INVITED;
        private List<ItemStack> initiatorOffer = List.of();
        private List<ItemStack> targetOffer = List.of();
        private long initiatorCurrency;
        private long targetCurrency;
        private boolean initiatorReady;
        private boolean targetReady;
        private boolean initiatorPrepared;
        private boolean targetPrepared;
        private boolean initiatorDelivered;
        private boolean targetDelivered;
        private boolean initiatorReturned;
        private boolean targetReturned;
        private String errorText = "";
        private Instant expiresAt = Instant.EPOCH;
        private Instant createdAt = Instant.EPOCH;
        private Instant updatedAt = Instant.EPOCH;

        private TradeRecord snapshot() {
            return new TradeRecord(
                    tradeId,
                    initiatorUuid,
                    initiatorName,
                    initiatorNode,
                    targetUuid,
                    targetName,
                    targetNode,
                    status,
                    initiatorOffer,
                    targetOffer,
                    initiatorCurrency,
                    targetCurrency,
                    initiatorReady,
                    targetReady,
                    initiatorPrepared,
                    targetPrepared,
                    initiatorDelivered,
                    targetDelivered,
                    initiatorReturned,
                    targetReturned,
                    errorText,
                    createdAt,
                    updatedAt
            );
        }

        private JsonObject toJson() {
            JsonObject object = new JsonObject();
            object.addProperty("trade_id", tradeId.toString());
            object.addProperty("initiator_uuid", initiatorUuid.toString());
            object.addProperty("initiator_name", initiatorName);
            object.addProperty("initiator_node", initiatorNode);
            object.addProperty("target_uuid", targetUuid.toString());
            object.addProperty("target_name", targetName);
            object.addProperty("target_node", targetNode);
            object.addProperty("status", status.name());
            object.addProperty("initiator_offer", TradeItemCodec.encode(initiatorOffer));
            object.addProperty("target_offer", TradeItemCodec.encode(targetOffer));
            object.addProperty("initiator_currency", initiatorCurrency);
            object.addProperty("target_currency", targetCurrency);
            object.addProperty("initiator_ready", initiatorReady);
            object.addProperty("target_ready", targetReady);
            object.addProperty("initiator_prepared", initiatorPrepared);
            object.addProperty("target_prepared", targetPrepared);
            object.addProperty("initiator_delivered", initiatorDelivered);
            object.addProperty("target_delivered", targetDelivered);
            object.addProperty("initiator_returned", initiatorReturned);
            object.addProperty("target_returned", targetReturned);
            object.addProperty("error_text", errorText);
            object.addProperty("expires_at", expiresAt.toString());
            object.addProperty("created_at", createdAt.toString());
            object.addProperty("updated_at", updatedAt.toString());
            return object;
        }

        private static MutableTrade fromJson(JsonObject object) {
            MutableTrade trade = new MutableTrade();
            trade.tradeId = UUID.fromString(getString(object, "trade_id", ""));
            trade.initiatorUuid = UUID.fromString(getString(object, "initiator_uuid", ""));
            trade.initiatorName = getString(object, "initiator_name", "");
            trade.initiatorNode = getString(object, "initiator_node", "local");
            trade.targetUuid = UUID.fromString(getString(object, "target_uuid", ""));
            trade.targetName = getString(object, "target_name", "");
            trade.targetNode = getString(object, "target_node", "local");
            trade.status = TradeStatus.valueOf(getString(object, "status", TradeStatus.EXPIRED.name()).toUpperCase(Locale.ROOT));
            trade.initiatorOffer = TradeItemCodec.decode(getString(object, "initiator_offer", ""), TradeService.OFFER_SLOTS);
            trade.targetOffer = TradeItemCodec.decode(getString(object, "target_offer", ""), TradeService.OFFER_SLOTS);
            trade.initiatorCurrency = Math.max(0L, getLong(object, "initiator_currency", 0L));
            trade.targetCurrency = Math.max(0L, getLong(object, "target_currency", 0L));
            trade.initiatorReady = getBoolean(object, "initiator_ready", false);
            trade.targetReady = getBoolean(object, "target_ready", false);
            trade.initiatorPrepared = getBoolean(object, "initiator_prepared", false);
            trade.targetPrepared = getBoolean(object, "target_prepared", false);
            trade.initiatorDelivered = getBoolean(object, "initiator_delivered", false);
            trade.targetDelivered = getBoolean(object, "target_delivered", false);
            trade.initiatorReturned = getBoolean(object, "initiator_returned", false);
            trade.targetReturned = getBoolean(object, "target_returned", false);
            trade.errorText = getString(object, "error_text", "");
            trade.expiresAt = parseInstant(getString(object, "expires_at", Instant.EPOCH.toString()));
            trade.createdAt = parseInstant(getString(object, "created_at", Instant.EPOCH.toString()));
            trade.updatedAt = parseInstant(getString(object, "updated_at", trade.createdAt.toString()));
            return trade;
        }
    }
}
