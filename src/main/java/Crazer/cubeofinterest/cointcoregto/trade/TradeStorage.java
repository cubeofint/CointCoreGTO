package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

abstract class TradeStorage {
    abstract void initialize() throws Exception;

    abstract void heartbeat(UUID uuid, String name, String nodeId, int tierIndex, boolean online) throws Exception;

    abstract Optional<PlayerPresence> findOnlinePlayer(String name) throws Exception;

    abstract Optional<PlayerPresence> findPlayerByName(String name) throws Exception;

    abstract Optional<PlayerPresence> findPlayer(UUID uuid) throws Exception;

    abstract UUID createInvite(PlayerPresence initiator, PlayerPresence target, int ttlSeconds) throws Exception;

    abstract Optional<TradeRecord> pendingInvite(UUID targetUuid, String initiatorName) throws Exception;

    abstract boolean accept(UUID tradeId, UUID targetUuid, String targetNode) throws Exception;

    abstract boolean deny(UUID tradeId, UUID targetUuid) throws Exception;

    abstract boolean cancel(UUID tradeId, UUID actorUuid, String reason) throws Exception;

    abstract Optional<TradeRecord> findActive(UUID playerUuid) throws Exception;

    abstract Optional<TradeRecord> find(UUID tradeId) throws Exception;

    abstract boolean updateOffer(UUID tradeId, TradeSide side, List<ItemStack> items, long currency) throws Exception;

    abstract boolean setReady(UUID tradeId, TradeSide side, boolean ready) throws Exception;

    abstract boolean markPrepared(UUID tradeId, TradeSide side) throws Exception;

    abstract boolean claimSettlement(UUID tradeId) throws Exception;

    abstract void markCommitting(UUID tradeId) throws Exception;

    abstract void markCancelled(UUID tradeId, String error) throws Exception;

    abstract boolean markDelivered(UUID tradeId, TradeSide side) throws Exception;

    abstract boolean markReturned(UUID tradeId, TradeSide side) throws Exception;

    abstract void finishIfComplete(UUID tradeId) throws Exception;

    abstract void finishCancelledIfReturned(UUID tradeId) throws Exception;

    abstract int expireInvites() throws Exception;

    abstract void audit(
            UUID tradeId,
            String eventType,
            String nodeId,
            UUID actorUuid,
            String actorName,
            String details,
            boolean suspicious
    ) throws Exception;

    abstract List<String> history(UUID playerUuid, int limit) throws Exception;

    abstract String mode();

    record PlayerPresence(UUID uuid, String name, String nodeId, int tierIndex) {
    }
}
