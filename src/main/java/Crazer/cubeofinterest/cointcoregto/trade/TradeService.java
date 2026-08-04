package Crazer.cubeofinterest.cointcoregto.trade;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import Crazer.cubeofinterest.cointcoregto.CointCoreGTODiscordProxy;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyBalance;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyContext;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyOperationResult;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyService;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyConfig;
import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerProgression;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TradeService {
    public static final int OFFER_SLOTS = 6;

    private static final String RECOVERY_TAG = "CointCoreGTOTradeRecovery";
    private static final String PHASE_PREPARE_STARTED = "PREPARE_STARTED";
    private static final String PHASE_PREPARED = "PREPARED";
    private static final String PHASE_DELIVERY_STARTED = "DELIVERY_STARTED";
    private static final String PHASE_DELIVERED = "DELIVERED";
    private static final String PHASE_RETURN_STARTED = "RETURN_STARTED";
    private static final String PHASE_RETURNED = "RETURNED";

    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:Trade");
    private static final Map<UUID, TradeRecord> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> OPENED_ONCE = new ConcurrentHashMap<>();
    private static final Set<String> NOTIFICATIONS = ConcurrentHashMap.newKeySet();

    private static volatile MinecraftServer server;
    private static volatile ClusterConfig clusterConfig;
    private static volatile TradeDatabase database;
    private static volatile String nodeId = "unknown";
    private static volatile String lastError = "";
    private static int tickCounter;

    private TradeService() {
    }

    public static synchronized void start(MinecraftServer minecraftServer) {
        stop();
        server = minecraftServer;
        try {
            clusterConfig = ClusterConfig.load();
            nodeId = clusterConfig.nodeId();
            database = new TradeDatabase(clusterConfig);
            database.initialize();
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                heartbeat(player, true);
            }
            lastError = "";
            LOGGER.info("Cross-node trade service started on node {}", nodeId);
        } catch (Exception exception) {
            lastError = message(exception);
            database = null;
            LOGGER.error("Unable to start cross-node trade service", exception);
        }
    }

    public static synchronized void stop() {
        TradeDatabase current = database;
        MinecraftServer currentServer = server;
        if (current != null && currentServer != null) {
            for (ServerPlayer player : currentServer.getPlayerList().getPlayers()) {
                try {
                    current.heartbeat(player.getUUID(), player.getGameProfile().getName(), nodeId,
                            ExchangerProgression.playerTier(player), false);
                } catch (Exception ignored) {
                }
            }
        }
        CACHE.clear();
        OPENED_ONCE.clear();
        NOTIFICATIONS.clear();
        database = null;
        clusterConfig = null;
        server = null;
        tickCounter = 0;
    }

    public static boolean available() {
        return database != null;
    }

    public static String lastError() {
        return lastError;
    }

    public static String nodeId() {
        return nodeId;
    }

    public static void onJoin(ServerPlayer player) {
        heartbeat(player, true);
        OPENED_ONCE.remove(player.getUUID());
    }

    public static void onLeave(ServerPlayer player) {
        heartbeat(player, false);
        OPENED_ONCE.remove(player.getUUID());
    }

    public static void tick(MinecraftServer minecraftServer) {
        if (!available()) {
            return;
        }
        tickCounter++;
        if (tickCounter < TradeConfig.pollIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        try {
            database.expireInvites();
            for (ServerPlayer player : minecraftServer.getPlayerList().getPlayers()) {
                heartbeat(player, true);
                Optional<TradeRecord> active = database.findActive(player.getUUID());
                if (active.isEmpty()) {
                    CACHE.entrySet().removeIf(entry -> entry.getValue().sideOf(player.getUUID()) != null);
                    OPENED_ONCE.remove(player.getUUID());
                    continue;
                }
                TradeRecord trade = active.get();
                CACHE.put(trade.tradeId(), trade);
                notifyAndOpen(player, trade);
                processLocal(player, trade);
            }
        } catch (Exception exception) {
            lastError = message(exception);
            LOGGER.error("Trade service tick failed", exception);
        }
    }

    public static Optional<TradeRecord> active(UUID playerUuid) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            Optional<TradeRecord> result = database.findActive(playerUuid);
            result.ifPresent(record -> CACHE.put(record.tradeId(), record));
            return result;
        } catch (Exception exception) {
            lastError = message(exception);
            return Optional.empty();
        }
    }

    public static Optional<TradeRecord> find(UUID tradeId) {
        TradeRecord cached = CACHE.get(tradeId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (!available()) {
            return Optional.empty();
        }
        try {
            Optional<TradeRecord> result = database.find(tradeId);
            result.ifPresent(record -> CACHE.put(record.tradeId(), record));
            return result;
        } catch (Exception exception) {
            lastError = message(exception);
            return Optional.empty();
        }
    }

    public static OperationResult invite(ServerPlayer initiator, String targetName) {
        if (!available()) {
            return OperationResult.failure("Система обмена недоступна: " + lastError);
        }
        if (targetName == null || targetName.isBlank()) {
            return OperationResult.failure("Укажи ник игрока");
        }
        try {
            heartbeat(initiator, true);
            Optional<TradeDatabase.PlayerPresence> targetOptional = database.findOnlinePlayer(targetName);
            if (targetOptional.isEmpty()) {
                return OperationResult.failure("Игрок не найден в сети кластера");
            }
            TradeDatabase.PlayerPresence target = targetOptional.get();
            if (target.uuid().equals(initiator.getUUID())) {
                return OperationResult.failure("Нельзя обмениваться с самим собой");
            }
            TradeDatabase.PlayerPresence source = new TradeDatabase.PlayerPresence(
                    initiator.getUUID(), initiator.getGameProfile().getName(), nodeId,
                    ExchangerProgression.playerTier(initiator)
            );
            UUID tradeId = database.createInvite(source, target, TradeConfig.inviteTtlSeconds());
            audit(tradeId, "INVITE", initiator, "target=" + target.name() + ", target_node=" + target.nodeId(), false);
            return OperationResult.success("Приглашение отправлено игроку " + target.name(), tradeId);
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult accept(ServerPlayer target, String initiatorName) {
        if (!available()) {
            return OperationResult.failure("Система обмена недоступна: " + lastError);
        }
        try {
            heartbeat(target, true);
            Optional<TradeRecord> pending = database.pendingInvite(target.getUUID(), initiatorName);
            if (pending.isEmpty()) {
                return OperationResult.failure("Активное приглашение от этого игрока не найдено");
            }
            TradeRecord trade = pending.get();
            if (!database.accept(trade.tradeId(), target.getUUID(), nodeId)) {
                return OperationResult.failure("Приглашение уже недействительно");
            }
            audit(trade.tradeId(), "ACCEPT", target, "accepted", false);
            Optional<TradeRecord> refreshed = database.find(trade.tradeId());
            refreshed.ifPresent(record -> {
                CACHE.put(record.tradeId(), record);
                open(target, record);
            });
            return OperationResult.success("Обмен принят", trade.tradeId());
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult deny(ServerPlayer target, String initiatorName) {
        if (!available()) {
            return OperationResult.failure("Система обмена недоступна");
        }
        try {
            Optional<TradeRecord> pending = database.pendingInvite(target.getUUID(), initiatorName);
            if (pending.isEmpty()) {
                return OperationResult.failure("Приглашение не найдено");
            }
            TradeRecord trade = pending.get();
            if (!database.deny(trade.tradeId(), target.getUUID())) {
                return OperationResult.failure("Приглашение уже недействительно");
            }
            audit(trade.tradeId(), "DENY", target, "denied", false);
            return OperationResult.success("Приглашение отклонено", trade.tradeId());
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult openActive(ServerPlayer player) {
        Optional<TradeRecord> active = active(player.getUUID());
        if (active.isEmpty() || active.get().status() == TradeStatus.INVITED) {
            return OperationResult.failure("Открытая сделка не найдена");
        }
        open(player, active.get());
        return OperationResult.success("Интерфейс обмена открыт", active.get().tradeId());
    }

    public static OperationResult cancel(ServerPlayer player) {
        Optional<TradeRecord> active = active(player.getUUID());
        if (active.isEmpty()) {
            return OperationResult.failure("Активная сделка не найдена");
        }
        TradeRecord trade = active.get();
        try {
            if (!database.cancel(trade.tradeId(), player.getUUID(), "Отменено игроком " + player.getGameProfile().getName())) {
                return OperationResult.failure("После начала подготовки сделку уже нельзя отменить вручную");
            }
            audit(trade.tradeId(), "CANCEL", player, "cancelled by player", false);
            player.closeContainer();
            return OperationResult.success("Сделка отменена", trade.tradeId());
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult setOfferItem(ServerPlayer player, UUID tradeId, int slot, ItemStack stack) {
        if (slot < 0 || slot >= OFFER_SLOTS) {
            return OperationResult.failure("Некорректный слот");
        }
        try {
            TradeRecord trade = database.find(tradeId).orElse(null);
            if (trade == null || trade.status() != TradeStatus.OPEN) {
                return OperationResult.failure("Предложение уже нельзя изменять");
            }
            TradeSide side = trade.sideOf(player.getUUID());
            if (side == null) {
                return OperationResult.failure("Игрок не участвует в этой сделке");
            }
            List<ItemStack> offer = mutableOffer(trade.offer(side));
            offer.set(slot, sanitize(stack));
            if (!database.updateOffer(tradeId, side, offer, trade.currency(side))) {
                return OperationResult.failure("Не удалось обновить предложение");
            }
            refresh(tradeId);
            audit(tradeId, "OFFER_ITEM", player, "slot=" + slot + ", offer=" + TradeItemCodec.encode(offer),
                    containsSuspicious(offer));
            return OperationResult.success("Предложение обновлено", tradeId);
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult setCurrency(ServerPlayer player, UUID tradeId, long amount) {
        if (amount < 0L || amount > CurrencyService.descriptor().maximumBalance()) {
            return OperationResult.failure("Некорректная сумма валюты");
        }
        try {
            TradeRecord trade = database.find(tradeId).orElse(null);
            if (trade == null || trade.status() != TradeStatus.OPEN) {
                return OperationResult.failure("Предложение уже нельзя изменять");
            }
            TradeSide side = trade.sideOf(player.getUUID());
            if (side == null) {
                return OperationResult.failure("Игрок не участвует в сделке");
            }
            if (!database.updateOffer(tradeId, side, trade.offer(side), amount)) {
                return OperationResult.failure("Не удалось обновить сумму");
            }
            refresh(tradeId);
            boolean suspicious = amount >= TradeConfig.suspiciousCurrencyThreshold()
                    && TradeConfig.suspiciousCurrencyThreshold() > 0L;
            audit(tradeId, "OFFER_CURRENCY", player, "amount=" + amount, suspicious);
            return OperationResult.success("Сумма обновлена", tradeId);
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static OperationResult setReady(ServerPlayer player, UUID tradeId, boolean ready) {
        try {
            TradeRecord trade = database.find(tradeId).orElse(null);
            if (trade == null || trade.status() != TradeStatus.OPEN) {
                return OperationResult.failure("Сделка не находится на этапе подтверждения");
            }
            TradeSide side = trade.sideOf(player.getUUID());
            if (side == null) {
                return OperationResult.failure("Игрок не участвует в сделке");
            }
            if (ready) {
                String validation = validateTrade(trade);
                if (!validation.isEmpty()) {
                    return OperationResult.failure(validation);
                }
                if (!hasItems(player.getInventory(), trade.offer(side))) {
                    return OperationResult.failure("В инвентаре недостаточно предложенных предметов");
                }
                long netPayment = netPaymentFor(trade, side);
                if (netPayment > 0L) {
                    CurrencyBalance balance = CurrencyService.balance(player.getUUID());
                    if (!balance.success() || balance.amount() < netPayment) {
                        return OperationResult.failure("Недостаточно валюты для завершения сделки");
                    }
                }
            }
            if (!database.setReady(tradeId, side, ready)) {
                return OperationResult.failure("Не удалось изменить подтверждение");
            }
            refresh(tradeId);
            audit(tradeId, ready ? "READY" : "UNREADY", player, "ready=" + ready, false);
            return OperationResult.success(ready ? "Готовность подтверждена" : "Готовность снята", tradeId);
        } catch (Exception exception) {
            return OperationResult.failure(message(exception));
        }
    }

    public static Optional<UUID> playerUuidByName(String name) {
        if (!available()) {
            return Optional.empty();
        }
        try {
            return database.findPlayerByName(name).map(TradeDatabase.PlayerPresence::uuid);
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public static List<String> history(UUID playerUuid, int limit) {
        if (!available()) {
            return List.of();
        }
        try {
            return database.history(playerUuid, limit);
        } catch (Exception exception) {
            return List.of("Ошибка: " + message(exception));
        }
    }

    private static void processLocal(ServerPlayer player, TradeRecord initial) throws Exception {
        TradeRecord trade = database.find(initial.tradeId()).orElse(initial);
        TradeSide side = trade.sideOf(player.getUUID());
        if (side == null) {
            return;
        }

        if (trade.status() == TradeStatus.CANCELLED && !trade.prepared(side)
                && isRecoveryMarker(player, trade.tradeId(), PHASE_PREPARED)) {
            database.markPrepared(trade.tradeId(), side);
            trade = database.find(trade.tradeId()).orElse(trade);
        }
        if (trade.prepared(side) && isRecoveryMarker(player, trade.tradeId(), PHASE_PREPARED)) {
            clearRecoveryMarker(player, trade.tradeId());
        }
        if (trade.delivered(side) && isRecoveryMarker(player, trade.tradeId(), PHASE_DELIVERED)) {
            clearRecoveryMarker(player, trade.tradeId());
        }
        if (trade.returned(side) && isRecoveryMarker(player, trade.tradeId(), PHASE_RETURNED)) {
            clearRecoveryMarker(player, trade.tradeId());
        }

        if (trade.status() == TradeStatus.PREPARING && !trade.prepared(side)) {
            prepare(player, trade, side);
            trade = database.find(trade.tradeId()).orElse(trade);
        }

        if (trade.status() == TradeStatus.PREPARING
                && trade.initiatorPrepared() && trade.targetPrepared()
                && database.claimSettlement(trade.tradeId())) {
            trade = database.find(trade.tradeId()).orElse(trade);
        }

        if (trade.status() == TradeStatus.SETTLING) {
            settleCurrency(trade);
            trade = database.find(trade.tradeId()).orElse(trade);
        }

        if (trade.status() == TradeStatus.COMMITTING && !trade.delivered(side)) {
            deliver(player, trade, side);
            database.finishIfComplete(trade.tradeId());
            refresh(trade.tradeId());
        }

        if (trade.status() == TradeStatus.CANCELLED) {
            rollback(player, trade, side);
            database.finishCancelledIfReturned(trade.tradeId());
            refresh(trade.tradeId());
        }
    }

    private static void prepare(ServerPlayer player, TradeRecord trade, TradeSide side) throws Exception {
        List<ItemStack> ownOffer = trade.offer(side);
        String phase = recoveryPhase(player, trade.tradeId());
        if (PHASE_PREPARED.equals(phase)) {
            if (database.markPrepared(trade.tradeId(), side)) {
                audit(trade.tradeId(), "PREPARED_RECOVERED", player,
                        "escrow=" + TradeItemCodec.encode(ownOffer), containsSuspicious(ownOffer));
            }
            clearRecoveryMarker(player, trade.tradeId());
            refresh(trade.tradeId());
            return;
        }

        String validation = validateTrade(trade);
        if (!validation.isEmpty()) {
            database.markCancelled(trade.tradeId(), validation);
            clearRecoveryMarker(player, trade.tradeId());
            audit(trade.tradeId(), "PREPARE_FAILED", player, validation, true);
            return;
        }
        if (!hasItems(player.getInventory(), ownOffer)) {
            database.markCancelled(trade.tradeId(), "У игрока " + player.getGameProfile().getName()
                    + " недостаточно предложенных предметов");
            clearRecoveryMarker(player, trade.tradeId());
            audit(trade.tradeId(), "PREPARE_FAILED", player, "missing offered items", true);
            return;
        }

        if (!PHASE_PREPARE_STARTED.equals(phase)) {
            setRecoveryMarker(player, trade.tradeId(), PHASE_PREPARE_STARTED);
        }
        if (!removeExact(player.getInventory(), ownOffer)) {
            database.markCancelled(trade.tradeId(), "Не удалось извлечь предложенные предметы");
            clearRecoveryMarker(player, trade.tradeId());
            return;
        }
        setRecoveryMarker(player, trade.tradeId(), PHASE_PREPARED);
        if (!database.markPrepared(trade.tradeId(), side)) {
            return;
        }
        clearRecoveryMarker(player, trade.tradeId());
        player.getInventory().setChanged();
        audit(trade.tradeId(), "PREPARED", player, "escrow=" + TradeItemCodec.encode(ownOffer),
                containsSuspicious(ownOffer));
        refresh(trade.tradeId());
    }

    private static void settleCurrency(TradeRecord trade) throws Exception {
        long initiatorAmount = trade.initiatorCurrency();
        long targetAmount = trade.targetCurrency();
        if (initiatorAmount == targetAmount) {
            database.markCommitting(trade.tradeId());
            audit(trade.tradeId(), "CURRENCY_SETTLED", null, "net=0", false);
            return;
        }
        TradeSide payerSide = initiatorAmount > targetAmount ? TradeSide.INITIATOR : TradeSide.TARGET;
        TradeSide receiverSide = payerSide.opposite();
        long net = Math.abs(initiatorAmount - targetAmount);
        UUID operationId = deterministicUuid(trade.tradeId(), "trade-currency-net");
        CurrencyContext context = CurrencyService.context(
                trade.uuid(payerSide),
                trade.name(payerSide),
                "Межсерверный обмен игроков",
                "PLAYER_TRADE",
                trade.tradeId().toString(),
                Map.of(
                        "trade_id", trade.tradeId().toString(),
                        "payer", trade.uuid(payerSide).toString(),
                        "receiver", trade.uuid(receiverSide).toString(),
                        "initiator_offer", Long.toString(initiatorAmount),
                        "target_offer", Long.toString(targetAmount),
                        "net", Long.toString(net)
                )
        );
        CurrencyOperationResult result = CurrencyService.transfer(
                trade.uuid(payerSide), trade.uuid(receiverSide), net, operationId, context
        );
        if (!result.success()) {
            database.markCancelled(trade.tradeId(), "Ошибка валюты: " + result.message());
            audit(trade.tradeId(), "CURRENCY_FAILED", null, result.code() + ": " + result.message(), true);
            return;
        }
        database.markCommitting(trade.tradeId());
        audit(trade.tradeId(), "CURRENCY_SETTLED", null, "net=" + net + ", operation=" + operationId,
                net >= TradeConfig.suspiciousCurrencyThreshold() && TradeConfig.suspiciousCurrencyThreshold() > 0L);
    }

    private static void deliver(ServerPlayer player, TradeRecord trade, TradeSide recipientSide) throws Exception {
        List<ItemStack> incoming = trade.offer(recipientSide.opposite());
        String phase = recoveryPhase(player, trade.tradeId());
        if (PHASE_DELIVERED.equals(phase)) {
            if (database.markDelivered(trade.tradeId(), recipientSide)) {
                audit(trade.tradeId(), "DELIVERED_RECOVERED", player,
                        "items=" + TradeItemCodec.encode(incoming), containsSuspicious(incoming));
            }
            clearRecoveryMarker(player, trade.tradeId());
            return;
        }
        if (!canFit(player.getInventory(), incoming)) {
            String key = trade.tradeId() + ":full:" + player.getUUID();
            if (NOTIFICATIONS.add(key)) {
                player.sendSystemMessage(Component.literal("§eОсвободи место в инвентаре для завершения обмена."));
            }
            return;
        }
        if (!PHASE_DELIVERY_STARTED.equals(phase)) {
            setRecoveryMarker(player, trade.tradeId(), PHASE_DELIVERY_STARTED);
        }
        giveItems(player, incoming);
        player.getInventory().setChanged();
        setRecoveryMarker(player, trade.tradeId(), PHASE_DELIVERED);
        if (database.markDelivered(trade.tradeId(), recipientSide)) {
            audit(trade.tradeId(), "DELIVERED", player, "items=" + TradeItemCodec.encode(incoming),
                    containsSuspicious(incoming));
            player.sendSystemMessage(Component.literal("§aПредметы по сделке получены."));
        }
        clearRecoveryMarker(player, trade.tradeId());
    }

    private static void rollback(ServerPlayer player, TradeRecord trade, TradeSide side) throws Exception {
        if (!trade.prepared(side) || trade.returned(side)) {
            return;
        }
        List<ItemStack> own = trade.offer(side);
        String phase = recoveryPhase(player, trade.tradeId());
        if (PHASE_RETURNED.equals(phase)) {
            if (database.markReturned(trade.tradeId(), side)) {
                audit(trade.tradeId(), "RETURNED_RECOVERED", player,
                        "items=" + TradeItemCodec.encode(own), false);
            }
            clearRecoveryMarker(player, trade.tradeId());
            return;
        }
        if (!canFit(player.getInventory(), own)) {
            String key = trade.tradeId() + ":rollback-full:" + player.getUUID();
            if (NOTIFICATIONS.add(key)) {
                player.sendSystemMessage(Component.literal("§eОсвободи место для возврата предметов отменённой сделки."));
            }
            return;
        }
        if (!PHASE_RETURN_STARTED.equals(phase)) {
            setRecoveryMarker(player, trade.tradeId(), PHASE_RETURN_STARTED);
        }
        giveItems(player, own);
        player.getInventory().setChanged();
        setRecoveryMarker(player, trade.tradeId(), PHASE_RETURNED);
        if (database.markReturned(trade.tradeId(), side)) {
            audit(trade.tradeId(), "RETURNED", player, "items=" + TradeItemCodec.encode(own), false);
            player.sendSystemMessage(Component.literal("§eПредметы отменённой сделки возвращены."));
        }
        clearRecoveryMarker(player, trade.tradeId());
    }

    private static String validateTrade(TradeRecord trade) throws SQLException {
        if (trade.initiatorUuid().equals(trade.targetUuid())) {
            return "Нельзя обмениваться с самим собой";
        }
        if (!hasAnyItem(trade.initiatorOffer()) && !hasAnyItem(trade.targetOffer())
                && trade.initiatorCurrency() == 0L && trade.targetCurrency() == 0L) {
            return "Нельзя подтвердить пустую сделку";
        }
        TradeDatabase.PlayerPresence initiator = database.findPlayer(trade.initiatorUuid()).orElse(null);
        TradeDatabase.PlayerPresence target = database.findPlayer(trade.targetUuid()).orElse(null);
        if (initiator == null || target == null) {
            return "Не удалось определить прогресс игроков";
        }
        String initiatorReceiveError = progressionError(trade.targetOffer(), initiator.tierIndex(), initiator.name());
        if (!initiatorReceiveError.isEmpty()) {
            return initiatorReceiveError;
        }
        String targetReceiveError = progressionError(trade.initiatorOffer(), target.tierIndex(), target.name());
        if (!targetReceiveError.isEmpty()) {
            return targetReceiveError;
        }
        if (TradeConfig.moderationMode() == TradeConfig.ModerationMode.BLOCK
                && (containsSuspicious(trade.initiatorOffer()) || containsSuspicious(trade.targetOffer()))) {
            return "Сделка содержит предметы, запрещённые правилами модерации";
        }
        return "";
    }

    private static String progressionError(List<ItemStack> items, int receiverTier, String receiverName) {
        if (!CurrencyConfig.exchangerProgressionEnabled()) {
            return "";
        }
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String required = ExchangerProgression.automaticRequiredTier(stack);
            if (required.isEmpty()) {
                continue;
            }
            int requiredIndex = CurrencyConfig.exchangerTierIndex(required);
            if (requiredIndex < 0) {
                return "Для предмета " + stack.getHoverName().getString() + " указана неизвестная эпоха " + required;
            }
            if (receiverTier < requiredIndex) {
                return "Игрок " + receiverName + " не достиг эпохи " + required.toUpperCase()
                        + " для предмета " + stack.getHoverName().getString();
            }
        }
        return "";
    }

    private static long netPaymentFor(TradeRecord trade, TradeSide side) {
        long own = trade.currency(side);
        long other = trade.currency(side.opposite());
        return Math.max(0L, own - other);
    }

    private static void notifyAndOpen(ServerPlayer player, TradeRecord trade) {
        TradeSide side = trade.sideOf(player.getUUID());
        if (side == null) {
            return;
        }
        if (trade.status() == TradeStatus.INVITED && side == TradeSide.TARGET) {
            String key = trade.tradeId() + ":invite:" + player.getUUID();
            if (NOTIFICATIONS.add(key)) {
                player.sendSystemMessage(Component.literal(
                        "§b" + trade.initiatorName() + " предлагает обмен. §f/trade accept "
                                + trade.initiatorName() + " §7или §f/trade deny " + trade.initiatorName()
                ));
            }
            return;
        }
        if (trade.status() == TradeStatus.OPEN) {
            String key = trade.tradeId() + ":open:" + player.getUUID();
            if (NOTIFICATIONS.add(key)) {
                player.sendSystemMessage(Component.literal("§aОбмен с " + trade.name(side.opposite()) + " открыт."));
            }
            if (!trade.tradeId().equals(OPENED_ONCE.get(player.getUUID()))) {
                open(player, trade);
            }
        }
        if (trade.status() == TradeStatus.COMPLETED) {
            notifyTerminal(player, trade, "§aОбмен успешно завершён.");
        } else if (trade.status() == TradeStatus.CANCELLED || trade.status() == TradeStatus.EXPIRED) {
            notifyTerminal(player, trade, "§cОбмен отменён: " + trade.errorText());
        }
    }

    private static void notifyTerminal(ServerPlayer player, TradeRecord trade, String message) {
        String key = trade.tradeId() + ":terminal:" + player.getUUID() + ":" + trade.status();
        if (NOTIFICATIONS.add(key)) {
            player.sendSystemMessage(Component.literal(message));
            if (player.containerMenu instanceof TradeMenu menu && menu.tradeId().equals(trade.tradeId())) {
                player.closeContainer();
            }
        }
    }

    private static void open(ServerPlayer player, TradeRecord trade) {
        TradeSide side = trade.sideOf(player.getUUID());
        if (side == null || trade.status() == TradeStatus.INVITED) {
            return;
        }
        OPENED_ONCE.put(player.getUUID(), trade.tradeId());
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (windowId, inventory, ignored) -> new TradeMenu(windowId, inventory, trade.tradeId(), side),
                        Component.literal("Обмен с " + trade.name(side.opposite()))
                ),
                buffer -> {
                    buffer.writeUUID(trade.tradeId());
                    buffer.writeBoolean(side == TradeSide.INITIATOR);
                }
        );
    }

    private static void heartbeat(ServerPlayer player, boolean online) {
        if (!available() || player == null) {
            return;
        }
        try {
            database.heartbeat(player.getUUID(), player.getGameProfile().getName(), nodeId,
                    ExchangerProgression.playerTier(player), online);
        } catch (Exception exception) {
            lastError = message(exception);
        }
    }

    private static void refresh(UUID tradeId) {
        try {
            database.find(tradeId).ifPresent(record -> CACHE.put(tradeId, record));
        } catch (Exception ignored) {
        }
    }

    private static List<ItemStack> mutableOffer(List<ItemStack> current) {
        ArrayList<ItemStack> result = new ArrayList<>(OFFER_SLOTS);
        for (int index = 0; index < OFFER_SLOTS; index++) {
            result.add(index < current.size() ? current.get(index).copy() : ItemStack.EMPTY);
        }
        return result;
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = stack.copy();
        result.setCount(Math.max(1, Math.min(result.getCount(), result.getMaxStackSize())));
        return result;
    }

    private static boolean hasItems(Inventory inventory, List<ItemStack> requested) {
        Map<StackKey, Integer> available = inventoryCounts(inventory);
        for (ItemStack stack : requested) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            StackKey key = new StackKey(stack);
            int count = available.getOrDefault(key, 0);
            if (count < stack.getCount()) {
                return false;
            }
            available.put(key, count - stack.getCount());
        }
        return true;
    }

    private static boolean removeExact(Inventory inventory, List<ItemStack> requested) {
        if (!hasItems(inventory, requested)) {
            return false;
        }
        for (ItemStack requestedStack : requested) {
            if (requestedStack == null || requestedStack.isEmpty()) {
                continue;
            }
            int remaining = requestedStack.getCount();
            for (int slot = 0; slot < inventory.items.size() && remaining > 0; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (!ItemStack.isSameItemSameTags(current, requestedStack)) {
                    continue;
                }
                int remove = Math.min(remaining, current.getCount());
                current.shrink(remove);
                remaining -= remove;
                if (current.isEmpty()) {
                    inventory.setItem(slot, ItemStack.EMPTY);
                }
            }
        }
        inventory.setChanged();
        return true;
    }

    private static Map<StackKey, Integer> inventoryCounts(Inventory inventory) {
        Map<StackKey, Integer> result = new HashMap<>();
        for (int slot = 0; slot < inventory.items.size(); slot++) {
            ItemStack stack = inventory.items.get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            result.merge(new StackKey(stack), stack.getCount(), Integer::sum);
        }
        return result;
    }

    private static boolean canFit(Inventory inventory, List<ItemStack> incoming) {
        ArrayList<ItemStack> simulated = new ArrayList<>(inventory.items.size());
        for (ItemStack stack : inventory.items) {
            simulated.add(stack.copy());
        }
        for (ItemStack original : incoming) {
            if (original == null || original.isEmpty()) {
                continue;
            }
            ItemStack remaining = original.copy();
            for (ItemStack current : simulated) {
                if (remaining.isEmpty()) {
                    break;
                }
                if (!current.isEmpty() && ItemStack.isSameItemSameTags(current, remaining)) {
                    int space = Math.min(current.getMaxStackSize(), 64) - current.getCount();
                    int moved = Math.min(space, remaining.getCount());
                    current.grow(moved);
                    remaining.shrink(moved);
                }
            }
            for (int slot = 0; slot < simulated.size() && !remaining.isEmpty(); slot++) {
                if (!simulated.get(slot).isEmpty()) {
                    continue;
                }
                int moved = Math.min(Math.min(remaining.getMaxStackSize(), 64), remaining.getCount());
                ItemStack placed = remaining.copy();
                placed.setCount(moved);
                simulated.set(slot, placed);
                remaining.shrink(moved);
            }
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void giveItems(ServerPlayer player, List<ItemStack> items) {
        for (ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ItemStack remaining = stack.copy();
            player.getInventory().add(remaining);
            if (!remaining.isEmpty()) {
                player.drop(remaining, false);
            }
        }
    }

    private static boolean hasAnyItem(List<ItemStack> items) {
        if (items == null) {
            return false;
        }
        for (ItemStack stack : items) {
            if (stack != null && !stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSuspicious(List<ItemStack> items) {
        if (TradeConfig.moderationMode() == TradeConfig.ModerationMode.OFF) {
            return false;
        }
        for (ItemStack stack : items) {
            if (TradeConfig.matchesModerated(stack)) {
                return true;
            }
        }
        return false;
    }

    private static void audit(UUID tradeId, String type, ServerPlayer actor, String details, boolean suspicious) {
        if (!TradeConfig.auditEnabled() || database == null) {
            return;
        }
        UUID actorUuid = actor == null ? null : actor.getUUID();
        String actorName = actor == null ? "" : actor.getGameProfile().getName();
        try {
            database.audit(tradeId, type, nodeId, actorUuid, actorName, details, suspicious);
        } catch (Exception exception) {
            LOGGER.error("Unable to write trade audit", exception);
        }
        String line = "trade=" + tradeId + ", event=" + type + ", actor=" + actorName + ", details=" + details;
        if (TradeConfig.logToConsole()) {
            LOGGER.info(line);
        }
        if (suspicious && TradeConfig.sendSuspiciousToDiscord()) {
            CointCoreGTODiscordProxy.sendToDiscordLog("[TRADE ALERT] " + line);
        }
    }

    private static String recoveryPhase(ServerPlayer player, UUID tradeId) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(RECOVERY_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return "";
        }
        CompoundTag recovery = persistent.getCompound(RECOVERY_TAG);
        if (!tradeId.toString().equals(recovery.getString("TradeId"))) {
            return "";
        }
        return recovery.getString("Phase");
    }

    private static boolean isRecoveryMarker(ServerPlayer player, UUID tradeId, String phase) {
        return phase.equals(recoveryPhase(player, tradeId));
    }

    private static void setRecoveryMarker(ServerPlayer player, UUID tradeId, String phase) {
        CompoundTag recovery = new CompoundTag();
        recovery.putString("TradeId", tradeId.toString());
        recovery.putString("Phase", phase);
        player.getPersistentData().put(RECOVERY_TAG, recovery);
        forceSave(player);
    }

    private static void clearRecoveryMarker(ServerPlayer player, UUID tradeId) {
        CompoundTag persistent = player.getPersistentData();
        if (!persistent.contains(RECOVERY_TAG, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag recovery = persistent.getCompound(RECOVERY_TAG);
        if (!tradeId.toString().equals(recovery.getString("TradeId"))) {
            return;
        }
        persistent.remove(RECOVERY_TAG);
        forceSave(player);
    }

    private static void forceSave(ServerPlayer player) {
        try {
            player.server.getPlayerList().saveAll();
        } catch (Throwable exception) {
            throw new IllegalStateException(
                    "Unable to force-save player trade recovery state for "
                            + player.getGameProfile().getName(),
                    exception
            );
        }
    }

    private static UUID deterministicUuid(UUID tradeId, String suffix) {
        return UUID.nameUUIDFromBytes((tradeId + ":" + suffix).getBytes(StandardCharsets.UTF_8));
    }

    private static String message(Throwable throwable) {
        String text = throwable.getMessage();
        return text == null || text.isBlank() ? throwable.getClass().getSimpleName() : text;
    }

    private record StackKey(net.minecraft.world.item.Item item, net.minecraft.nbt.CompoundTag tag) {
        StackKey(ItemStack stack) {
            this(stack.getItem(), stack.getTag() == null ? null : stack.getTag().copy());
        }
    }

    public record OperationResult(boolean success, String message, UUID tradeId) {
        static OperationResult success(String message, UUID tradeId) {
            return new OperationResult(true, message, tradeId);
        }

        static OperationResult failure(String message) {
            return new OperationResult(false, message, null);
        }
    }
}
