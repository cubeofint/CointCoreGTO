package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import Crazer.cubeofinterest.cointcoregto.recipe.GtoCustomRecipeLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Server-side file browser for recipe JSON files managed by CointCoreGTO.
 *
 * Every path coming from a client is resolved relative to one of the two
 * recipe roots and is checked against traversal/symlink escapes before use.
 */
public final class RecipeEditorServerFileService {
    public static final int MAX_LIST_ENTRIES = 2_048;
    public static final int MAX_READ_BYTES = 256_000;

    private RecipeEditorServerFileService() {
    }

    public record Entry(
            String relativePath,
            String recipeId,
            String recipeType,
            long size,
            long modified,
            boolean validJson
    ) {
    }

    public record ReadResult(boolean success, String message, String relativePath, String json) {
        static ReadResult failure(String message, String relativePath) {
            return new ReadResult(false, message, relativePath == null ? "" : relativePath, "");
        }
    }

    public record DeleteResult(boolean success, String message, String relativePath) {
        static DeleteResult failure(String message, String relativePath) {
            return new DeleteResult(false, message, relativePath == null ? "" : relativePath);
        }
    }

    public static List<Entry> list(boolean crafting) throws IOException {
        Path root = root(crafting);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }

        List<Entry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(RecipeEditorServerFileService::isJsonFile)
                    .limit(MAX_LIST_ENTRIES)
                    .forEach(path -> entries.add(describe(root, path)));
        }

        entries.sort(Comparator.comparing(Entry::relativePath, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(entries);
    }

    public static ReadResult read(boolean crafting, String relativePath) {
        try {
            Path target = resolveExisting(crafting, relativePath);
            long size = Files.size(target);
            if (size > MAX_READ_BYTES) {
                return ReadResult.failure("JSON слишком большой для удалённого просмотра: " + size + " bytes", relativePath);
            }

            String json = Files.readString(target, StandardCharsets.UTF_8);
            return new ReadResult(
                    true,
                    "Загружено с сервера: " + normalize(relativePath),
                    normalize(relativePath),
                    json
            );
        } catch (Throwable throwable) {
            return ReadResult.failure(message(throwable), relativePath);
        }
    }

    public static DeleteResult delete(boolean crafting, String relativePath) {
        try {
            Path root = root(crafting);
            Path target = resolveExisting(crafting, relativePath);
            Files.delete(target);
            removeEmptyParents(root, target.getParent());

            String kind = crafting ? "верстачного" : "GT/GTO";
            return new DeleteResult(
                    true,
                    "Файл " + kind + " рецепта удалён. Из активного RecipeManager рецепт исчезнет после полного рестарта сервера.",
                    normalize(relativePath)
            );
        } catch (Throwable throwable) {
            return DeleteResult.failure(message(throwable), relativePath);
        }
    }

    /**
     * Resolves an existing recipe file for read/write operations initiated by
     * the server browser. Package-private so RecipeEditorFileService can update
     * exactly the file that was opened instead of creating a duplicate.
     */
    static Path resolveExisting(boolean crafting, String relativePath) throws IOException {
        Path target = resolve(crafting, relativePath);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Файл рецепта не найден: " + normalize(relativePath));
        }
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Символические ссылки для Recipe Editor запрещены");
        }

        Path rootReal = root(crafting).toRealPath();
        Path targetReal = target.toRealPath();
        if (!targetReal.startsWith(rootReal)) {
            throw new IOException("Недопустимый путь файла рецепта");
        }
        return targetReal;
    }

    private static Path resolve(boolean crafting, String relativePath) throws IOException {
        Path root = root(crafting);
        Path relative = parseRelative(relativePath);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Недопустимый путь файла рецепта");
        }
        return target;
    }

    private static Path parseRelative(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IOException("Пустой путь файла рецепта");
        }
        if (relativePath.length() > 1_024) {
            throw new IOException("Слишком длинный путь файла рецепта");
        }

        String portable = relativePath.replace('\\', '/');
        Path relative = Path.of(portable).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("Недопустимый путь файла рецепта");
        }
        if (!isJsonFile(relative)) {
            throw new IOException("Recipe Editor работает только с .json файлами");
        }
        return relative;
    }

    private static Entry describe(Path root, Path path) {
        String relative = normalize(root.relativize(path).toString());
        String id = "<invalid>";
        String type = "<invalid>";
        boolean valid = false;
        long size = 0L;
        long modified = 0L;

        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
            if (size <= MAX_READ_BYTES) {
                JsonObject object = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                if (object.has("id")) {
                    id = truncate(object.get("id").getAsString(), 250);
                }
                if (object.has("type")) {
                    type = truncate(object.get("type").getAsString(), 250);
                }
                valid = object.has("id") && object.has("type");
            }
        } catch (Throwable ignored) {
            // Invalid files are still shown so an administrator can inspect or
            // delete them from the remote browser.
        }

        return new Entry(relative, id, type, size, modified, valid);
    }

    private static Path root(boolean crafting) {
        return (crafting ? CraftingRecipeLoader.RECIPE_DIRECTORY : GtoCustomRecipeLoader.RECIPE_DIRECTORY)
                .toAbsolutePath()
                .normalize();
    }

    private static boolean isJsonFile(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json");
    }

    private static void removeEmptyParents(Path root, Path start) {
        Path current = start;
        while (current != null && !current.equals(root) && current.startsWith(root)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException ignored) {
                return;
            }

            try {
                Files.deleteIfExists(current);
            } catch (IOException ignored) {
                return;
            }
            current = current.getParent();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    private static String message(Throwable throwable) {
        String value = throwable.getMessage();
        return value == null || value.isBlank() ? throwable.getClass().getSimpleName() : value;
    }
}
