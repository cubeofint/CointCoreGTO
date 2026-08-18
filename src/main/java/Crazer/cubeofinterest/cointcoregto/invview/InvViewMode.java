package Crazer.cubeofinterest.cointcoregto.invview;

public enum InvViewMode {
    MAIN,
    ENDER,
    CURIOS;

    public static InvViewMode byId(int id) {
        InvViewMode[] values = values();
        return id >= 0 && id < values.length ? values[id] : MAIN;
    }
}
