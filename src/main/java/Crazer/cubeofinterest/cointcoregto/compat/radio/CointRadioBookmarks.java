package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CointRadioBookmarks {

    private static final List<Bookmark> BOOKMARKS = new ArrayList<>();
    private static boolean loaded = false;

    private CointRadioBookmarks() {
    }

    public static List<Bookmark> getBookmarks() {
        load();
        return new ArrayList<>(BOOKMARKS);
    }

    public static void add(String url) {
        load();

        String cleanUrl = url == null ? "" : url.trim();
        if (cleanUrl.isBlank()) {
            return;
        }

        for (Bookmark bookmark : BOOKMARKS) {
            if (bookmark.url().equalsIgnoreCase(cleanUrl)) {
                return;
            }
        }

        String name = createName(cleanUrl);
        BOOKMARKS.add(new Bookmark(name, cleanUrl));
        save();
    }

    public static void remove(String url) {
        load();

        String cleanUrl = url == null ? "" : url.trim();
        if (cleanUrl.isBlank()) {
            return;
        }

        BOOKMARKS.removeIf(bookmark -> bookmark.url().equalsIgnoreCase(cleanUrl));
        save();
    }

    private static void load() {
        if (loaded) {
            return;
        }

        loaded = true;
        BOOKMARKS.clear();

        Path path = getPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

            for (String line : lines) {
                String cleanLine = line == null ? "" : line.trim();
                if (cleanLine.isBlank()) {
                    continue;
                }

                int separator = cleanLine.indexOf('\u001F');
                if (separator > 0 && separator < cleanLine.length() - 1) {
                    String name = cleanLine.substring(0, separator).trim();
                    String url = cleanLine.substring(separator + 1).trim();

                    if (!url.isBlank()) {
                        BOOKMARKS.add(new Bookmark(name.isBlank() ? createName(url) : name, url));
                    }
                } else {
                    BOOKMARKS.add(new Bookmark(createName(cleanLine), cleanLine));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void save() {
        Path path = getPath();

        try {
            Files.createDirectories(path.getParent());

            List<String> lines = new ArrayList<>();
            for (Bookmark bookmark : BOOKMARKS) {
                lines.add(bookmark.name() + "\u001F" + bookmark.url());
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static Path getPath() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameDirectory.toPath()
                .resolve("config")
                .resolve("cointcoregto_radio_bookmarks.txt");
    }

    private static String createName(String url) {
        String clean = url;

        clean = clean.replace("https://", "");
        clean = clean.replace("http://", "");

        int slash = clean.indexOf('/');
        if (slash > 0) {
            clean = clean.substring(0, slash);
        }

        if (clean.length() > 28) {
            clean = clean.substring(0, 28) + "...";
        }

        return clean;
    }

    public record Bookmark(String name, String url) {
    }
}