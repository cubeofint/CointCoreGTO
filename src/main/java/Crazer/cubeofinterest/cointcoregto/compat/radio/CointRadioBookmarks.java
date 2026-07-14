package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public final class CointRadioBookmarks {
    private static final char SEPARATOR = '\u001F';
    private static final int MAX_BOOKMARKS = 64;

    private static final ArrayList<Bookmark> BOOKMARKS = new ArrayList<>();
    private static boolean loaded = false;

    private CointRadioBookmarks() {
    }

    public static List<Bookmark> getBookmarks() {
        load();
        return List.copyOf(BOOKMARKS);
    }

    public static void add(String url) {
        add(createName(url), url);
    }

    public static void add(String name, String url) {
        load();

        String cleanUrl = cleanUrl(url);
        if (cleanUrl.isBlank()) {
            return;
        }

        String cleanName = cleanName(name);
        if (cleanName.isBlank()) {
            cleanName = createName(cleanUrl);
        }

        removeInternal(cleanUrl);

        BOOKMARKS.add(0, new Bookmark(cleanName, cleanUrl));

        while (BOOKMARKS.size() > MAX_BOOKMARKS) {
            BOOKMARKS.remove(BOOKMARKS.size() - 1);
        }

        save();
    }

    public static void rename(String url, String newName) {
        load();

        String cleanUrl = cleanUrl(url);
        String cleanName = cleanName(newName);

        if (cleanUrl.isBlank() || cleanName.isBlank()) {
            return;
        }

        for (int i = 0; i < BOOKMARKS.size(); i++) {
            Bookmark bookmark = BOOKMARKS.get(i);

            if (bookmark.url().equalsIgnoreCase(cleanUrl)) {
                BOOKMARKS.set(i, new Bookmark(cleanName, bookmark.url()));
                save();
                return;
            }
        }
    }

    public static void remove(String url) {
        load();

        if (removeInternal(cleanUrl(url))) {
            save();
        }
    }

    public static String createName(String url) {
        String cleanUrl = cleanUrl(url);

        if (cleanUrl.isBlank()) {
            return "Радио";
        }

        try {
            URI uri = URI.create(cleanUrl);
            String host = uri.getHost();

            if (host != null && !host.isBlank()) {
                host = host.toLowerCase(Locale.ROOT);

                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }

                return cleanName(host);
            }
        } catch (Throwable ignored) {
        }

        String result = cleanUrl
                .replace("https://", "")
                .replace("http://", "");

        int slashIndex = result.indexOf('/');
        if (slashIndex >= 0) {
            result = result.substring(0, slashIndex);
        }

        result = cleanName(result);

        return result.isBlank() ? "Радио" : result;
    }

    private static boolean removeInternal(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        return BOOKMARKS.removeIf(bookmark -> bookmark.url().equalsIgnoreCase(url));
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
            LinkedHashMap<String, Bookmark> unique = new LinkedHashMap<>();

            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String name;
                String url;

                int separatorIndex = line.indexOf(SEPARATOR);

                if (separatorIndex >= 0) {
                    name = line.substring(0, separatorIndex);
                    url = line.substring(separatorIndex + 1);
                } else {
                    url = line;
                    name = createName(url);
                }

                url = cleanUrl(url);
                name = cleanName(name);

                if (url.isBlank()) {
                    continue;
                }

                if (name.isBlank()) {
                    name = createName(url);
                }

                unique.put(url.toLowerCase(Locale.ROOT), new Bookmark(name, url));
            }

            BOOKMARKS.addAll(unique.values());

            while (BOOKMARKS.size() > MAX_BOOKMARKS) {
                BOOKMARKS.remove(BOOKMARKS.size() - 1);
            }
        } catch (Throwable e) {
            System.out.println("[CointRadioBookmarks] Failed to load bookmarks: " + e.getMessage());
        }
    }

    private static void save() {
        try {
            Path path = getPath();
            Files.createDirectories(path.getParent());

            ArrayList<String> lines = new ArrayList<>();

            for (Bookmark bookmark : BOOKMARKS) {
                if (bookmark == null || bookmark.url().isBlank()) {
                    continue;
                }

                lines.add(cleanName(bookmark.name()) + SEPARATOR + cleanUrl(bookmark.url()));
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[CointRadioBookmarks] Failed to save bookmarks: " + e.getMessage());
        }
    }

    private static Path getPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cointcoregto_radio_bookmarks.txt");
    }

    private static String cleanUrl(String url) {
        if (url == null) {
            return "";
        }

        return url.trim()
                .replace("\r", "")
                .replace("\n", "");
    }

    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }

        String clean = name.trim()
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(String.valueOf(SEPARATOR), " ");

        while (clean.contains("  ")) {
            clean = clean.replace("  ", " ");
        }

        if (clean.length() > 40) {
            clean = clean.substring(0, 40).trim();
        }

        return clean;
    }

    public record Bookmark(String name, String url) {
    }
}