package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CointRadioConfig {
    private CointRadioConfig() {
    }

    public record RadioStation(String id, String name, String url) {
    }

    public static boolean isEnabled() {
        try {
            return CointCoreGTO.RADIO_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static int getRadius() {
        try {
            return CointCoreGTO.RADIO_RADIUS.get();
        } catch (Throwable ignored) {
            return 24;
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

        Map<String, RadioStation> stations = getStationData();

        if (!stations.isEmpty()) {
            return stations.values().iterator().next().url();
        }

        return fallbackUrl();
    }

    public static String getStationUrl(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return null;
        }

        RadioStation station = getStationData().get(normalizeStation(stationId));

        if (station == null) {
            return null;
        }

        return station.url();
    }

    public static String getStationName(String stationId) {
        if (stationId == null || stationId.isBlank()) {
            return "";
        }

        String normalized = normalizeStation(stationId);
        RadioStation station = getStationData().get(normalized);

        if (station == null) {
            return normalized;
        }

        return station.name();
    }

    public static Map<String, String> getStations() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();

        for (RadioStation station : getStationData().values()) {
            result.put(station.id(), station.url());
        }

        return result;
    }

    public static Map<String, RadioStation> getStationData() {
        LinkedHashMap<String, RadioStation> result = new LinkedHashMap<>();

        try {
            List<? extends String> entries = CointCoreGTO.RADIO_STATIONS.get();

            if (entries == null) {
                return result;
            }

            for (String entry : entries) {
                RadioStation station = parseStation(entry);

                if (station == null) {
                    continue;
                }

                result.put(station.id(), station);
            }
        } catch (Throwable ignored) {
        }

        return result;
    }

    public static List<String> getStationIds() {
        return new ArrayList<>(getStationData().keySet());
    }

    public static List<String> getStationNames() {
        List<String> result = new ArrayList<>();

        for (RadioStation station : getStationData().values()) {
            result.add(station.name());
        }

        return result;
    }

    public static List<String> getStationScreenEntries() {
        List<String> result = new ArrayList<>();

        for (RadioStation station : getStationData().values()) {
            result.add(station.id() + "\u001F" + station.name());
        }

        return result;
    }

    public static String getOnMessage(String stationId) {
        String displayName = getStationName(stationId);

        if (displayName == null || displayName.isBlank()) {
            displayName = stationId == null ? "" : stationId;
        }

        try {
            String message = CointCoreGTO.RADIO_ON_MESSAGE.get();

            if (message == null || message.isBlank()) {
                return "§a[CointMusic] Радио включено: §f" + displayName;
            }

            return color(message)
                    .replace("%station%", displayName)
                    .replace("%station_id%", stationId == null ? "" : stationId);
        } catch (Throwable ignored) {
            return "§a[CointMusic] Радио включено: §f" + displayName;
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

    private static RadioStation parseStation(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }

        String trimmed = entry.trim();

        RadioStation newFormat = parseNewFormat(trimmed);

        if (newFormat != null) {
            return newFormat;
        }

        return parseOldFormat(trimmed);
    }

    private static RadioStation parseNewFormat(String entry) {
        String[] parts = entry.split("\\|", 3);

        if (parts.length != 3) {
            return null;
        }

        String id = normalizeStation(parts[0]);
        String name = parts[1].trim();
        String url = parts[2].trim();

        if (id.isBlank() || name.isBlank() || url.isBlank()) {
            return null;
        }

        return new RadioStation(id, name, url);
    }

    private static RadioStation parseOldFormat(String entry) {
        int separator = entry.indexOf('=');

        if (separator <= 0 || separator >= entry.length() - 1) {
            return null;
        }

        String id = normalizeStation(entry.substring(0, separator));
        String url = entry.substring(separator + 1).trim();

        if (id.isBlank() || url.isBlank()) {
            return null;
        }

        return new RadioStation(id, id, url);
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