package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class ClusterFtbChunksCodec {
    public static final String SCOPE_PLAYER = "PLAYER";
    public static final String SCOPE_TEAM = "TEAM";

    private static final String FTB_CHUNKS_API =
            "dev.ftb.mods.ftbchunks.api.FTBChunksAPI";
    private static final String FTB_TEAMS_API =
            "dev.ftb.mods.ftbteams.api.FTBTeamsAPI";
    private static final String CHUNK_DIM_POS =
            "dev.ftb.mods.ftblibrary.math.ChunkDimPos";
    private static final String FTB_CHUNKS_EXPECTED =
            "dev.ftb.mods.ftbchunks.FTBChunksExpected";
    private static final String FTB_CHUNKS_MOD_ID = "ftbchunks";

    private ClusterFtbChunksCodec() {
    }

    public static boolean isLoaded() {
        try {
            Class.forName(
                    FTB_CHUNKS_API,
                    false,
                    ClusterFtbChunksCodec.class.getClassLoader()
            );
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    public static Snapshot capture(
            MinecraftServer server
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Object manager = chunksManager();
        Map<UUID, Collection<?>> grouped = groupedClaims(manager);
        Map<ChunkKey, ClaimState> claims = new LinkedHashMap<>();
        long activeForceLoaded = 0L;

        for (Map.Entry<UUID, Collection<?>> entry : grouped.entrySet()) {
            UUID teamId = entry.getKey();
            TeamMetadata metadata = readTeamMetadata(teamId);
            for (Object claimedChunk : entry.getValue()) {
                Object position = claimPosition(claimedChunk);
                ChunkKey key = readChunkKey(position);
                boolean forceLoaded = booleanValue(
                        invokeRequired(claimedChunk, "isForceLoaded")
                );
                if (booleanValue(
                        invokeRequired(
                                manager,
                                "isChunkForceLoaded",
                                position
                        )
                )) {
                    activeForceLoaded++;
                }
                claims.put(
                        key,
                        new ClaimState(
                                key.dimensionId(),
                                key.chunkX(),
                                key.chunkZ(),
                                teamId,
                                metadata.scope(),
                                metadata.name(),
                                true,
                                forceLoaded
                        )
                );
            }
        }

        List<ClaimState> sorted = new ArrayList<>(claims.values());
        sorted.sort(
                Comparator.comparing(ClaimState::dimensionId)
                        .thenComparingInt(ClaimState::chunkX)
                        .thenComparingInt(ClaimState::chunkZ)
        );
        return new Snapshot(List.copyOf(sorted), activeForceLoaded);
    }

    public static void ensureClaim(
            MinecraftServer server,
            ClaimState desired
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(desired, "desired");

        Object manager = chunksManager();
        Object position = createChunkPosition(desired.key());
        Object existing = invokeRequired(manager, "getChunk", position);
        if (existing != null) {
            UUID existingTeam = claimTeamId(manager, existing, position);
            if (desired.teamUuid().equals(existingTeam)) {
                return;
            }
            if (existingTeam != null) {
                unclaim(
                        server,
                        new ClaimState(
                                desired.dimensionId(),
                                desired.chunkX(),
                                desired.chunkZ(),
                                existingTeam,
                                SCOPE_TEAM,
                                "",
                                true,
                                booleanValue(invokeRequired(
                                        existing,
                                        "isForceLoaded"
                                ))
                        )
                );
            }
        }

        Object team = ensureTeam(
                desired.teamUuid(),
                desired.teamScope(),
                desired.teamName()
        );
        Object teamData = invokeRequired(manager, "getOrCreateData", team);
        CommandSourceStack source = adminSource(server);
        invokeMutation(teamData, "claim", source, position);

        Object applied = invokeRequired(manager, "getChunk", position);
        if (applied == null) {
            throw new IOException(
                    "FTB Chunks did not create claim for " + desired.key()
            );
        }
        UUID appliedTeam = claimTeamId(manager, applied, position);
        if (appliedTeam != null && !desired.teamUuid().equals(appliedTeam)) {
            throw new IOException(
                    "FTB Chunks claim owner mismatch for "
                            + desired.key()
                            + ": expected="
                            + desired.teamUuid()
                            + ", actual="
                            + appliedTeam
            );
        }
    }

    public static void unclaim(
            MinecraftServer server,
            ClaimState current
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(current, "current");

        Object manager = chunksManager();
        Object position = createChunkPosition(current.key());
        Object existing = invokeRequired(manager, "getChunk", position);
        if (existing == null) {
            return;
        }

        UUID teamId = claimTeamId(manager, existing, position);
        if (teamId == null) {
            teamId = current.teamUuid();
        }
        TeamMetadata metadata = readTeamMetadata(teamId);
        Object team = ensureTeam(teamId, metadata.scope(), metadata.name());
        Object teamData = invokeRequired(manager, "getOrCreateData", team);
        invokeMutation(
                teamData,
                "unclaim",
                adminSource(server),
                position
        );

        if (invokeRequired(manager, "getChunk", position) != null) {
            throw new IOException(
                    "FTB Chunks did not remove claim for " + current.key()
            );
        }
    }

    public static void setForceLoaded(
            MinecraftServer server,
            ClaimState claim,
            boolean requestedForceLoaded,
            boolean physicalForceLoaded
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(claim, "claim");

        Object manager = chunksManager();
        Object position = createChunkPosition(claim.key());
        Object existing = invokeRequired(manager, "getChunk", position);
        if (existing == null) {
            if (!requestedForceLoaded) {
                setPhysicalTicket(server, claim, false);
                return;
            }
            ensureClaim(server, claim);
            existing = invokeRequired(manager, "getChunk", position);
        }
        if (existing == null) {
            throw new IOException(
                    "FTB Chunks claim is missing for " + claim.key()
            );
        }

        boolean currentRequested = booleanValue(
                invokeRequired(existing, "isForceLoaded")
        );
        Object teamData = readMember(
                existing,
                "getTeamData",
                "teamData"
        );
        if (teamData == null) {
            Object team = ensureTeam(
                    claim.teamUuid(),
                    claim.teamScope(),
                    claim.teamName()
            );
            teamData = invokeRequired(manager, "getOrCreateData", team);
        }

        if (currentRequested != requestedForceLoaded) {
            invokeRequired(
                    existing,
                    "setForceLoadedTime",
                    requestedForceLoaded ? System.currentTimeMillis() : 0L
            );
            invokeOptional(teamData, "markDirty");
        }

        setPhysicalTicket(
                server,
                claim,
                requestedForceLoaded && physicalForceLoaded
        );
        invokeOptional(manager, "clearForceLoadedCache");

        boolean appliedRequested = booleanValue(
                invokeRequired(existing, "isForceLoaded")
        );
        if (appliedRequested != requestedForceLoaded) {
            throw new IOException(
                    "FTB Chunks requested force-load state mismatch for "
                            + claim.key()
                            + ": expected="
                            + requestedForceLoaded
                            + ", actual="
                            + appliedRequested
            );
        }
    }

    public static void syncClients(
            MinecraftServer server
    ) throws IOException {
        Objects.requireNonNull(server, "server");
        Object manager = chunksManager();
        Set<Object> teamDataSet = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        for (Collection<?> claims : groupedClaims(manager).values()) {
            for (Object claim : claims) {
                Object teamData = readMember(
                        claim,
                        "getTeamData",
                        "teamData"
                );
                if (teamData != null) {
                    teamDataSet.add(teamData);
                }
            }
        }
        for (Object teamData : teamDataSet) {
            invokeOptional(teamData, "syncChunksToAll", server);
        }
    }

    private static void setPhysicalTicket(
            MinecraftServer server,
            ClaimState claim,
            boolean loaded
    ) throws IOException {
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                new ResourceLocation(claim.dimensionId())
        );
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            if (loaded) {
                throw new IOException(
                        "FTB Chunks cannot force-load unavailable dimension "
                                + claim.dimensionId()
                );
            }
            return;
        }

        if (loaded) {
            level.getChunk(claim.chunkX(), claim.chunkZ());
        }

        try {
            Class<?> expected = Class.forName(
                    FTB_CHUNKS_EXPECTED,
                    true,
                    ClusterFtbChunksCodec.class.getClassLoader()
            );
            invokeStaticRequired(
                    expected,
                    "addChunkToForceLoaded",
                    level,
                    FTB_CHUNKS_MOD_ID,
                    claim.teamUuid(),
                    claim.chunkX(),
                    claim.chunkZ(),
                    loaded
            );
        } catch (ClassNotFoundException exception) {
            throw new IOException(
                    "FTB Chunks platform force-load bridge is unavailable",
                    exception
            );
        }
    }

    private static Object chunksManager() throws IOException {
        try {
            Class<?> apiType = Class.forName(
                    FTB_CHUNKS_API,
                    true,
                    ClusterFtbChunksCodec.class.getClassLoader()
            );
            Object api = invokeStaticRequired(apiType, "api");
            Object loaded = invokeOptional(api, "isManagerLoaded");
            if (loaded instanceof Boolean value && !value) {
                throw new IOException("FTB Chunks manager is not loaded yet");
            }
            return invokeRequired(api, "getManager");
        } catch (ClassNotFoundException exception) {
            throw new IOException("FTB Chunks is not installed", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Collection<?>> groupedClaims(
            Object manager
    ) throws IOException {
        Predicate<Object> all = ignored -> true;
        Object result = invokeRequired(
                manager,
                "getClaimedChunksByTeam",
                all
        );
        if (!(result instanceof Map<?, ?> map)) {
            throw new IOException(
                    "FTB Chunks getClaimedChunksByTeam returned "
                            + typeName(result)
            );
        }

        Map<UUID, Collection<?>> grouped = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            UUID teamId = uuidValue(entry.getKey());
            if (teamId == null) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Collection<?> collection) {
                grouped.put(teamId, (Collection<?>) collection);
            }
        }
        return grouped;
    }

    private static Object claimPosition(
            Object claim
    ) throws IOException {
        Object value = readMember(
                claim,
                "getPos",
                "getPosition",
                "pos",
                "position"
        );
        if (value == null) {
            throw new IOException(
                    "Unable to read FTB Chunks position from "
                            + claim.getClass().getName()
            );
        }
        return value;
    }

    private static ChunkKey readChunkKey(
            Object position
    ) throws IOException {
        Object dimension = readMember(
                position,
                "dimension",
                "getDimension",
                "dimensionKey",
                "getDimensionKey"
        );
        String dimensionId = dimensionId(dimension);
        int x = intValue(readMember(
                position,
                "x",
                "getX",
                "chunkX",
                "getChunkX"
        ));
        int z = intValue(readMember(
                position,
                "z",
                "getZ",
                "chunkZ",
                "getChunkZ"
        ));
        return new ChunkKey(dimensionId, x, z);
    }

    private static Object createChunkPosition(
            ChunkKey key
    ) throws IOException {
        ResourceKey<Level> dimension = ResourceKey.create(
                Registries.DIMENSION,
                new ResourceLocation(key.dimensionId())
        );
        try {
            Class<?> type = Class.forName(
                    CHUNK_DIM_POS,
                    true,
                    ClusterFtbChunksCodec.class.getClassLoader()
            );
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length == 3
                        && parameterTypes[0].isAssignableFrom(dimension.getClass())
                        && isInt(parameterTypes[1])
                        && isInt(parameterTypes[2])) {
                    constructor.setAccessible(true);
                    return constructor.newInstance(
                            dimension,
                            key.chunkX(),
                            key.chunkZ()
                    );
                }
            }
            throw new IOException(
                    "No compatible ChunkDimPos constructor was found"
            );
        } catch (ClassNotFoundException
                 | InstantiationException
                 | IllegalAccessException
                 | InvocationTargetException exception) {
            throw new IOException(
                    "Unable to create FTB Chunks position " + key,
                    unwrap(exception)
            );
        }
    }

    private static UUID claimTeamId(
            Object manager,
            Object claim,
            Object position
    ) throws IOException {
        Object direct = readMember(
                claim,
                "getTeamId",
                "getTeamID",
                "teamId",
                "teamID"
        );
        UUID directUuid = uuidValue(direct);
        if (directUuid != null) {
            return directUuid;
        }

        Object teamData = readMember(
                claim,
                "getTeamData",
                "teamData"
        );
        if (teamData != null) {
            UUID fromData = uuidValue(readMember(
                    teamData,
                    "getTeamId",
                    "getTeamID",
                    "getId",
                    "teamId"
            ));
            if (fromData != null) {
                return fromData;
            }
            Object team = readMember(teamData, "getTeam", "team");
            UUID fromTeam = team == null
                    ? null
                    : uuidValue(readMember(team, "getId", "id"));
            if (fromTeam != null) {
                return fromTeam;
            }
        }

        Map<UUID, Collection<?>> grouped = groupedClaims(manager);
        for (Map.Entry<UUID, Collection<?>> entry : grouped.entrySet()) {
            for (Object item : entry.getValue()) {
                if (item == claim) {
                    return entry.getKey();
                }
                Object itemPosition = claimPosition(item);
                if (itemPosition.equals(position)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private static TeamMetadata readTeamMetadata(
            UUID teamId
    ) throws IOException {
        Object team = findTeam(teamId);
        if (team == null) {
            return new TeamMetadata(SCOPE_TEAM, "");
        }
        Object playerTeam = invokeOptional(team, "isPlayerTeam");
        String scope = playerTeam instanceof Boolean value && value
                ? SCOPE_PLAYER
                : SCOPE_TEAM;
        Object shortName = invokeOptional(team, "getShortName");
        String name = shortName == null ? "" : shortName.toString();
        return new TeamMetadata(scope, name);
    }

    private static Object findTeam(
            UUID teamId
    ) throws IOException {
        Object manager = teamsManager();
        Object result = invokeOptional(manager, "getTeamByID", teamId);
        result = unwrapOptional(result);
        if (result != null) {
            return result;
        }
        result = invokeOptional(manager, "getTeamById", teamId);
        result = unwrapOptional(result);
        if (result != null) {
            return result;
        }
        result = getFromMapField(manager, "teamMap", teamId);
        if (result != null) {
            return result;
        }
        return getFromMapField(manager, "knownPlayers", teamId);
    }

    private static Object ensureTeam(
            UUID teamId,
            String scope,
            String name
    ) throws IOException {
        Object manager = teamsManager();
        Object existing = findTeam(teamId);
        if (existing != null) {
            return existing;
        }

        boolean playerScope = SCOPE_PLAYER.equalsIgnoreCase(scope);
        String className = playerScope
                ? "dev.ftb.mods.ftbteams.data.PlayerTeam"
                : "dev.ftb.mods.ftbteams.data.PartyTeam";
        String mapName = playerScope ? "knownPlayers" : "teamMap";
        Object team = instantiateTeam(className, manager, teamId);
        putIntoMapField(manager, mapName, teamId, team);
        if (name != null && !name.isBlank()) {
            invokeAnyOptional(
                    team,
                    List.of("setName", "setShortName"),
                    name
            );
        }
        invokeOptional(team, "markDirty");
        invokeOptional(manager, "markDirty");
        invokeOptional(manager, "saveNow");
        return team;
    }

    private static Object teamsManager() throws IOException {
        try {
            Class<?> apiType = Class.forName(
                    FTB_TEAMS_API,
                    true,
                    ClusterFtbChunksCodec.class.getClassLoader()
            );
            Object api = invokeStaticRequired(apiType, "api");
            return invokeRequired(api, "getManager");
        } catch (ClassNotFoundException exception) {
            throw new IOException("FTB Teams is not installed", exception);
        }
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
                    ClusterFtbChunksCodec.class.getClassLoader()
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

    @SuppressWarnings("unchecked")
    private static Object getFromMapField(
            Object target,
            String fieldName,
            UUID id
    ) throws IOException {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            Object value = field.get(target);
            if (value instanceof Map<?, ?> map) {
                return ((Map<UUID, Object>) map).get(id);
            }
            return null;
        } catch (IllegalAccessException exception) {
            throw new IOException(
                    "Unable to read FTB Teams map " + fieldName,
                    exception
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static void putIntoMapField(
            Object target,
            String fieldName,
            UUID id,
            Object value
    ) throws IOException {
        Field field = findField(target.getClass(), fieldName);
        if (field == null) {
            throw new IOException(
                    "FTB Teams map field not found: " + fieldName
            );
        }
        try {
            field.setAccessible(true);
            Object mapValue = field.get(target);
            if (!(mapValue instanceof Map<?, ?> map)) {
                throw new IOException(
                        "FTB Teams field is not a map: " + fieldName
                );
            }
            ((Map<UUID, Object>) map).put(id, value);
        } catch (IllegalAccessException exception) {
            throw new IOException(
                    "Unable to update FTB Teams map " + fieldName,
                    exception
            );
        }
    }

    private static void invokeMutation(
            Object target,
            String method,
            CommandSourceStack source,
            Object position
    ) throws IOException {
        InvocationResult result = invokeIfPresent(
                target,
                method,
                source,
                position,
                false
        );
        if (!result.found()) {
            result = invokeIfPresent(target, method, source, position);
        }
        if (!result.found()) {
            result = invokeIfPresent(target, method, position, false);
        }
        if (!result.found()) {
            result = invokeIfPresent(target, method, position);
        }
        if (!result.found() && "unforceLoad".equals(method)) {
            result = invokeIfPresent(
                    target,
                    "unForceLoad",
                    source,
                    position,
                    false
            );
        }
        if (!result.found()) {
            throw new IOException(
                    "FTB Chunks method not found: "
                            + target.getClass().getName()
                            + "."
                            + method
            );
        }
    }

    private static CommandSourceStack adminSource(
            MinecraftServer server
    ) {
        return server.createCommandSourceStack()
                .withPermission(4)
                .withSuppressedOutput();
    }

    private static String dimensionId(
            Object value
    ) throws IOException {
        if (value instanceof ResourceKey<?> key) {
            return key.location().toString();
        }
        if (value instanceof ResourceLocation location) {
            return location.toString();
        }
        if (value != null) {
            Object location = invokeOptional(value, "location");
            if (location == null) {
                location = invokeOptional(value, "getLocation");
            }
            if (location != null) {
                return location.toString();
            }
        }
        throw new IOException(
                "Unable to read dimension from " + typeName(value)
        );
    }

    private static Object readMember(
            Object target,
            String... names
    ) throws IOException {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            InvocationResult method = invokeIfPresent(target, name);
            if (method.found()) {
                return method.value();
            }
            Field field = findField(target.getClass(), name);
            if (field != null) {
                try {
                    field.setAccessible(true);
                    return field.get(target);
                } catch (IllegalAccessException exception) {
                    throw new IOException(
                            "Unable to read field "
                                    + target.getClass().getName()
                                    + "."
                                    + name,
                            exception
                    );
                }
            }
        }
        return null;
    }

    private static Field findField(
            Class<?> type,
            String name
    ) {
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static Object invokeStaticRequired(
            Class<?> type,
            String method,
            Object... arguments
    ) throws IOException {
        Method found = findMethod(type, method, true, arguments);
        if (found == null) {
            throw new IOException(
                    "Method not found: " + type.getName() + "." + method
            );
        }
        return invokeMethod(null, found, arguments);
    }

    private static Object invokeRequired(
            Object target,
            String method,
            Object... arguments
    ) throws IOException {
        InvocationResult result = invokeIfPresent(target, method, arguments);
        if (!result.found()) {
            throw new IOException(
                    "Method not found: "
                            + target.getClass().getName()
                            + "."
                            + method
            );
        }
        return result.value();
    }

    private static Object invokeOptional(
            Object target,
            String method,
            Object... arguments
    ) throws IOException {
        InvocationResult result = invokeIfPresent(target, method, arguments);
        return result.found() ? result.value() : null;
    }

    private static Object invokeAnyOptional(
            Object target,
            List<String> methods,
            Object... arguments
    ) throws IOException {
        for (String method : methods) {
            InvocationResult result = invokeIfPresent(
                    target,
                    method,
                    arguments
            );
            if (result.found()) {
                return result.value();
            }
        }
        return null;
    }

    private static InvocationResult invokeIfPresent(
            Object target,
            String method,
            Object... arguments
    ) throws IOException {
        if (target == null) {
            return new InvocationResult(false, null);
        }
        Method found = findMethod(
                target.getClass(),
                method,
                false,
                arguments
        );
        if (found == null) {
            return new InvocationResult(false, null);
        }
        return new InvocationResult(
                true,
                invokeMethod(target, found, arguments)
        );
    }

    private static Method findMethod(
            Class<?> type,
            String name,
            boolean requireStatic,
            Object[] arguments
    ) {
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)
                        || (requireStatic
                        && !Modifier.isStatic(method.getModifiers()))
                        || !compatible(method.getParameterTypes(), arguments)) {
                    continue;
                }
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && (!requireStatic
                    || Modifier.isStatic(method.getModifiers()))
                    && compatible(method.getParameterTypes(), arguments)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static boolean compatible(
            Class<?>[] parameterTypes,
            Object[] arguments
    ) {
        if (parameterTypes.length != arguments.length) {
            return false;
        }
        for (int i = 0; i < parameterTypes.length; i++) {
            Object argument = arguments[i];
            if (argument == null) {
                if (parameterTypes[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> parameter = wrap(parameterTypes[i]);
            if (!parameter.isAssignableFrom(argument.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Object invokeMethod(
            Object target,
            Method method,
            Object[] arguments
    ) throws IOException {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException | InvocationTargetException exception) {
            throw new IOException(
                    "Unable to invoke "
                            + method.getDeclaringClass().getName()
                            + "."
                            + method.getName(),
                    unwrap(exception)
            );
        }
    }

    private static Class<?> wrap(
            Class<?> type
    ) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static boolean isInt(
            Class<?> type
    ) {
        return type == int.class || type == Integer.class;
    }

    private static int intValue(
            Object value
    ) throws IOException {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IOException(
                "Expected integer, got " + typeName(value)
        );
    }

    private static boolean booleanValue(
            Object value
    ) throws IOException {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new IOException(
                "Expected boolean, got " + typeName(value)
        );
    }

    private static UUID uuidValue(
            Object value
    ) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value != null) {
            try {
                return UUID.fromString(value.toString());
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static Object unwrapOptional(
            Object value
    ) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private static Throwable unwrap(
            Throwable throwable
    ) {
        Throwable current = throwable;
        while ((current instanceof InvocationTargetException
                || current instanceof ExceptionInInitializerError)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String typeName(
            Object value
    ) {
        return value == null ? "null" : value.getClass().getName();
    }

    public record ChunkKey(
            String dimensionId,
            int chunkX,
            int chunkZ
    ) {
        public ChunkKey {
            dimensionId = dimensionId == null
                    ? ""
                    : dimensionId.trim().toLowerCase(Locale.ROOT);
            if (dimensionId.isEmpty()) {
                throw new IllegalArgumentException("dimensionId is empty");
            }
        }
    }

    public record ClaimState(
            String dimensionId,
            int chunkX,
            int chunkZ,
            UUID teamUuid,
            String teamScope,
            String teamName,
            boolean claimed,
            boolean forceLoaded
    ) {
        public ClaimState {
            dimensionId = dimensionId == null
                    ? ""
                    : dimensionId.trim().toLowerCase(Locale.ROOT);
            teamUuid = Objects.requireNonNull(teamUuid, "teamUuid");
            teamScope = SCOPE_PLAYER.equalsIgnoreCase(teamScope)
                    ? SCOPE_PLAYER
                    : SCOPE_TEAM;
            teamName = teamName == null ? "" : teamName;
        }

        public ChunkKey key() {
            return new ChunkKey(dimensionId, chunkX, chunkZ);
        }
    }

    public record Snapshot(
            List<ClaimState> claims,
            long activeForceLoadedCount
    ) {
        public Snapshot {
            claims = List.copyOf(claims);
            activeForceLoadedCount = Math.max(0L, activeForceLoadedCount);
        }

        public Map<ChunkKey, ClaimState> byKey() {
            Map<ChunkKey, ClaimState> result = new LinkedHashMap<>();
            for (ClaimState claim : claims) {
                result.put(claim.key(), claim);
            }
            return result;
        }

        public long forceLoadedCount() {
            return claims.stream()
                    .filter(ClaimState::forceLoaded)
                    .count();
        }
    }

    private record TeamMetadata(
            String scope,
            String name
    ) {
    }

    private record InvocationResult(
            boolean found,
            Object value
    ) {
    }
}
