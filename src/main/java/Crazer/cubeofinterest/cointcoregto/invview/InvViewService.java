package Crazer.cubeofinterest.cointcoregto.invview;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.network.NetworkHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

public final class InvViewService {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:InvView");
    public static final String VIEW_PERMISSION = "cointcoregto.invview";
    public static final String EDIT_PERMISSION = "cointcoregto.invview.edit";
    public static final String OFFLINE_PERMISSION = "cointcoregto.invview.offline";
    public static final int CURIOS_PAGE_SIZE = 36;

    private InvViewService() {
    }

    public static boolean open(ServerPlayer viewer, String targetName) {
        if (!canView(viewer)) {
            viewer.sendSystemMessage(Component.literal("§cНет прав: " + VIEW_PERMISSION));
            return false;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return false;
        }

        try {
            ServerPlayer online = server.getPlayerList().getPlayerByName(targetName);
            if (online != null) {
                if (online.getUUID().equals(viewer.getUUID())) {
                    viewer.sendSystemMessage(Component.literal("§cНельзя открыть свой инвентарь через InvView."));
                    return false;
                }
                openResolved(viewer, online.getGameProfile(), online, false, InvViewMode.MAIN, 0);
                return true;
            }

            Optional<GameProfile> profile = resolveOfflineProfile(server, targetName);
            if (profile.isEmpty()) {
                viewer.sendSystemMessage(Component.literal("§cНе найдены сохранённые данные игрока: " + targetName));
                return false;
            }
            return openOffline(viewer, profile.get(), InvViewMode.MAIN, 0);
        } catch (Throwable throwable) {
            viewer.sendSystemMessage(Component.literal("§cInvView: ошибка оффлайн-загрузки: "
                    + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage())));
            LOGGER.error("Failed to resolve/open InvView target={} viewer={}",
                    targetName, viewer.getGameProfile().getName(), throwable);
            return false;
        }
    }

    public static void open(ServerPlayer viewer, UUID targetId, String targetName, InvViewMode mode, int page) {
        if (!canView(viewer)) {
            viewer.closeContainer();
            return;
        }
        if (targetId.equals(viewer.getUUID())) {
            viewer.closeContainer();
            viewer.sendSystemMessage(Component.literal("§cНельзя открыть свой инвентарь через InvView."));
            return;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(targetId);
        if (online != null) {
            openResolved(viewer, online.getGameProfile(), online, false, mode, page);
            return;
        }
        GameProfile profile = new GameProfile(targetId, targetName);
        openOffline(viewer, profile, mode, page);
    }

    static String[] getKnownPlayerNames(MinecraftServer server, UUID excludedId) {
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (excludedId == null || !excludedId.equals(player.getUUID())) {
                names.add(player.getGameProfile().getName());
            }
        }

        Path userCache = server.getServerDirectory().toPath().resolve("usercache.json");
        if (Files.isRegularFile(userCache)) {
            try (var reader = Files.newBufferedReader(userCache, StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonArray()) {
                    for (JsonElement element : root.getAsJsonArray()) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject object = element.getAsJsonObject();
                        if (!object.has("name") || !object.has("uuid")) {
                            continue;
                        }
                        String name = object.get("name").getAsString();
                        UUID id;
                        try {
                            id = UUID.fromString(object.get("uuid").getAsString());
                        } catch (IllegalArgumentException ignored) {
                            continue;
                        }
                        if ((excludedId == null || !excludedId.equals(id)) && hasPlayerData(server, id)) {
                            names.add(name);
                        }
                    }
                }
            } catch (Throwable throwable) {
                LOGGER.warn("Failed to read offline player suggestions from {}", userCache, throwable);
            }
        }

        return names.toArray(String[]::new);
    }

    static boolean canView(ServerPlayer player) {
        return has(player, VIEW_PERMISSION);
    }

    static boolean canEdit(ServerPlayer player) {
        return has(player, EDIT_PERMISSION);
    }

    static boolean canOffline(ServerPlayer player) {
        return has(player, OFFLINE_PERMISSION);
    }

    private static Optional<GameProfile> resolveOfflineProfile(MinecraftServer server, String targetName) {
        Optional<GameProfile> cached = Optional.empty();
        try {
            cached = server.getProfileCache().get(targetName);
            if (cached.isPresent() && hasPlayerData(server, cached.get().getId())) {
                return cached;
            }
        } catch (Throwable throwable) {
            LOGGER.warn("Profile cache lookup failed for {}", targetName, throwable);
        }

        UUID offlineId = UUID.nameUUIDFromBytes(("OfflinePlayer:" + targetName).getBytes(StandardCharsets.UTF_8));
        if (hasPlayerData(server, offlineId)) {
            return Optional.of(new GameProfile(offlineId, targetName));
        }

        return cached.filter(profile -> hasPlayerData(server, profile.getId()));
    }

    private static boolean hasPlayerData(MinecraftServer server, UUID id) {
        Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        String uuid = id.toString();
        return Files.isRegularFile(playerDir.resolve(uuid + ".dat"))
                || Files.isRegularFile(playerDir.resolve(uuid + ".dat_old"));
    }

    private static boolean loadOfflineData(MinecraftServer server, ServerPlayer player, UUID id) throws Exception {
        Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        String uuid = id.toString();
        Path current = playerDir.resolve(uuid + ".dat");
        Path old = playerDir.resolve(uuid + ".dat_old");
        Path source = Files.isRegularFile(current) ? current : Files.isRegularFile(old) ? old : null;
        if (source == null) {
            return false;
        }
        CompoundTag data;
        try (var input = Files.newInputStream(source)) {
            data = NbtIo.readCompressed(input);
        }
        player.load(data);
        return true;
    }

    private static boolean openOffline(ServerPlayer viewer, GameProfile profile, InvViewMode mode, int page) {
        if (!canOffline(viewer)) {
            viewer.sendSystemMessage(Component.literal("§cДля оффлайн-инвентарей нужно право " + OFFLINE_PERMISSION));
            return false;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return false;
        }
        try {
            ServerPlayer fake = new ServerPlayer(server, server.overworld(), profile);
            if (!loadOfflineData(server, fake, profile.getId())) {
                viewer.sendSystemMessage(Component.literal("§cНет сохранённых данных игрока " + profile.getName()));
                return false;
            }
            openResolved(viewer, profile, fake, true, mode, page);
            return true;
        } catch (Throwable throwable) {
            viewer.sendSystemMessage(Component.literal("§cInvView: ошибка загрузки playerdata: "
                    + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage())));
            LOGGER.error("Failed to load offline playerdata target={} uuid={} viewer={}",
                    profile.getName(), profile.getId(), viewer.getGameProfile().getName(), throwable);
            return false;
        }
    }

    private static void openResolved(
            ServerPlayer viewer,
            GameProfile profile,
            ServerPlayer target,
            boolean offline,
            InvViewMode mode,
            int requestedPage
    ) {
        boolean editable = canEdit(viewer);
        List<InvViewCuriosBridge.SlotRef> allCurios = mode == InvViewMode.CURIOS
                ? InvViewCuriosBridge.collect(target)
                : List.of();
        int pageCount = mode == InvViewMode.CURIOS
                ? Math.max(1, (allCurios.size() + CURIOS_PAGE_SIZE - 1) / CURIOS_PAGE_SIZE)
                : 1;
        int page = Math.max(0, Math.min(pageCount - 1, requestedPage));
        List<InvViewCuriosBridge.SlotRef> pageCurios;
        if (mode == InvViewMode.CURIOS && !allCurios.isEmpty()) {
            int from = page * CURIOS_PAGE_SIZE;
            int to = Math.min(allCurios.size(), from + CURIOS_PAGE_SIZE);
            pageCurios = List.copyOf(allCurios.subList(from, to));
        } else {
            pageCurios = List.of();
        }

        int targetSlots = switch (mode) {
            case MAIN -> 41;
            case ENDER -> 27;
            case CURIOS -> pageCurios.size();
        };
        List<String> labels = pageCurios.stream().map(InvViewCuriosBridge.SlotRef::label).toList();
        String name = profile.getName() == null || profile.getName().isBlank()
                ? profile.getId().toString()
                : profile.getName();

        try {
            NetworkHooks.openScreen(
                    viewer,
                    new SimpleMenuProvider(
                            (windowId, inventory, ignored) -> InvViewMenu.server(
                                    windowId,
                                    inventory,
                                    target,
                                    profile.getId(),
                                    name,
                                    mode,
                                    page,
                                    pageCount,
                                    offline,
                                    editable,
                                    pageCurios
                            ),
                            Component.literal("Инвентарь: " + name)
                    ),
                    buffer -> {
                        buffer.writeUUID(profile.getId());
                        buffer.writeUtf(name, 64);
                        buffer.writeVarInt(mode.ordinal());
                        buffer.writeVarInt(page);
                        buffer.writeVarInt(pageCount);
                        buffer.writeBoolean(offline);
                        buffer.writeBoolean(editable);
                        buffer.writeVarInt(targetSlots);
                        buffer.writeVarInt(labels.size());
                        for (String label : labels) {
                            buffer.writeUtf(label, 128);
                        }
                    }
            );
            if (!(viewer.containerMenu instanceof InvViewMenu)) {
                viewer.sendSystemMessage(Component.literal("§cInvView: сервер не открыл меню. Проверь latest.log."));
                LOGGER.error("InvView menu was not installed for viewer={} target={} mode={} offline={}",
                        viewer.getGameProfile().getName(), name, mode, offline);
            }
        } catch (Throwable throwable) {
            viewer.sendSystemMessage(Component.literal("§cInvView: ошибка открытия: " + throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage())));
            LOGGER.error("Failed to open InvView for viewer={} target={} mode={} offline={}",
                    viewer.getGameProfile().getName(), name, mode, offline, throwable);
        }
    }

    private static boolean has(ServerPlayer player, String permission) {
        return player != null && (player.hasPermissions(2) || CointCoreGTO.hasPermissionNode(player, permission));
    }
}
