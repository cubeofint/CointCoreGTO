package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ClusterDimensionMigration {
    private static final int BUFFER_SIZE = 1024 * 1024;

    private ClusterDimensionMigration() {
    }

    public static PreparedArchive createArchive(
            MinecraftServer server,
            String dimensionId,
            Path stagingRoot,
            String migrationId
    ) throws IOException {
        Path source = resolveDimensionPath(server, dimensionId);
        if (!Files.isDirectory(source)) {
            throw new IOException("Папка измерения не найдена: " + source);
        }

        Path normalizedStaging = stagingRoot.toAbsolutePath().normalize();
        if (normalizedStaging.startsWith(source.toAbsolutePath().normalize())) {
            throw new IOException(
                    "dimension_migration_staging_path не может находиться внутри измерения"
            );
        }

        Files.createDirectories(normalizedStaging);
        String archiveName = migrationId + ".zip";
        Path archive = safeResolve(normalizedStaging, archiveName);
        Path temporaryArchive = safeResolve(normalizedStaging, archiveName + ".tmp");
        Files.deleteIfExists(temporaryArchive);

        List<Path> files;
        try (var stream = Files.walk(source)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> normalizeEntryName(source.relativize(path))))
                    .toList();
        }

        if (files.isEmpty()) {
            throw new IOException("Папка измерения не содержит файлов: " + source);
        }

        MessageDigest contentDigest = digest();
        long totalBytes = 0;

        try (OutputStream fileOutput = Files.newOutputStream(temporaryArchive);
             BufferedOutputStream bufferedOutput = new BufferedOutputStream(fileOutput, BUFFER_SIZE);
             ZipOutputStream zipOutput = new ZipOutputStream(bufferedOutput)) {
            zipOutput.setLevel(1);
            byte[] buffer = new byte[BUFFER_SIZE];

            for (Path file : files) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Символические ссылки в измерении не поддерживаются: " + file);
                }

                String entryName = normalizeEntryName(source.relativize(file));
                byte[] entryNameBytes = entryName.getBytes(StandardCharsets.UTF_8);
                contentDigest.update(intBytes(entryNameBytes.length));
                contentDigest.update(entryNameBytes);
                long size = Files.size(file);
                contentDigest.update(longBytes(size));
                totalBytes += size;

                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                zipOutput.putNextEntry(entry);

                try (InputStream input = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) {
                            continue;
                        }
                        zipOutput.write(buffer, 0, read);
                        contentDigest.update(buffer, 0, read);
                    }
                }

                zipOutput.closeEntry();
            }
        } catch (Exception exception) {
            Files.deleteIfExists(temporaryArchive);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(exception);
        }

        moveReplace(temporaryArchive, archive);
        long archiveSize = Files.size(archive);
        String archiveSha256 = sha256(archive);
        String contentSha256 = HexFormat.of().formatHex(contentDigest.digest());

        return new PreparedArchive(
                archiveName,
                archiveSha256,
                contentSha256,
                archiveSize,
                totalBytes,
                files.size(),
                source
        );
    }

    public static AppliedArchive applyArchive(
            MinecraftServer server,
            ClusterDatabase.DimensionMigration migration,
            Path stagingRoot
    ) throws IOException {
        Path archive = safeResolve(stagingRoot, migration.archiveName());
        if (!Files.isRegularFile(archive)) {
            throw new IOException("Архив миграции не найден: " + archive);
        }

        if (Files.size(archive) != migration.archiveSize()) {
            throw new IOException("Размер архива миграции не совпадает");
        }

        String actualArchiveSha = sha256(archive);
        if (!actualArchiveSha.equalsIgnoreCase(migration.archiveSha256())) {
            throw new IOException("SHA-256 архива миграции не совпадает");
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path target = resolveDimensionPath(server, migration.dimensionId());
        Path normalizedStaging = stagingRoot.toAbsolutePath().normalize();
        if (normalizedStaging.startsWith(target.toAbsolutePath().normalize())) {
            throw new IOException(
                    "dimension_migration_staging_path не может находиться внутри измерения"
            );
        }

        if (Files.isDirectory(target)) {
            String targetHash = treeSha256(target);
            if (targetHash.equalsIgnoreCase(migration.contentSha256())) {
                return new AppliedArchive(target, null, true);
            }
        }

        Path workRoot = safeResolve(
                worldRoot.resolve(".cointcoregto-migrations"),
                migration.migrationId()
        );
        Path extracted = workRoot.resolve("dimension").normalize();
        deleteTree(workRoot);
        Files.createDirectories(extracted);
        extractArchive(archive, extracted);

        String extractedHash = treeSha256(extracted);
        if (!extractedHash.equalsIgnoreCase(migration.contentSha256())) {
            deleteTree(workRoot);
            throw new IOException("SHA-256 распакованного измерения не совпадает");
        }

        Path relativeTarget = worldRoot.relativize(target);
        Path backup = safeResolve(
                worldRoot.resolve(".cointcoregto-migration-backups")
                        .resolve(migration.migrationId()),
                normalizeEntryName(relativeTarget)
        );

        Files.createDirectories(backup.getParent());

        if (Files.exists(target)) {
            if (!Files.exists(backup)) {
                moveDirectory(target, backup);
            } else {
                deleteTree(target);
            }
        }

        try {
            Files.createDirectories(target.getParent());
            moveDirectory(extracted, target);
            String installedHash = treeSha256(target);
            if (!installedHash.equalsIgnoreCase(migration.contentSha256())) {
                throw new IOException("SHA-256 установленного измерения не совпадает");
            }
        } catch (Exception exception) {
            deleteTree(target);
            if (Files.exists(backup)) {
                Files.createDirectories(target.getParent());
                moveDirectory(backup, target);
            }
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(exception);
        } finally {
            deleteTree(workRoot);
        }

        return new AppliedArchive(
                target,
                Files.exists(backup) ? backup : null,
                false
        );
    }

    public static void deleteArchive(
            Path stagingRoot,
            String archiveName
    ) throws IOException {
        if (stagingRoot == null || archiveName == null || archiveName.isBlank()) {
            return;
        }
        Files.deleteIfExists(safeResolve(stagingRoot, archiveName));
        Files.deleteIfExists(safeResolve(stagingRoot, archiveName + ".tmp"));
    }

    public static Path resolveDimensionPath(
            MinecraftServer server,
            String dimensionId
    ) throws IOException {
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        if (id == null) {
            throw new IOException("Некорректный ID измерения: " + dimensionId);
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();

        if (id.equals(Level.OVERWORLD.location())) {
            throw new IOException("Миграция minecraft:overworld пока не поддерживается");
        }

        if (id.equals(Level.NETHER.location())) {
            return safeResolve(worldRoot, "DIM-1");
        }

        if (id.equals(Level.END.location())) {
            return safeResolve(worldRoot, "DIM1");
        }

        Path dimensionsRoot = worldRoot.resolve("dimensions").normalize();
        Path namespaceRoot = safeResolve(dimensionsRoot, id.getNamespace());
        return safeResolve(namespaceRoot, id.getPath());
    }

    public static String treeSha256(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException("Папка не найдена: " + root);
        }

        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> normalizeEntryName(root.relativize(path))))
                    .toList();
        }

        MessageDigest digest = digest();
        byte[] buffer = new byte[BUFFER_SIZE];

        for (Path file : files) {
            if (Files.isSymbolicLink(file)) {
                throw new IOException("Символические ссылки не поддерживаются: " + file);
            }

            String entryName = normalizeEntryName(root.relativize(file));
            byte[] entryNameBytes = entryName.getBytes(StandardCharsets.UTF_8);
            digest.update(intBytes(entryNameBytes.length));
            digest.update(entryNameBytes);
            long size = Files.size(file);
            digest.update(longBytes(size));

            try (InputStream input = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE)) {
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static void extractArchive(
            Path archive,
            Path destination
    ) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream fileInput = Files.newInputStream(archive);
             BufferedInputStream bufferedInput = new BufferedInputStream(fileInput, BUFFER_SIZE);
             ZipInputStream zipInput = new ZipInputStream(bufferedInput)) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                Path target = safeResolve(destination, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(target), BUFFER_SIZE)) {
                        int read;
                        while ((read = zipInput.read(buffer)) >= 0) {
                            if (read > 0) {
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    if (entry.getTime() >= 0) {
                        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(entry.getTime()));
                    }
                }
                zipInput.closeEntry();
            }
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        byte[] buffer = new byte[BUFFER_SIZE];

        try (InputStream input = new BufferedInputStream(Files.newInputStream(file), BUFFER_SIZE);
             DigestInputStream digestInput = new DigestInputStream(input, digest)) {
            while (digestInput.read(buffer) >= 0) {
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
    }

    private static Path safeResolve(Path root, String child) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(child).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Путь выходит за пределы разрешённой папки: " + child);
        }
        return resolved;
    }

    private static String normalizeEntryName(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (AtomicMoveNotSupportedException ignored) {
        }

        Files.move(source, target);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path directory,
                    IOException exception
            ) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static byte[] intBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static byte[] longBytes(long value) {
        return new byte[]{
                (byte) (value >>> 56),
                (byte) (value >>> 48),
                (byte) (value >>> 40),
                (byte) (value >>> 32),
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    public record PreparedArchive(
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize,
            long uncompressedSize,
            int fileCount,
            Path sourcePath
    ) {
    }

    public record AppliedArchive(
            Path targetPath,
            Path backupPath,
            boolean alreadyApplied
    ) {
    }
}
