package Crazer.cubeofinterest.cointcoregto.bosssim;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public final class ApotheosisBossSimulator {
    private static final String APOTHEOSIS_MOD_ID = "apotheosis";

    private static volatile Bridge cachedBridge;

    private ApotheosisBossSimulator() {}

    public record SimulationResult(
            ResourceLocation bossDefinition,
            ResourceLocation entityType,
            String rarity,
            List<ItemStack> outputs
    ) {
        public SimulationResult {
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
        }
    }

    public static boolean isAvailable() {
        return ModList.get().isLoaded(APOTHEOSIS_MOD_ID);
    }

    public static SimulationResult simulate(ServerLevel simulatedLevel, ServerPlayer contextPlayer, BlockPos pos)
            throws ReflectiveOperationException {
        if (contextPlayer == null) {
            throw new IllegalArgumentException("contextPlayer cannot be null");
        }

        Bridge bridge = bridgeChecked(simulatedLevel);
        float luck = contextPlayer.getLuck();
        Object dimensionPredicate = bridge.dimensionMatches.invoke(null, simulatedLevel);
        Object stagePredicate = bridge.stageMatches.invoke(null, contextPlayer);

        return simulateInternal(
                bridge,
                simulatedLevel,
                pos,
                contextPlayer.getRandom(),
                luck,
                new Object[]{dimensionPredicate, stagePredicate}
        );
    }

    public static SimulationResult simulateMachine(ServerLevel simulatedLevel, float luck, BlockPos pos)
            throws ReflectiveOperationException {
        Bridge bridge = bridgeChecked(simulatedLevel);
        Object dimensionPredicate = bridge.dimensionMatches.invoke(null, simulatedLevel);

        return simulateInternal(
                bridge,
                simulatedLevel,
                pos,
                simulatedLevel.getRandom(),
                luck,
                new Object[]{dimensionPredicate}
        );
    }

    private static SimulationResult simulateInternal(
            Bridge bridge,
            ServerLevel simulatedLevel,
            BlockPos pos,
            RandomSource random,
            float luck,
            Object[] predicates
    ) throws ReflectiveOperationException {
        Object predicateArray = predicateArray(predicates);
        Object bossDefinition = bridge.getRandomBoss.invoke(
                bridge.bossRegistry,
                new Object[]{random, luck, predicateArray}
        );

        if (bossDefinition == null) {
            throw new NoEligibleBossException(
                    "Apotheosis has no eligible boss definitions for dimension "
                            + simulatedLevel.dimension().location()
            );
        }

        Method createBoss = findCreateBossMethod(bossDefinition.getClass());
        Mob root = (Mob) createBoss.invoke(bossDefinition, simulatedLevel, pos, random, luck);
        if (root == null) {
            throw new IllegalStateException("Apotheosis returned null from ApothBoss#createBoss");
        }

        try {
            Mob actualBoss = root.getSelfAndPassengers()
                    .filter(Mob.class::isInstance)
                    .map(Mob.class::cast)
                    .filter(entity -> entity.getPersistentData().getBoolean("apoth.boss"))
                    .findFirst()
                    .orElse(root);

            ItemStack guaranteedAffix = findGuaranteedAffixItem(actualBoss);
            if (guaranteedAffix.isEmpty()) {
                throw new IllegalStateException(
                        "Virtual Apotheosis boss was generated without an apoth_boss equipment item"
                );
            }

            List<ItemStack> outputs = new ArrayList<>(2);
            outputs.add(guaranteedAffix.copy());

            if (actualBoss instanceof Monster) {
                ItemStack gem = bridge.rollBossGem(random, simulatedLevel, luck, predicates);
                if (!gem.isEmpty()) {
                    outputs.add(gem.copy());
                }
            }

            ResourceLocation bossKey = bridge.getBossKey(bossDefinition);
            ResourceLocation entityType = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES
                    .getKey(actualBoss.getType());
            String rarity = actualBoss.getPersistentData().getString("apoth.rarity");

            return new SimulationResult(bossKey, entityType, rarity, outputs);
        } finally {

            root.getSelfAndPassengers().forEach(Entity::discard);
        }
    }

    private static Bridge bridgeChecked(ServerLevel simulatedLevel) throws ReflectiveOperationException {
        if (simulatedLevel == null) {
            throw new IllegalArgumentException("simulatedLevel cannot be null");
        }
        if (!isAvailable()) {
            throw new IllegalStateException("Apotheosis is not loaded");
        }
        return bridge();
    }

    private static Object predicateArray(Object[] predicates) {
        Object array = Array.newInstance(Predicate.class, predicates.length);
        for (int i = 0; i < predicates.length; i++) {
            Array.set(array, i, predicates[i]);
        }
        return array;
    }

    private static ItemStack findGuaranteedAffixItem(Mob boss) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = boss.getItemBySlot(slot);
            if (!stack.isEmpty() && stack.hasTag() && stack.getTag().getBoolean("apoth_boss")) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static Method findCreateBossMethod(Class<?> bossClass) {
        for (Method method : bossClass.getMethods()) {
            if (!method.getName().equals("createBoss") || method.getParameterCount() != 4) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (BlockPos.class.isAssignableFrom(params[1])
                    && RandomSource.class.isAssignableFrom(params[2])
                    && (params[3] == float.class || params[3] == Float.class)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(
                "Unable to locate Apotheosis ApothBoss#createBoss(level,pos,random,luck)"
        );
    }

    private static Bridge bridge() throws ReflectiveOperationException {
        Bridge local = cachedBridge;
        if (local != null) return local;

        synchronized (ApotheosisBossSimulator.class) {
            local = cachedBridge;
            if (local == null) {
                local = new Bridge();
                cachedBridge = local;
            }
        }
        return local;
    }

    public static final class NoEligibleBossException extends IllegalStateException {
        public NoEligibleBossException(String message) {
            super(message);
        }
    }

    private static final class Bridge {
        private final Object bossRegistry;
        private final Method getRandomBoss;
        private final Method getBossKey;
        private final Method dimensionMatches;
        private final Method stageMatches;

        private final Field gemDropChance;
        private final Field gemBossBonus;
        private final Method createRandomGemStack;

        private Bridge() throws ReflectiveOperationException {
            Class<?> bossRegistryClass = Class.forName(
                    "dev.shadowsoffire.apotheosis.adventure.boss.BossRegistry"
            );
            this.bossRegistry = readStaticField(bossRegistryClass, "INSTANCE");
            this.getRandomBoss = findRandomItemMethod(bossRegistryClass);
            this.getBossKey = findOptionalMethod(bossRegistryClass, "getKey", 1);

            Class<?> dimensionalClass = Class.forName(
                    "dev.shadowsoffire.placebo.reload.WeightedDynamicRegistry$IDimensional"
            );
            this.dimensionMatches = findStaticMethod(dimensionalClass, "matches", 1);

            Class<?> stagedClass = Class.forName(
                    "dev.shadowsoffire.apotheosis.adventure.compat.GameStagesCompat$IStaged"
            );
            this.stageMatches = findStaticMethod(stagedClass, "matches", 1);

            Class<?> adventureConfigClass = Class.forName(
                    "dev.shadowsoffire.apotheosis.adventure.AdventureConfig"
            );
            this.gemDropChance = findField(adventureConfigClass, "gemDropChance");
            this.gemBossBonus = findField(adventureConfigClass, "gemBossBonus");

            Class<?> gemRegistryClass = Class.forName(
                    "dev.shadowsoffire.apotheosis.adventure.socket.gem.GemRegistry"
            );
            this.createRandomGemStack = findGemFactoryMethod(gemRegistryClass);
        }

        private ResourceLocation getBossKey(Object bossDefinition) {
            if (getBossKey == null) return null;
            try {
                Object key = getBossKey.invoke(bossRegistry, bossDefinition);
                return key instanceof ResourceLocation id ? id : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private ItemStack rollBossGem(
                RandomSource random,
                ServerLevel level,
                float luck,
                Object[] predicates
        ) throws ReflectiveOperationException {
            float chance = readFloat(gemDropChance) + readFloat(gemBossBonus);
            if (chance <= 0.0F || random.nextFloat() > chance) {
                return ItemStack.EMPTY;
            }

            Object result = createRandomGemStack.invoke(
                    null,
                    new Object[]{random, level, luck, predicateArray(predicates)}
            );
            return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        }
    }

    private static Method findGemFactoryMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (isGemFactory(method)) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (isGemFactory(method)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(
                "Unable to locate Apotheosis GemRegistry#createRandomGemStack(RandomSource,ServerLevel,float,Predicate[])"
        );
    }

    private static boolean isGemFactory(Method method) {
        if (!method.getName().equals("createRandomGemStack")
                || method.getParameterCount() != 4
                || !Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return RandomSource.class.isAssignableFrom(params[0])
                && ServerLevel.class.isAssignableFrom(params[1])
                && (params[2] == float.class || params[2] == Float.class)
                && params[3].isArray()
                && Predicate.class.isAssignableFrom(params[3].getComponentType());
    }

    private static Method findRandomItemMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals("getRandomItem") || method.getParameterCount() != 3) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (RandomSource.class.isAssignableFrom(params[0])
                    && (params[1] == float.class || params[1] == Float.class)
                    && params[2].isArray()) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Unable to locate Placebo WeightedDynamicRegistry#getRandomItem");
    }

    private static Method findStaticMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount
                    && Modifier.isStatic(method.getModifiers())) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("Unable to locate static method " + type.getName() + "#" + name);
    }

    private static Method findOptionalMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "#" + name);
    }

    private static Object readStaticField(Class<?> type, String name) throws ReflectiveOperationException {
        return findField(type, name).get(null);
    }

    private static float readFloat(Field field) throws IllegalAccessException {
        Object value = field.get(null);
        return value instanceof Number number ? number.floatValue() : 0.0F;
    }
}
