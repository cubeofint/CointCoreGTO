package Crazer.cubeofinterest.cointcoregto.compat.emi;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CointManaOverlayClientSettings {

    private static final Path PATH = FMLPaths.CONFIGDIR.get()
            .resolve("cointcoregto_mana_overlay_client.properties");

    private static boolean loaded = false;

    private static int emiRunicAltarXOffset = 0;
    private static int emiRunicAltarYOffset = 0;

    private static int emiTerraPlateXOffset = 0;
    private static int emiTerraPlateYOffset = 0;

    private static int emiManaInfuserXOffset = 0;
    private static int emiManaInfuserYOffset = 0;

    private static int emiRuneRitualXOffset = 0;
    private static int emiRuneRitualYOffset = 0;

    private static int emiPoolXOffset = 0;
    private static int emiPoolYOffset = 0;

    private static int emiPoolSecondXOffset = 0;
    private static int emiPoolSecondYOffset = 0;

    private static int worldManaXOffset = 0;
    private static int worldManaYOffset = 0;

    private CointManaOverlayClientSettings() {
    }

    public static int getEmiRunicAltarXOffset() {
        load();
        return emiRunicAltarXOffset;
    }

    public static int getEmiRunicAltarYOffset() {
        load();
        return emiRunicAltarYOffset;
    }

    public static int getEmiTerraPlateXOffset() {
        load();
        return emiTerraPlateXOffset;
    }

    public static int getEmiTerraPlateYOffset() {
        load();
        return emiTerraPlateYOffset;
    }

    public static int getEmiManaInfuserXOffset() {
        load();
        return emiManaInfuserXOffset;
    }

    public static int getEmiManaInfuserYOffset() {
        load();
        return emiManaInfuserYOffset;
    }

    public static int getEmiRuneRitualXOffset() {
        load();
        return emiRuneRitualXOffset;
    }

    public static int getEmiRuneRitualYOffset() {
        load();
        return emiRuneRitualYOffset;
    }

    public static int getEmiPoolXOffset() {
        load();
        return emiPoolXOffset;
    }

    public static int getEmiPoolYOffset() {
        load();
        return emiPoolYOffset;
    }

    public static int getEmiPoolSecondXOffset() {
        load();
        return emiPoolSecondXOffset;
    }

    public static int getEmiPoolSecondYOffset() {
        load();
        return emiPoolSecondYOffset;
    }

    public static int getWorldManaXOffset() {
        load();
        return worldManaXOffset;
    }

    public static int getWorldManaYOffset() {
        load();
        return worldManaYOffset;
    }

    public static void setEmiRunicAltarOffset(int x, int y) {
        load();
        emiRunicAltarXOffset = clamp(x);
        emiRunicAltarYOffset = clamp(y);
    }

    public static void setEmiTerraPlateOffset(int x, int y) {
        load();
        emiTerraPlateXOffset = clamp(x);
        emiTerraPlateYOffset = clamp(y);
    }

    public static void setEmiManaInfuserOffset(int x, int y) {
        load();
        emiManaInfuserXOffset = clamp(x);
        emiManaInfuserYOffset = clamp(y);
    }

    public static void setEmiRuneRitualOffset(int x, int y) {
        load();
        emiRuneRitualXOffset = clamp(x);
        emiRuneRitualYOffset = clamp(y);
    }

    public static void setEmiPoolOffset(int x, int y) {
        load();
        emiPoolXOffset = clamp(x);
        emiPoolYOffset = clamp(y);
    }

    public static void setEmiPoolSecondOffset(int x, int y) {
        load();
        emiPoolSecondXOffset = clamp(x);
        emiPoolSecondYOffset = clamp(y);
    }

    public static void setWorldManaOffset(int x, int y) {
        load();
        worldManaXOffset = clamp(x);
        worldManaYOffset = clamp(y);
    }

    public static void reset() {
        emiRunicAltarXOffset = 0;
        emiRunicAltarYOffset = 0;

        emiTerraPlateXOffset = 0;
        emiTerraPlateYOffset = 0;

        emiManaInfuserXOffset = 0;
        emiManaInfuserYOffset = 0;

        emiRuneRitualXOffset = 0;
        emiRuneRitualYOffset = 0;

        emiPoolXOffset = 0;
        emiPoolYOffset = 0;

        emiPoolSecondXOffset = 0;
        emiPoolSecondYOffset = 0;

        worldManaXOffset = 0;
        worldManaYOffset = 0;

        loaded = true;
        save();
    }

    public static void load() {
        if (loaded) {
            return;
        }

        loaded = true;

        if (!Files.exists(PATH)) {
            save();
            return;
        }

        Properties properties = new Properties();

        try (InputStream inputStream = Files.newInputStream(PATH)) {
            properties.load(inputStream);

            emiRunicAltarXOffset = readInt(properties, "emiRunicAltarXOffset", emiRunicAltarXOffset);
            emiRunicAltarYOffset = readInt(properties, "emiRunicAltarYOffset", emiRunicAltarYOffset);

            emiTerraPlateXOffset = readInt(properties, "emiTerraPlateXOffset", emiTerraPlateXOffset);
            emiTerraPlateYOffset = readInt(properties, "emiTerraPlateYOffset", emiTerraPlateYOffset);

            emiManaInfuserXOffset = readInt(properties, "emiManaInfuserXOffset", emiManaInfuserXOffset);
            emiManaInfuserYOffset = readInt(properties, "emiManaInfuserYOffset", emiManaInfuserYOffset);

            emiRuneRitualXOffset = readInt(properties, "emiRuneRitualXOffset", emiRuneRitualXOffset);
            emiRuneRitualYOffset = readInt(properties, "emiRuneRitualYOffset", emiRuneRitualYOffset);

            emiPoolXOffset = readInt(properties, "emiPoolXOffset", emiPoolXOffset);
            emiPoolYOffset = readInt(properties, "emiPoolYOffset", emiPoolYOffset);

            emiPoolSecondXOffset = readInt(properties, "emiPoolSecondXOffset", emiPoolSecondXOffset);
            emiPoolSecondYOffset = readInt(properties, "emiPoolSecondYOffset", emiPoolSecondYOffset);

            worldManaXOffset = readInt(properties, "worldManaXOffset", worldManaXOffset);
            worldManaYOffset = readInt(properties, "worldManaYOffset", worldManaYOffset);
        } catch (Throwable e) {
            System.out.println("[CointManaOverlay] Failed to load client overlay settings: " + e.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());

            Properties properties = new Properties();

            properties.setProperty("emiRunicAltarXOffset", String.valueOf(emiRunicAltarXOffset));
            properties.setProperty("emiRunicAltarYOffset", String.valueOf(emiRunicAltarYOffset));

            properties.setProperty("emiTerraPlateXOffset", String.valueOf(emiTerraPlateXOffset));
            properties.setProperty("emiTerraPlateYOffset", String.valueOf(emiTerraPlateYOffset));

            properties.setProperty("emiManaInfuserXOffset", String.valueOf(emiManaInfuserXOffset));
            properties.setProperty("emiManaInfuserYOffset", String.valueOf(emiManaInfuserYOffset));

            properties.setProperty("emiRuneRitualXOffset", String.valueOf(emiRuneRitualXOffset));
            properties.setProperty("emiRuneRitualYOffset", String.valueOf(emiRuneRitualYOffset));

            properties.setProperty("emiPoolXOffset", String.valueOf(emiPoolXOffset));
            properties.setProperty("emiPoolYOffset", String.valueOf(emiPoolYOffset));

            properties.setProperty("emiPoolSecondXOffset", String.valueOf(emiPoolSecondXOffset));
            properties.setProperty("emiPoolSecondYOffset", String.valueOf(emiPoolSecondYOffset));

            properties.setProperty("worldManaXOffset", String.valueOf(worldManaXOffset));
            properties.setProperty("worldManaYOffset", String.valueOf(worldManaYOffset));

            try (OutputStream outputStream = Files.newOutputStream(PATH)) {
                properties.store(outputStream, "CointCoreGTO mana overlay client positions");
            }
        } catch (IOException e) {
            System.out.println("[CointManaOverlay] Failed to save client overlay settings: " + e.getMessage());
        }
    }

    private static int readInt(Properties properties, String key, int fallback) {
        try {
            return clamp(Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)).trim()));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int clamp(int value) {
        return Math.max(-2000, Math.min(2000, value));
    }
}