package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftbquests.integration.PermissionsHelper;
import dev.ftb.mods.ftbquests.net.SyncEditorPermissionMessage;
import dev.ftb.mods.ftbquests.net.SyncQuestsMessage;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class ClusterQuestBookCodec {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_ENTRY_COUNT = 100_000;

    private ClusterQuestBookCodec() {
    }

    public static Snapshot capture(
            MinecraftServer server,
            int maximumArchiveBytes
    ) throws IOException {
        if (server == null) {
            throw new IOException("Minecraft server is unavailable");
        }
        if (!server.isSameThread()) {
            throw new IOException("FTB Quests book capture must run on the server thread");
        }
        if (maximumArchiveBytes <= 0) {
            throw new IOException("Maximum FTB Quests book archive size must be positive");
        }

        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile != null) {
            questFile.saveNow();
        }

        Path folder = questFolder();
        Files.createDirectories(folder);

        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(folder)) {
            stream.filter(path -> Files.isRegularFile(path))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .sorted(Comparator.comparing(path -> normalizeEntryName(folder.relativize(path))))
                    .forEach(files::add);
        }

        if (files.size() > MAX_ENTRY_COUNT) {
            throw new IOException("FTB Quests book contains too many files: " + files.size());
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(new LimitedOutputStream(bytes, maximumArchiveBytes))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            for (Path file : files) {
                String entryName = normalizeEntryName(folder.relativize(file));
                validateEntryName(entryName);
                ZipEntry entry = new ZipEntry(entryName);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                try (InputStream input = Files.newInputStream(file)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            zip.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        }

        byte[] archive = bytes.toByteArray();
        if (archive.length > maximumArchiveBytes) {
            throw new IOException(
                    "FTB Quests book archive is too large: "
                            + archive.length
                            + " > "
                            + maximumArchiveBytes
            );
        }

        return new Snapshot(
                archive,
                sha256(archive),
                archive.length,
                files.size(),
                Instant.now()
        );
    }

    public static ApplyResult apply(
            MinecraftServer server,
            ClusterDatabase.QuestBookRevision revision,
            int maximumArchiveBytes,
            int backupRetention
    ) throws IOException {
        if (server == null) {
            throw new IOException("Minecraft server is unavailable");
        }
        if (!server.isSameThread()) {
            throw new IOException("FTB Quests book apply must run on the server thread");
        }
        if (revision == null) {
            throw new IOException("FTB Quests book revision is unavailable");
        }

        validateRevision(revision, maximumArchiveBytes);

        Path folder = questFolder();
        Path parent = folder.getParent();
        if (parent == null) {
            throw new IOException("FTB Quests book folder has no parent: " + folder);
        }
        Files.createDirectories(parent);

        Path staging = parent.resolve(".cointcoregto-questbook-stage-" + UUID.randomUUID());
        Path backupRoot = parent.resolve(".cointcoregto-questbook-backups");
        Files.createDirectories(staging);
        Files.createDirectories(backupRoot);

        long extractedBytes = 0L;
        int extractedEntries = 0;
        long maximumExtractedBytes = Math.max(
                maximumArchiveBytes,
                Math.min(1_073_741_824L, (long) maximumArchiveBytes * 16L)
        );

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(revision.archiveData()))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zip.closeEntry();
                    continue;
                }
                extractedEntries++;
                if (extractedEntries > MAX_ENTRY_COUNT) {
                    throw new IOException("FTB Quests book archive contains too many files");
                }

                String entryName = entry.getName();
                validateEntryName(entryName);
                Path output = staging.resolve(entryName).normalize();
                if (!output.startsWith(staging)) {
                    throw new IOException("FTB Quests book archive escapes the staging folder");
                }
                Path outputParent = output.getParent();
                if (outputParent != null) {
                    Files.createDirectories(outputParent);
                }

                try (OutputStream fileOutput = Files.newOutputStream(output)) {
                    int read;
                    while ((read = zip.read(buffer)) >= 0) {
                        if (read <= 0) {
                            continue;
                        }
                        extractedBytes += read;
                        if (extractedBytes > maximumExtractedBytes) {
                            throw new IOException(
                                    "FTB Quests book extracted data is too large: "
                                            + extractedBytes
                                            + " > "
                                            + maximumExtractedBytes
                            );
                        }
                        fileOutput.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception exception) {
            deleteRecursively(staging);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to extract FTB Quests book archive", exception);
        }

        if (extractedEntries != revision.fileCount()) {
            deleteRecursively(staging);
            throw new IOException(
                    "FTB Quests book file count mismatch: "
                            + extractedEntries
                            + " != "
                            + revision.fileCount()
            );
        }

        String timestamp = DateTimeFormatter.ISO_INSTANT
                .format(Instant.now())
                .replace(':', '-')
                .replace('.', '-');
        Path backup = backupRoot.resolve(
                "revision-"
                        + revision.revisionId()
                        + "-"
                        + timestamp
        );

        boolean hadOriginal = Files.exists(folder);
        try {
            if (hadOriginal) {
                move(folder, backup);
            }
            move(staging, folder);

            reloadQuestBook(server);

            cleanupBackups(backupRoot, Math.max(1, backupRetention));
            return new ApplyResult(folder, hadOriginal ? backup : null, extractedEntries, extractedBytes);
        } catch (Exception exception) {
            try {
                if (Files.exists(folder)) {
                    deleteRecursively(folder);
                }
                if (hadOriginal && Files.exists(backup)) {
                    move(backup, folder);
                    reloadQuestBook(server);
                }
            } catch (Exception rollbackException) {
                exception.addSuppressed(rollbackException);
            }
            deleteRecursively(staging);
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Unable to apply FTB Quests book revision", exception);
        }
    }

    private static void reloadQuestBook(MinecraftServer server) throws IOException {
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile == null) {
            throw new IOException("FTB Quests server quest file is unavailable");
        }

        try {
            questFile.load();
            new SyncQuestsMessage(questFile).sendToAll(server);
            server.getPlayerList().getPlayers().forEach(player ->
                    new SyncEditorPermissionMessage(
                            PermissionsHelper.hasEditorPermission(player, false)
                    ).sendTo(player)
            );
        } catch (Exception exception) {
            throw new IOException("Unable to reload FTB Quests through its server API", exception);
        }
    }

    public static Path questFolder() {
        ServerQuestFile questFile = ServerQuestFile.INSTANCE;
        if (questFile != null && questFile.getFolder() != null) {
            return questFile.getFolder().toAbsolutePath().normalize();
        }
        return FMLPaths.CONFIGDIR.get()
                .resolve("ftbquests")
                .resolve("quests")
                .toAbsolutePath()
                .normalize();
    }

    public static void validateRevision(
            ClusterDatabase.QuestBookRevision revision,
            int maximumArchiveBytes
    ) throws IOException {
        byte[] archive = revision.archiveData();
        if (archive == null || archive.length == 0) {
            throw new IOException("FTB Quests book archive is empty");
        }
        if (archive.length != revision.archiveSize()) {
            throw new IOException(
                    "FTB Quests book archive size mismatch: "
                            + archive.length
                            + " != "
                            + revision.archiveSize()
            );
        }
        if (archive.length > maximumArchiveBytes) {
            throw new IOException(
                    "FTB Quests book archive exceeds configured limit: "
                            + archive.length
                            + " > "
                            + maximumArchiveBytes
            );
        }
        String actualHash = sha256(archive);
        if (!actualHash.equalsIgnoreCase(revision.archiveSha256())) {
            throw new IOException(
                    "FTB Quests book SHA-256 mismatch: "
                            + actualHash
                            + " != "
                            + revision.archiveSha256()
            );
        }
        if (revision.fileCount() < 0 || revision.fileCount() > MAX_ENTRY_COUNT) {
            throw new IOException("FTB Quests book file count is invalid: " + revision.fileCount());
        }
    }

    public static String sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }

    private static String normalizeEntryName(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static void validateEntryName(String entryName) throws IOException {
        if (entryName == null || entryName.isBlank()) {
            throw new IOException("FTB Quests book archive contains an empty path");
        }
        String normalized = entryName.replace('\\', '/');
        if (normalized.startsWith("/")
                || normalized.contains("../")
                || normalized.equals("..")
                || normalized.contains(":")
                || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe FTB Quests book archive path: " + entryName);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private static void cleanupBackups(Path backupRoot, int retention) throws IOException {
        if (!Files.isDirectory(backupRoot)) {
            return;
        }
        List<Path> backups = new ArrayList<>();
        try (var stream = Files.list(backupRoot)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(ClusterQuestBookCodec::lastModifiedMillis).reversed())
                    .forEach(backups::add);
        }
        for (int index = retention; index < backups.size(); index++) {
            deleteRecursively(backups.get(index));
        }
    }

    private static long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            IOException failure = null;
            for (Path entry : paths) {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException exception) {
                    if (failure == null) {
                        failure = exception;
                    } else {
                        failure.addSuppressed(exception);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    public record Snapshot(
            byte[] archiveData,
            String archiveSha256,
            int archiveSize,
            int fileCount,
            Instant capturedAt
    ) {
        public Snapshot {
            archiveData = archiveData == null ? null : archiveData.clone();
        }

        @Override
        public byte[] archiveData() {
            return archiveData == null ? null : archiveData.clone();
        }
    }

    public record ApplyResult(
            Path folder,
            Path backupFolder,
            int fileCount,
            long extractedBytes
    ) {
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final long maximumBytes;
        private long written;

        private LimitedOutputStream(OutputStream delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            ensureCapacity(length);
            delegate.write(data, offset, length);
            written += length;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        private void ensureCapacity(int length) throws IOException {
            if (written + length > maximumBytes) {
                throw new IOException(
                        "FTB Quests book archive exceeds configured limit: "
                                + (written + length)
                                + " > "
                                + maximumBytes
                );
            }
        }
    }
}
