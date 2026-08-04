package Crazer.cubeofinterest.cointcoregto.currency;

import java.util.Map;
import java.util.UUID;

public record CurrencyContext(
        UUID actorUuid,
        String actorName,
        String nodeId,
        String reason,
        String sourceType,
        String sourceId,
        Map<String, String> metadata
) {
    public CurrencyContext {
        actorName = actorName == null ? "" : actorName;
        nodeId = nodeId == null ? "" : nodeId;
        reason = reason == null ? "" : reason;
        sourceType = sourceType == null ? "" : sourceType;
        sourceId = sourceId == null ? "" : sourceId;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static CurrencyContext system(String nodeId, String reason, String sourceType, String sourceId) {
        return new CurrencyContext(null, "system", nodeId, reason, sourceType, sourceId, Map.of());
    }
}
