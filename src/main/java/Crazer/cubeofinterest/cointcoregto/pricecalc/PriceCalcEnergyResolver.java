package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorGtoSyncState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.emi.emi.api.recipe.EmiRecipe;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class PriceCalcEnergyResolver {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:PriceCalcEnergy");
    private static final String PREFIX = "server_sync/gto/";
    private static final String GT_RECIPE_CLASS = "com.gregtechceu.gtceu.api.recipe.GTRecipe";
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private PriceCalcEnergyResolver() {
    }

    public static double energyCost(EmiRecipe recipe, double pricePerEu) {
        if (recipe == null || !(pricePerEu > 0.0D) || !Double.isFinite(pricePerEu)) {
            return 0.0D;
        }

        // CointCoreGTO's synced GT/GTO JSON is authoritative for its own recipes.
        // Read it first so a partially-readable native GTRecipe cannot suppress the fallback.
        Double syncedCost = syncedJsonEnergyCost(recipe, pricePerEu);
        if (syncedCost != null) {
            return syncedCost;
        }

        Double nativeCost = nativeGtEnergyCost(recipe, pricePerEu);
        if (nativeCost != null) {
            return nativeCost;
        }

        logUnresolvedGtRecipeOnce(recipe);
        return 0.0D;
    }

    private static Double nativeGtEnergyCost(EmiRecipe recipe, double pricePerEu) {
        Object gtRecipe = findNativeGtRecipe(recipe);
        if (gtRecipe == null) {
            return null;
        }

        Long duration = readLong(
                gtRecipe,
                new String[]{"getDuration", "duration"},
                new String[]{"duration"}
        );
        if (duration == null || duration <= 0L) {
            // Unknown is not the same as zero. Let later fallbacks run.
            return null;
        }

        Long eut = readInputEUt(gtRecipe);
        if (eut == null) {
            // Unknown is not the same as zero. Let later fallbacks run.
            return null;
        }
        if (eut == 0L) {
            return 0.0D;
        }

        double cost = Math.abs((double) eut) * duration * pricePerEu;
        if (!Double.isFinite(cost) || cost < 0.0D) {
            return null;
        }
        logResolvedOnce(recipe, "native", duration, eut, pricePerEu, cost);
        return cost;
    }

    private static Object findNativeGtRecipe(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        Object registered = findRegisteredGtRecipe(id);
        if (isGtRecipe(registered)) {
            return registered;
        }

        for (String methodName : new String[]{"getBackingRecipe", "getRecipe", "recipe"}) {
            Object backing = invokeNoArg(recipe, methodName);
            Object unwrapped = unwrapOptional(backing);
            if (isGtRecipe(unwrapped)) {
                return unwrapped;
            }
        }

        // GTEmiRecipe is constructed with the GTRecipe instance. Do not depend on
        // a particular field name or declared field type; scan the complete hierarchy.
        for (Class<?> type = recipe.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (!field.canAccess(recipe) && !field.trySetAccessible()) {
                        continue;
                    }
                    Object value = unwrapOptional(field.get(recipe));
                    if (isGtRecipe(value)) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }
        return value;
    }

    private static Object findRegisteredGtRecipe(ResourceLocation id) {
        if (id == null) {
            return null;
        }
        try {
            ClassLoader loader = PriceCalcEnergyResolver.class.getClassLoader();
            Class<?> gtRegistriesClass = Class.forName(
                    "com.gregtechceu.gtceu.api.registry.GTRegistries",
                    false,
                    loader
            );
            Field recipeTypesField = gtRegistriesClass.getField("RECIPE_TYPES");
            Object recipeTypes = recipeTypesField.get(null);
            if (recipeTypes == null) {
                return null;
            }

            // GTCEu 26.x removed GTRegistry.registry(), but both 1.8.0 and 26.x
            // expose values(). Prefer the common API and only fall back to the
            // legacy Map accessor if necessary.
            Object valuesObject;
            try {
                valuesObject = invokeNoArg(recipeTypes, "values");
            } catch (Throwable noValuesMethod) {
                Object registryObject = invokeNoArg(recipeTypes, "registry");
                if (!(registryObject instanceof Map<?, ?> registryMap)) {
                    return null;
                }
                valuesObject = registryMap.values();
            }
            if (!(valuesObject instanceof Iterable<?> recipeTypesIterable)) {
                return null;
            }

            for (Object recipeType : recipeTypesIterable) {
                if (recipeType == null) {
                    continue;
                }
                Object recipesObject = readPublicField(recipeType, "recipes");
                if (!(recipesObject instanceof Map<?, ?> recipes)) {
                    continue;
                }

                Object direct = recipes.get(id);
                if (isGtRecipe(direct)) {
                    return direct;
                }

                for (Map.Entry<?, ?> entry : recipes.entrySet()) {
                    Object value = entry.getValue();
                    if (!isGtRecipe(value)) {
                        continue;
                    }
                    if (resourceLocationEquals(entry.getKey(), id)) {
                        return value;
                    }
                    ResourceLocation recipeId = readRecipeId(value);
                    if (idsEquivalent(id, recipeId)) {
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object readPublicField(Object owner, String fieldName) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getClass().getField(fieldName).get(owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ResourceLocation readRecipeId(Object recipe) {
        Object getterValue = invokeNoArg(recipe, "getId");
        if (getterValue instanceof ResourceLocation id) {
            return id;
        }
        Object fieldValue = readPublicField(recipe, "id");
        return fieldValue instanceof ResourceLocation id ? id : null;
    }

    private static boolean resourceLocationEquals(Object value, ResourceLocation id) {
        if (value instanceof ResourceLocation resourceLocation) {
            return idsEquivalent(id, resourceLocation);
        }
        if (value == null) {
            return false;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(value.toString());
        return idsEquivalent(id, parsed);
    }

    private static boolean idsEquivalent(ResourceLocation viewerId, ResourceLocation sourceOrRuntimeId) {
        if (viewerId == null || sourceOrRuntimeId == null) {
            return false;
        }
        if (viewerId.equals(sourceOrRuntimeId)) {
            return true;
        }

        String a = viewerId.getPath();
        String b = sourceOrRuntimeId.getPath();
        String aFull = viewerId.getNamespace() + "/" + a;
        String bFull = sourceOrRuntimeId.getNamespace() + "/" + b;

        return a.equals(bFull)
                || b.equals(aFull)
                || a.endsWith("/" + bFull)
                || b.endsWith("/" + aFull);
    }

    private static boolean isGtRecipe(Object value) {
        return value != null && isGtRecipeClass(value.getClass());
    }

    private static boolean isGtRecipeClass(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (GT_RECIPE_CLASS.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Long readInputEUt(Object gtRecipe) {
        Object energyStack = invokeNoArg(gtRecipe, "getInputEUt");
        if (energyStack != null) {
            Long directNumber = numberValue(energyStack);
            if (directNumber != null) {
                return directNumber;
            }

            Long total = firstNumber(
                    invokeNoArg(energyStack, "getTotalEU"),
                    invokeNoArg(energyStack, "totalEU"),
                    invokeNoArg(energyStack, "getEUt"),
                    invokeNoArg(energyStack, "getEut"),
                    invokeNoArg(energyStack, "getEnergy"),
                    invokeNoArg(energyStack, "energy")
            );
            if (total != null) {
                return total;
            }

            Long voltage = firstNumber(
                    invokeNoArg(energyStack, "voltage"),
                    invokeNoArg(energyStack, "getVoltage"),
                    readFieldNumber(energyStack, "voltage")
            );
            Long amperage = firstNumber(
                    invokeNoArg(energyStack, "amperage"),
                    invokeNoArg(energyStack, "getAmperage"),
                    readFieldNumber(energyStack, "amperage")
            );
            if (voltage != null && amperage != null) {
                return multiplySaturated(voltage, amperage);
            }

            Long value = firstNumber(
                    invokeNoArg(energyStack, "value"),
                    invokeNoArg(energyStack, "getValue"),
                    readFieldNumber(energyStack, "value")
            );
            if (value != null) {
                return value;
            }
        }

        return readLong(
                gtRecipe,
                new String[]{"getEUt", "getEut", "EUt", "eut"},
                new String[]{"EUt", "eut"}
        );
    }

    private static Long readFieldNumber(Object owner, String fieldName) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (!field.canAccess(owner) && !field.trySetAccessible()) {
                    continue;
                }
                return numberValue(field.get(owner));
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long readLong(Object owner, String[] methodNames, String[] fieldNames) {
        for (String methodName : methodNames) {
            Long value = numberValue(invokeNoArg(owner, methodName));
            if (value != null) {
                return value;
            }
        }

        for (String fieldName : fieldNames) {
            Long value = readFieldNumber(owner, fieldName);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object owner, String methodName) {
        if (owner == null) {
            return null;
        }
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                if (!method.canAccess(owner) && !method.trySetAccessible()) {
                    continue;
                }
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long firstNumber(Object... values) {
        for (Object value : values) {
            Long number = numberValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static Long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long multiplySaturated(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException ignored) {
            return (left < 0L) ^ (right < 0L) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    private static Double syncedJsonEnergyCost(EmiRecipe recipe, double pricePerEu) {
        ResourceLocation viewerId = recipe.getId();
        if (viewerId == null) {
            return null;
        }

        JsonObject json = findSyncedRecipeForViewer(viewerId);
        if (json == null || !json.has("duration")) {
            return null;
        }

        try {
            long duration = json.get("duration").getAsLong();
            long eut = json.has("eut") ? json.get("eut").getAsLong() : 0L;
            if (duration <= 0L) {
                return null;
            }
            if (eut == 0L) {
                logResolvedOnce(recipe, "synced-json", duration, eut, pricePerEu, 0.0D);
                return 0.0D;
            }

            double cost = Math.abs((double) eut) * duration * pricePerEu;
            if (!Double.isFinite(cost) || cost < 0.0D) {
                return null;
            }
            logResolvedOnce(recipe, "synced-json", duration, eut, pricePerEu, cost);
            return cost;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JsonObject findSyncedRecipeForViewer(ResourceLocation viewerId) {
        ResourceLocation embeddedOriginal = originalIdFromViewer(viewerId);
        for (String raw : RecipeEditorGtoSyncState.activeJson()) {
            try {
                JsonElement root = JsonParser.parseString(raw);
                JsonObject found = findInElement(root, viewerId, embeddedOriginal);
                if (found != null) {
                    return found;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ResourceLocation originalIdFromViewer(ResourceLocation viewerId) {
        if (viewerId == null) {
            return null;
        }
        String path = viewerId.getPath();
        int marker = path.indexOf(PREFIX);
        if (marker < 0) {
            return null;
        }
        String value = path.substring(marker + PREFIX.length());
        int slash = value.indexOf('/');
        if (slash <= 0 || slash >= value.length() - 1) {
            return null;
        }
        return ResourceLocation.tryParse(value.substring(0, slash) + ":" + value.substring(slash + 1));
    }

    private static JsonObject findInElement(
            JsonElement element,
            ResourceLocation viewerId,
            ResourceLocation embeddedOriginal
    ) {
        if (element == null) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                JsonObject found = findInElement(child, viewerId, embeddedOriginal);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (!element.isJsonObject()) {
            return null;
        }

        JsonObject object = element.getAsJsonObject();
        if (object.has("recipes") && object.get("recipes").isJsonArray()) {
            return findInElement(object.get("recipes"), viewerId, embeddedOriginal);
        }
        if (!object.has("id")) {
            return null;
        }

        ResourceLocation sourceId = ResourceLocation.tryParse(object.get("id").getAsString());
        if (sourceId == null) {
            return null;
        }
        if (embeddedOriginal != null && embeddedOriginal.equals(sourceId)) {
            return object;
        }
        return viewerMatchesSource(viewerId, sourceId) ? object : null;
    }

    private static boolean viewerMatchesSource(ResourceLocation viewerId, ResourceLocation sourceId) {
        if (viewerId == null || sourceId == null) {
            return false;
        }
        if (viewerId.equals(sourceId)) {
            return true;
        }

        String sourceToken = sourceId.getNamespace() + "/" + sourceId.getPath();
        String viewerPath = viewerId.getPath();
        return viewerPath.equals(sourceToken)
                || viewerPath.endsWith("/" + sourceToken);
    }

    private static void logResolvedOnce(
            EmiRecipe recipe,
            String source,
            long duration,
            long eut,
            double pricePerEu,
            double cost
    ) {
        ResourceLocation id = recipe.getId();
        String key = "ok:" + source + ":" + (id == null ? recipe.getClass().getName() : id.toString());
        if (!LOGGED.add(key)) {
            return;
        }
        if (isCointCoreGtoRecipe(recipe)) {
            LOGGER.info(
                    "PriceCalc energy resolved: source={}, recipe={}, duration={}, eut={}, pricePerEu={}, cost={}",
                    source,
                    id,
                    duration,
                    eut,
                    pricePerEu,
                    cost
            );
        }
    }

    private static void logUnresolvedGtRecipeOnce(EmiRecipe recipe) {
        if (!isNativeGtViewer(recipe) || !isCointCoreGtoRecipe(recipe)) {
            return;
        }
        ResourceLocation id = recipe.getId();
        String key = "miss:" + (id == null ? recipe.getClass().getName() : id.toString());
        if (LOGGED.add(key)) {
            LOGGER.warn(
                    "PriceCalc could not resolve GT energy for recipe={} wrapper={}",
                    id,
                    recipe.getClass().getName()
            );
        }
    }

    private static boolean isNativeGtViewer(EmiRecipe recipe) {
        String name = recipe.getClass().getName();
        return name.endsWith("GTEmiRecipe") || name.contains(".gtceu.") || name.contains("gregtechceu");
    }

    private static boolean isCointCoreGtoRecipe(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        if (id == null) {
            return false;
        }
        return "cointcoregto".equals(id.getNamespace())
                || id.getPath().contains("cointcoregto")
                || id.getPath().contains(PREFIX);
    }
}
