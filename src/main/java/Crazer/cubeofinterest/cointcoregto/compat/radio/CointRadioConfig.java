package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CointRadioConfig {
    private CointRadioConfig() {
    }

    public static boolean isEnabled() {
        try {
            return CointCoreGTO.RADIO_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String getDefaultStation() {
        try {
            String station = CointCoreGTO.RADIO_DEFAULT_STATION.get();

            if (station == null || station.isBlank()) {
                return "main";
            }

            return normalizeStation(station);
        } catch (Throwable ignored) {
            return "main";
        }
    }

    public static String getDefaultUrl() {
        String defaultStation = getDefaultStation();
        String url = getStationUrl(defaultStation);

        if (url != null && !url.isBlank()) {
            return url;
        }

        Map<String, String> stations = getStations();

        if (!stations.isEmpty()) {
            return stations.values().iterator().next();
        }

        return fallbackUrl();
    }

    public static String getStationUrl(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }

        return getStations().get(normalizeStation(stationId));
    }

    public static Map<String, String> getStations() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();

        try {
            List<? extends String> entries = CointCoreGTO.RADIO_STATIONS.get();

            if (entries == null) {
                return result;
            }

            for (String entry : entries) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }

                int separator = entry.indexOf('=');

                if (separator <= 0 || separator >= entry.length() - 1) {
                    continue;
                }

                String stationId = normalizeStation(entry.substring(0, separator));
                String url = entry.substring(separator + 1).trim();

                if (stationId.isBlank() || url.isBlank()) {
                    continue;
                }

                result.put(stationId, url);
            }
        } catch (Throwable ignored) {
        }

        return result;
    }

    public static java.util.List<String> getStationIds() {
        return new java.util.ArrayList<>(getStations().keySet());
    }

    public static String getOnMessage(String stationId) {
        try {
            String message = CointCoreGTO.RADIO_ON_MESSAGE.get();

            if (message == null || message.isBlank()) {
                return "§a[CointMusic] Радио включено: §f" + stationId;
            }

            return color(message).replace("%station%", stationId == null ? "" : stationId);
        } catch (Throwable ignored) {
            return "§a[CointMusic] Радио включено: §f" + stationId;
        }
    }

    public static String getOffMessage() {
        try {
            String message = CointCoreGTO.RADIO_OFF_MESSAGE.get();

            if (message == null || message.isBlank()) {
                return "§c[CointMusic] Радио выключено.";
            }

            return color(message);
        } catch (Throwable ignored) {
            return "§c[CointMusic] Радио выключено.";
        }
    }

    private static String normalizeStation(String station) {
        return station.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String fallbackUrl() {
        return "https://upload.wikimedia.org/wikipedia/commons/c/c8/Example.ogg";
    }

    private static String color(String text) {
        return text.replace("&", "§");
    }
}