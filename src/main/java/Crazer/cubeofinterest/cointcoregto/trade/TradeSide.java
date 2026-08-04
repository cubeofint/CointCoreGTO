package Crazer.cubeofinterest.cointcoregto.trade;

public enum TradeSide {
    INITIATOR,
    TARGET;

    public TradeSide opposite() {
        return this == INITIATOR ? TARGET : INITIATOR;
    }
}
