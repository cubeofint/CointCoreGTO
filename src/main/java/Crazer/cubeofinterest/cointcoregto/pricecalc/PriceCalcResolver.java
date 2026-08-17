package Crazer.cubeofinterest.cointcoregto.pricecalc;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.material.Fluid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PriceCalcResolver {
    private final EmiRecipeManager manager;
    private final Map<String, Resolution> memo = new HashMap<>();
    private final Map<String, PriceCalcResultEntry> stagedResults = new LinkedHashMap<>();
    private final Set<String> path = new HashSet<>();

    public PriceCalcResolver() {
        this.manager = EmiApi.getRecipeManager();
    }

    public Resolution resolve(EmiStack target) {
        if (target == null || target.isEmpty()) {
            return Resolution.failure(Status.UNSUPPORTED, "Пустая цель");
        }
        PriceKey key = PriceKey.of(target);
        if (key == null) {
            return Resolution.failure(Status.UNSUPPORTED, "Поддерживаются только предметы и жидкости");
        }
        return resolveStackUnit(target.copy().setAmount(1), 0);
    }

    public Map<String, PriceCalcResultEntry> getStagedResults() {
        return new LinkedHashMap<>(stagedResults);
    }

    private Resolution resolveStackUnit(EmiStack stack, int depth) {
        PriceKey key = PriceKey.of(stack);
        if (key == null) {
            return Resolution.failure(Status.UNSUPPORTED, "Неизвестный тип: " + stack.getId());
        }
        if (depth > PriceCalcStorage.getMaxDepth()) {
            return Resolution.failure(Status.DEPTH_LIMIT, "Превышена глубина дерева возле " + key.storageKey());
        }

        Double manual = key.kind == Kind.ITEM
                ? PriceCalcStorage.getItemUnitPrice(key.id)
                : PriceCalcStorage.getFluidUnitPrice(key.id);
        if (manual != null) {
            return Resolution.success(manual, "base");
        }

        PriceCalcStorage.ComputedPrice computed = PriceCalcStorage.getComputedPrice(key.storageKey());
        if (computed != null && Double.isFinite(computed.price) && computed.price >= 0.0D) {
            return Resolution.success(computed.price, computed.recipeId);
        }

        Resolution memoized = memo.get(key.storageKey());
        if (memoized != null) {
            return memoized;
        }

        if (!path.add(key.storageKey())) {
            return Resolution.failure(Status.CYCLE, "Цикл рецептов возле " + key.storageKey());
        }

        try {
            List<EmiRecipe> candidates = collectCandidates(stack);
            if (candidates.isEmpty()) {
                return Resolution.failure(Status.MISSING_BASE, key.storageKey());
            }

            EmiRecipe selected;
            if (candidates.size() == 1) {
                selected = candidates.get(0);
            } else {
                String preferred = PriceCalcStorage.getPreferredRecipe(key.preferenceKey());
                selected = preferred == null ? null : findByRecipeKey(candidates, preferred);
                if (selected == null) {
                    if (preferred != null) {
                        PriceCalcStorage.removePreferredRecipe(key.preferenceKey());
                    }
                    return Resolution.needsChoice(stack.copy().setAmount(1), key.preferenceKey(), candidates);
                }
            }

            Resolution result = resolveRecipeForTarget(selected, stack, depth + 1);
            if (result.status == Status.SUCCESS) {
                memo.put(key.storageKey(), result);
                stagedResults.put(key.storageKey(), new PriceCalcResultEntry(result.price, result.recipeId));
            }
            return result;
        } finally {
            path.remove(key.storageKey());
        }
    }

    private Resolution resolveRecipeForTarget(EmiRecipe recipe, EmiStack target, int depth) {
        double inputCost = 0.0D;
        for (EmiIngredient input : recipe.getInputs()) {
            Resolution inputResolution = resolveIngredient(input, depth);
            if (inputResolution.status != Status.SUCCESS) {
                return inputResolution;
            }
            inputCost += inputResolution.price;
        }

        double expectedOutput = expectedTargetOutput(recipe, target);
        if (!(expectedOutput > 0.0D) || !Double.isFinite(expectedOutput)) {
            return Resolution.failure(Status.INVALID_RECIPE, recipeKey(recipe));
        }

        double energyCost = PriceCalcEnergyResolver.energyCost(recipe, PriceCalcStorage.getPricePerEu());
        double unitPrice = (inputCost + energyCost) / expectedOutput;
        if (!Double.isFinite(unitPrice) || unitPrice < 0.0D) {
            return Resolution.failure(Status.INVALID_RECIPE, recipeKey(recipe));
        }
        return Resolution.success(unitPrice, recipeKey(recipe));
    }

    private Resolution resolveIngredient(EmiIngredient ingredient, int depth) {
        if (ingredient == null || ingredient.isEmpty()) {
            return Resolution.success(0.0D, "empty");
        }

        double amount = Math.max(0L, ingredient.getAmount());
        double chance = normalizedChance(ingredient.getChance());
        double multiplier = amount * chance;

        if (ingredient instanceof TagEmiIngredient tagIngredient) {
            ResourceLocation tagId = tagIngredient.key.location();
            Double manual = PriceCalcStorage.getTagUnitPrice(tagId);
            if (manual != null) {
                return Resolution.success(manual * multiplier, "tag:" + tagId);
            }
            return Resolution.failure(Status.MISSING_TAG_BASE, "#" + tagId);
        }

        if (ingredient instanceof ListEmiIngredient listIngredient) {
            return resolveAlternatives(listIngredient.getIngredients(), multiplier, depth);
        }

        List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks == null || stacks.isEmpty()) {
            return Resolution.failure(Status.UNSUPPORTED, "Пустой ингредиент");
        }
        if (stacks.size() > 1) {
            return resolveStackAlternatives(stacks, multiplier, depth);
        }

        EmiStack stack = stacks.get(0);
        Resolution unit = resolveStackUnit(stack.copy().setAmount(1), depth + 1);
        if (unit.status != Status.SUCCESS) {
            return unit;
        }
        return Resolution.success(unit.price * multiplier, unit.recipeId);
    }

    private Resolution resolveAlternatives(List<? extends EmiIngredient> alternatives, double multiplier, int depth) {
        BranchState base = snapshotState();
        Resolution best = null;
        BranchState bestState = null;
        Resolution firstBlocking = null;
        for (EmiIngredient alternative : alternatives) {
            restoreState(base);
            Resolution resolution = resolveIngredientAsUnitAlternative(alternative, depth + 1);
            if (resolution.status == Status.SUCCESS) {
                if (best == null || resolution.price < best.price) {
                    best = resolution;
                    bestState = snapshotState();
                }
            } else if (firstBlocking == null) {
                firstBlocking = resolution;
            }
        }
        if (best != null) {
            restoreState(bestState);
            return Resolution.success(best.price * multiplier, best.recipeId);
        }
        restoreState(base);
        return firstBlocking != null ? firstBlocking : Resolution.failure(Status.UNSUPPORTED, "Нет вариантов ингредиента");
    }

    private Resolution resolveIngredientAsUnitAlternative(EmiIngredient ingredient, int depth) {
        if (ingredient instanceof TagEmiIngredient tagIngredient) {
            Double price = PriceCalcStorage.getTagUnitPrice(tagIngredient.key.location());
            if (price != null) {
                return Resolution.success(price, "tag:" + tagIngredient.key.location());
            }
            return Resolution.failure(Status.MISSING_TAG_BASE, "#" + tagIngredient.key.location());
        }
        List<EmiStack> stacks = ingredient.getEmiStacks();
        if (stacks == null || stacks.isEmpty()) {
            return Resolution.failure(Status.UNSUPPORTED, "Пустой вариант ингредиента");
        }

        BranchState base = snapshotState();
        Resolution best = null;
        BranchState bestState = null;
        Resolution firstBlocking = null;
        for (EmiStack stack : stacks) {
            restoreState(base);
            Resolution resolution = resolveStackUnit(stack.copy().setAmount(1), depth + 1);
            if (resolution.status == Status.SUCCESS) {
                if (best == null || resolution.price < best.price) {
                    best = resolution;
                    bestState = snapshotState();
                }
            } else if (firstBlocking == null) {
                firstBlocking = resolution;
            }
        }
        if (best != null) {
            restoreState(bestState);
            return best;
        }
        restoreState(base);
        return firstBlocking != null ? firstBlocking : Resolution.failure(Status.UNSUPPORTED, "Нет вариантов ингредиента");
    }

    private Resolution resolveStackAlternatives(List<EmiStack> stacks, double multiplier, int depth) {
        BranchState base = snapshotState();
        Resolution best = null;
        BranchState bestState = null;
        Resolution firstBlocking = null;
        for (EmiStack stack : stacks) {
            restoreState(base);
            Resolution resolution = resolveStackUnit(stack.copy().setAmount(1), depth + 1);
            if (resolution.status == Status.SUCCESS) {
                if (best == null || resolution.price < best.price) {
                    best = resolution;
                    bestState = snapshotState();
                }
            } else if (firstBlocking == null) {
                firstBlocking = resolution;
            }
        }
        if (best != null) {
            restoreState(bestState);
            return Resolution.success(best.price * multiplier, best.recipeId);
        }
        restoreState(base);
        return firstBlocking != null ? firstBlocking : Resolution.failure(Status.UNSUPPORTED, "Нет вариантов ингредиента");
    }

    private BranchState snapshotState() {
        return new BranchState(new HashMap<>(memo), new LinkedHashMap<>(stagedResults));
    }

    private void restoreState(BranchState state) {
        memo.clear();
        memo.putAll(state.memo);
        stagedResults.clear();
        stagedResults.putAll(state.stagedResults);
    }

    private List<EmiRecipe> collectCandidates(EmiStack target) {
        List<EmiRecipe> raw = manager.getRecipesByOutput(target);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Map<String, EmiRecipe> unique = new LinkedHashMap<>();
        for (EmiRecipe recipe : raw) {
            if (recipe == null || !recipe.supportsRecipeTree()) {
                continue;
            }
            if (!(expectedTargetOutput(recipe, target) > 0.0D)) {
                continue;
            }
            unique.putIfAbsent(recipeKey(recipe), recipe);
        }
        ArrayList<EmiRecipe> list = new ArrayList<>(unique.values());
        list.sort(Comparator.comparing(PriceCalcResolver::recipeKey));
        return list;
    }

    private static EmiRecipe findByRecipeKey(List<EmiRecipe> recipes, String key) {
        for (EmiRecipe recipe : recipes) {
            if (recipeKey(recipe).equals(key)) {
                return recipe;
            }
        }
        return null;
    }

    private static double expectedTargetOutput(EmiRecipe recipe, EmiStack target) {
        double amount = 0.0D;
        for (EmiStack output : recipe.getOutputs()) {
            if (output == null || output.isEmpty() || !samePriceKey(output, target)) {
                continue;
            }
            amount += Math.max(0L, output.getAmount()) * normalizedChance(output.getChance());
        }
        return amount;
    }

    private static boolean samePriceKey(EmiStack a, EmiStack b) {
        PriceKey ak = PriceKey.of(a);
        PriceKey bk = PriceKey.of(b);
        return ak != null && ak.equals(bk);
    }

    private static double normalizedChance(float chance) {
        if (!Float.isFinite(chance) || chance <= 0.0F) {
            return 1.0D;
        }
        return Math.min(1.0D, chance);
    }

    public static String recipeKey(EmiRecipe recipe) {
        ResourceLocation id = recipe.getId();
        if (id != null) {
            return id.toString();
        }
        StringBuilder signature = new StringBuilder();
        signature.append(recipe.getCategory().getId()).append('|');
        appendIngredientSignature(signature, recipe.getInputs());
        signature.append("->");
        for (EmiStack stack : recipe.getOutputs()) {
            PriceKey key = PriceKey.of(stack);
            signature.append(key == null ? stack.getId() : key.storageKey())
                    .append('@').append(stack.getAmount())
                    .append('@').append(stack.getChance()).append(';');
        }
        return "synthetic:" + recipe.getCategory().getId() + ":" + sha1(signature.toString()).substring(0, 16);
    }

    private static void appendIngredientSignature(StringBuilder builder, List<EmiIngredient> ingredients) {
        for (EmiIngredient ingredient : ingredients) {
            if (ingredient instanceof TagEmiIngredient tag) {
                builder.append('#').append(tag.key.location());
            } else {
                for (EmiStack stack : ingredient.getEmiStacks()) {
                    PriceKey key = PriceKey.of(stack);
                    builder.append(key == null ? stack.getId() : key.storageKey()).append(',');
                }
            }
            builder.append('@').append(ingredient.getAmount()).append(';');
        }
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return Integer.toUnsignedString(text.hashCode(), 16) + "0000000000000000";
        }
    }

    private record BranchState(Map<String, Resolution> memo, Map<String, PriceCalcResultEntry> stagedResults) {
    }

    public enum Status {
        SUCCESS,
        NEEDS_CHOICE,
        MISSING_BASE,
        MISSING_TAG_BASE,
        CYCLE,
        DEPTH_LIMIT,
        INVALID_RECIPE,
        UNSUPPORTED
    }

    public static final class Resolution {
        public final Status status;
        public final double price;
        public final String recipeId;
        public final String detail;
        public final EmiStack choiceTarget;
        public final String preferenceKey;
        public final List<EmiRecipe> choices;

        private Resolution(Status status, double price, String recipeId, String detail, EmiStack choiceTarget, String preferenceKey, List<EmiRecipe> choices) {
            this.status = status;
            this.price = price;
            this.recipeId = recipeId;
            this.detail = detail;
            this.choiceTarget = choiceTarget;
            this.preferenceKey = preferenceKey;
            this.choices = choices;
        }

        public static Resolution success(double price, String recipeId) {
            return new Resolution(Status.SUCCESS, price, recipeId, null, null, null, List.of());
        }

        public static Resolution failure(Status status, String detail) {
            return new Resolution(status, 0.0D, null, detail, null, null, List.of());
        }

        public static Resolution needsChoice(EmiStack target, String preferenceKey, List<EmiRecipe> choices) {
            return new Resolution(Status.NEEDS_CHOICE, 0.0D, null, null, target, preferenceKey, List.copyOf(choices));
        }
    }

    public enum Kind {
        ITEM,
        FLUID
    }

    public record PriceKey(Kind kind, ResourceLocation id) {
        public static PriceKey of(EmiStack stack) {
            Object key = stack.getKey();
            if (key instanceof Item) {
                return new PriceKey(Kind.ITEM, stack.getId());
            }
            if (key instanceof Fluid) {
                return new PriceKey(Kind.FLUID, stack.getId());
            }
            return null;
        }

        public String storageKey() {
            return kind == Kind.ITEM ? id.toString() : "fluid:" + id;
        }

        public String preferenceKey() {
            return storageKey();
        }
    }
}
