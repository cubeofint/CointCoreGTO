package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.Team;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ClusterTeamDataCodec {
    public static final int FORMAT_VERSION = 1;
    public static final String SCOPE_PLAYER = "PLAYER";
    public static final String SCOPE_TEAM = "TEAM";

    private ClusterTeamDataCodec() {
    }

    public static CompoundTag capture(ServerPlayer player) throws IOException {
        Objects.requireNonNull(player, "player");

        Object manager = FTBTeamsAPI.api().getManager();
        Team activeTeam = FTBTeamsAPI.api()
                .getManager()
                .getTeamForPlayer(player)
                .orElseThrow(() -> new IOException(
                        "FTB Teams active team is unavailable"
                ));

        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT_VERSION);
        root.putUUID("player_uuid", player.getUUID());
        root.putUUID("active_team_uuid", activeTeam.getId());
        root.putString(
                "scope",
                activeTeam.isPlayerTeam() ? SCOPE_PLAYER : SCOPE_TEAM
        );
        root.putString("team_name", activeTeam.getShortName());
        root.put("active_team", serializeTeam(activeTeam));

        Set<UUID> memberIds = new LinkedHashSet<>(activeTeam.getMembers());
        memberIds.add(player.getUUID());

        ListTag personalTeams = new ListTag();
        for (UUID memberId : memberIds) {
            Object personalTeam = findPersonalTeam(manager, memberId);
            if (personalTeam == null) {
                continue;
            }

            CompoundTag entry = new CompoundTag();
            entry.putUUID("player_uuid", memberId);
            entry.put("data", serializeTeam(personalTeam));
            personalTeams.add(entry);
        }
        root.put("personal_teams", personalTeams);

        return root;
    }

    public static ApplyResult apply(
            ServerPlayer player,
            CompoundTag root
    ) throws IOException {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(root, "root");

        int version = root.getInt("format");
        if (version != FORMAT_VERSION) {
            throw new IOException(
                    "Unsupported FTB Teams snapshot version: " + version
            );
        }
        if (!root.hasUUID("player_uuid")
                || !root.hasUUID("active_team_uuid")) {
            throw new IOException("FTB Teams snapshot has no UUID metadata");
        }

        UUID snapshotPlayerUuid = root.getUUID("player_uuid");
        if (!snapshotPlayerUuid.equals(player.getUUID())) {
            throw new IOException(
                    "FTB Teams snapshot belongs to "
                            + snapshotPlayerUuid
                            + ", current player is "
                            + player.getUUID()
            );
        }

        UUID activeTeamUuid = root.getUUID("active_team_uuid");
        String scope = normalizeScope(root.getString("scope"));
        Object manager = FTBTeamsAPI.api().getManager();
        Object currentPersonal = ensurePersonalTeam(
                manager,
                player.getUUID()
        );
        Object previousEffective = invokeOptional(
                currentPersonal,
                "getEffectiveTeam"
        );

        List<Object> changedTeams = new ArrayList<>();
        ListTag personalTeams = root.getList(
                "personal_teams",
                Tag.TAG_COMPOUND
        );
        for (int i = 0; i < personalTeams.size(); i++) {
            CompoundTag entry = personalTeams.getCompound(i);
            if (!entry.hasUUID("player_uuid")
                    || !entry.contains("data", Tag.TAG_COMPOUND)) {
                continue;
            }

            UUID memberId = entry.getUUID("player_uuid");
            Object personalTeam = ensurePersonalTeam(manager, memberId);
            deserializeTeam(
                    personalTeam,
                    entry.getCompound("data")
            );
            changedTeams.add(personalTeam);
        }

        currentPersonal = ensurePersonalTeam(manager, player.getUUID());
        Object activeTeam;
        if (SCOPE_PLAYER.equals(scope)) {
            detachFromPreviousTeam(
                    previousEffective,
                    currentPersonal,
                    player.getUUID()
            );
            invokeRequired(
                    currentPersonal,
                    "setEffectiveTeam",
                    currentPersonal
            );
            activeTeam = currentPersonal;
        } else {
            if (!root.contains("active_team", Tag.TAG_COMPOUND)) {
                throw new IOException(
                        "FTB Teams party snapshot has no active team data"
                );
            }

            activeTeam = ensurePartyTeam(manager, activeTeamUuid);
            deserializeTeam(
                    activeTeam,
                    root.getCompound("active_team")
            );
            registerPartyName(manager, activeTeam);
            changedTeams.add(activeTeam);

            detachFromPreviousTeam(
                    previousEffective,
                    activeTeam,
                    player.getUUID()
            );

            Set<UUID> members = activeTeam instanceof Team team
                    ? team.getMembers()
                    : Set.of(player.getUUID());
            Set<UUID> effectiveMembers = new LinkedHashSet<>(members);
            effectiveMembers.add(player.getUUID());

            for (UUID memberId : effectiveMembers) {
                Object personalTeam = ensurePersonalTeam(manager, memberId);
                invokeRequired(
                        personalTeam,
                        "setEffectiveTeam",
                        activeTeam
                );
                invokeOptional(personalTeam, "markDirty");
                changedTeams.add(personalTeam);
            }
        }

        invokeOptional(activeTeam, "markDirty");
        invokeOptional(currentPersonal, "markDirty");
        invokeOptional(currentPersonal, "updatePresence");
        invokeOptional(manager, "markDirty");
        invokeOptional(manager, "saveNow");
        syncToAll(manager, changedTeams);
        invokeOptional(manager, "syncAllToPlayer", player);

        Team resolved = FTBTeamsAPI.api()
                .getManager()
                .getTeamForPlayer(player)
                .orElseThrow(() -> new IOException(
                        "FTB Teams active team disappeared after apply"
                ));
        if (!activeTeamUuid.equals(resolved.getId())) {
            throw new IOException(
                    "FTB Teams apply mismatch: expected="
                            + activeTeamUuid
                            + ", actual="
                            + resolved.getId()
            );
        }

        return new ApplyResult(
                true,
                activeTeamUuid,
                scope,
                root.getString("team_name"),
                personalTeams.size()
        );
    }

    public static String normalizeScope(String scope) {
        return SCOPE_PLAYER.equalsIgnoreCase(scope)
                ? SCOPE_PLAYER
                : SCOPE_TEAM;
    }

    private static CompoundTag serializeTeam(Object team) throws IOException {
        Object value = invokeRequired(team, "serializeNBT");
        if (!(value instanceof CompoundTag compoundTag)) {
            throw new IOException(
                    "FTB Teams serializeNBT returned "
                            + (value == null
                            ? "null"
                            : value.getClass().getName())
            );
        }
        return compoundTag.copy();
    }

    private static void deserializeTeam(
            Object team,
            CompoundTag data
    ) throws IOException {
        invokeRequired(team, "deserializeNBT", data.copy());
    }

    private static Object ensurePartyTeam(
            Object manager,
            UUID teamUuid
    ) throws IOException {
        Object existing = findTeam(manager, teamUuid);
        if (existing != null && existing.getClass().getName().endsWith("PartyTeam")) {
            return existing;
        }
        if (existing != null) {
            throw new IOException(
                    "FTB Teams UUID collision for " + teamUuid
            );
        }

        Object partyTeam = instantiateTeam(
                "dev.ftb.mods.ftbteams.data.PartyTeam",
                manager,
                teamUuid
        );
        putIntoMapField(manager, "teamMap", teamUuid, partyTeam);
        return partyTeam;
    }

    private static Object ensurePersonalTeam(
            Object manager,
            UUID playerUuid
    ) throws IOException {
        Object existing = findPersonalTeam(manager, playerUuid);
        if (existing != null) {
            return existing;
        }

        Object playerTeam = instantiateTeam(
                "dev.ftb.mods.ftbteams.data.PlayerTeam",
                manager,
                playerUuid
        );
        putIntoMapField(manager, "knownPlayers", playerUuid, playerTeam);
        return playerTeam;
    }

    private static Object findPersonalTeam(
            Object manager,
            UUID playerUuid
    ) throws IOException {
        Object result = invokeOptional(
                manager,
                "getPersonalTeamForPlayerID",
                playerUuid
        );
        result = unwrapOptional(result);
        if (result != null) {
            return result;
        }

        result = invokeOptional(
                manager,
                "getPlayerTeamForPlayerID",
                playerUuid
        );
        result = unwrapOptional(result);
        if (result != null) {
            return result;
        }

        return getFromMapField(manager, "knownPlayers", playerUuid);
    }

    private static Object findTeam(
            Object manager,
            UUID teamUuid
    ) throws IOException {
        Object result = invokeOptional(manager, "getTeamByID", teamUuid);
        result = unwrapOptional(result);
        if (result != null) {
            return result;
        }
        return getFromMapField(manager, "teamMap", teamUuid);
    }

    private static Object instantiateTeam(
            String className,
            Object manager,
            UUID id
    ) throws IOException {
        try {
            Class<?> type = Class.forName(
                    className,
                    true,
                    ClusterTeamDataCodec.class.getClassLoader()
            );
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 2
                        && parameterTypes[0].isAssignableFrom(manager.getClass())
                        && parameterTypes[1] == UUID.class) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(manager, id);
                }
            }
            throw new IOException(
                    "No compatible constructor for " + className
            );
        } catch (ClassNotFoundException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IOException(
                    "Unable to create FTB Teams object " + className,
                    unwrap(exception)
            );
        }
    }

    private static void detachFromPreviousTeam(
            Object previousEffective,
            Object newEffective,
            UUID playerUuid
    ) throws IOException {
        if (previousEffective == null
                || previousEffective == newEffective
                || previousEffective.getClass().getName().endsWith("PlayerTeam")) {
            return;
        }

        Field ranksField = findField(previousEffective.getClass(), "ranks");
        if (ranksField != null) {
            try {
                ranksField.setAccessible(true);
                Object value = ranksField.get(previousEffective);
                if (value instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> mutable = (Map<Object, Object>) map;
                    mutable.remove(playerUuid);
                }
            } catch (IllegalAccessException exception) {
                throw new IOException(
                        "Unable to detach player from previous FTB Team",
                        exception
                );
            }
        }
        invokeOptional(previousEffective, "markDirty");
    }

    private static void registerPartyName(
            Object manager,
            Object partyTeam
    ) throws IOException {
        if (!(partyTeam instanceof Team team)) {
            return;
        }

        Field field = findField(manager.getClass(), "nameMap");
        if (field == null) {
            return;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(manager);
            if (!(value instanceof Map<?, ?> map)) {
                return;
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.entrySet().removeIf(entry ->
                    team.getId().equals(entry.getValue())
            );
            mutable.put(
                    team.getShortName().toLowerCase(Locale.ROOT),
                    team.getId()
            );
        } catch (IllegalAccessException exception) {
            throw new IOException(
                    "Unable to register FTB Teams name",
                    exception
            );
        }
    }

    private static void syncToAll(
            Object manager,
            Collection<Object> teams
    ) throws IOException {
        List<Object> unique = new ArrayList<>(new LinkedHashSet<>(teams));
        if (unique.isEmpty()) {
            return;
        }

        for (Method method : allMethods(manager.getClass())) {
            if (!method.getName().equals("syncToAll")
                    || method.getParameterCount() != 1
                    || !method.getParameterTypes()[0].isArray()) {
                continue;
            }

            Class<?> componentType = method.getParameterTypes()[0]
                    .getComponentType();
            Object array = Array.newInstance(componentType, unique.size());
            for (int i = 0; i < unique.size(); i++) {
                if (!componentType.isInstance(unique.get(i))) {
                    return;
                }
                Array.set(array, i, unique.get(i));
            }

            try {
                method.setAccessible(true);
                method.invoke(manager, array);
                return;
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new IOException(
                        "Unable to synchronize FTB Teams state",
                        unwrap(exception)
                );
            }
        }
    }

    private static Object getFromMapField(
            Object owner,
            String fieldName,
            Object key
    ) throws IOException {
        Field field = findField(owner.getClass(), fieldName);
        if (field == null) {
            return null;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(owner);
            if (value instanceof Map<?, ?> map) {
                return map.get(key);
            }
            return null;
        } catch (IllegalAccessException exception) {
            throw new IOException(
                    "Unable to access FTB Teams map " + fieldName,
                    exception
            );
        }
    }

    private static void putIntoMapField(
            Object owner,
            String fieldName,
            Object key,
            Object value
    ) throws IOException {
        Field field = findField(owner.getClass(), fieldName);
        if (field == null) {
            throw new IOException(
                    "FTB Teams map is unavailable: " + fieldName
            );
        }

        try {
            field.setAccessible(true);
            Object mapValue = field.get(owner);
            if (!(mapValue instanceof Map<?, ?> map)) {
                throw new IOException(
                        "FTB Teams field is not a map: " + fieldName
                );
            }

            @SuppressWarnings("unchecked")
            Map<Object, Object> mutable = (Map<Object, Object>) map;
            mutable.put(key, value);
        } catch (IllegalAccessException exception) {
            throw new IOException(
                    "Unable to update FTB Teams map " + fieldName,
                    exception
            );
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object invokeRequired(
            Object target,
            String methodName,
            Object... arguments
    ) throws IOException {
        Method method = findCompatibleMethod(
                target.getClass(),
                methodName,
                arguments
        );
        if (method == null) {
            throw new IOException(
                    "FTB Teams method is unavailable: "
                            + target.getClass().getName()
                            + "."
                            + methodName
            );
        }

        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IOException(
                    "FTB Teams method failed: " + methodName,
                    unwrap(exception)
            );
        }
    }

    private static Object invokeOptional(
            Object target,
            String methodName,
            Object... arguments
    ) throws IOException {
        if (target == null) {
            return null;
        }
        Method method = findCompatibleMethod(
                target.getClass(),
                methodName,
                arguments
        );
        if (method == null) {
            return null;
        }

        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IOException(
                    "FTB Teams method failed: " + methodName,
                    unwrap(exception)
            );
        }
    }

    private static Method findCompatibleMethod(
            Class<?> type,
            String name,
            Object[] arguments
    ) {
        for (Method method : allMethods(type)) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != arguments.length) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                if (!isCompatible(parameterTypes[i], arguments[i])) {
                    compatible = false;
                    break;
                }
            }
            if (compatible) {
                return method;
            }
        }
        return null;
    }

    private static List<Method> allMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        Set<String> signatures = new LinkedHashSet<>();

        for (Method method : type.getMethods()) {
            String signature = method.toGenericString();
            if (signatures.add(signature)) {
                methods.add(method);
            }
        }

        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                String signature = method.toGenericString();
                if (signatures.add(signature)) {
                    methods.add(method);
                }
            }
            current = current.getSuperclass();
        }
        return methods;
    }

    private static boolean isCompatible(
            Class<?> parameterType,
            Object argument
    ) {
        if (argument == null) {
            return !parameterType.isPrimitive();
        }
        if (parameterType.isInstance(argument)) {
            return true;
        }
        if (!parameterType.isPrimitive()) {
            return false;
        }
        return (parameterType == boolean.class && argument instanceof Boolean)
                || (parameterType == byte.class && argument instanceof Byte)
                || (parameterType == short.class && argument instanceof Short)
                || (parameterType == int.class && argument instanceof Integer)
                || (parameterType == long.class && argument instanceof Long)
                || (parameterType == float.class && argument instanceof Float)
                || (parameterType == double.class && argument instanceof Double)
                || (parameterType == char.class && argument instanceof Character);
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional
                ? optional.orElse(null)
                : value;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }

    public record ApplyResult(
            boolean snapshotPresent,
            UUID activeTeamUuid,
            String scope,
            String teamName,
            int personalTeams
    ) {
    }
}
