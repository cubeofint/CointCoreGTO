package Crazer.cubeofinterest.cointcoregto.trade;

public enum TradeStatus {
    INVITED,
    OPEN,
    PREPARING,
    SETTLING,
    COMMITTING,
    COMPLETED,
    CANCELLED,
    DENIED,
    EXPIRED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == DENIED || this == EXPIRED;
    }

    public boolean active() {
        return !terminal();
    }
}
