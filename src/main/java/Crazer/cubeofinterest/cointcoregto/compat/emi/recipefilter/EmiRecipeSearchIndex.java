package Crazer.cubeofinterest.cointcoregto.compat.emi.recipefilter;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

final class EmiRecipeSearchIndex {
    private final Map<EmiRecipe, Document> cache = new IdentityHashMap<>();

    Document document(EmiRecipe recipe) {
        return cache.computeIfAbsent(recipe, Document::new);
    }

    static final class Document {
        private static final Map<String, String> MOD_NAMES = new ConcurrentHashMap<>();
        private static volatile Class<?> chemicalHelperClass;
        private static volatile boolean chemicalHelperResolved;
        private static volatile Method gtFluidTooltipMethod;
        private static volatile boolean gtFluidTooltipResolved;
        private static volatile boolean gtVoltageConstantsResolved;
        private static volatile String[] gtVoltageNames;
        private static volatile String[] gtVoltageNamesFormatted;
        private static volatile long[] gtVoltages;

        private final EmiRecipe recipe;
        private String inputs;
        private String outputs;
        private String any;
        private String mods;
        private String resourceIds;
        private String tags;
        private String tooltips;
        private String formulas;
        private String voltage;
        private List<EmiIngredient> inputIngredients;
        private List<EmiIngredient> outputIngredients;
        private Object gtRecipeDefinition;
        private boolean gtRecipeDefinitionResolved;

        private Document(EmiRecipe recipe) {
            this.recipe = recipe;
        }

        String inputs() {
            if (inputs == null) {
                inputs = buildSideText(true);
            }
            return inputs;
        }

        String outputs() {
            if (outputs == null) {
                outputs = buildSideText(false);
            }
            return outputs;
        }

        String any() {
            if (any == null) {
                StringBuilder builder = new StringBuilder();
                append(builder, inputs());
                append(builder, outputs());
                ResourceLocation recipeId = safeRecipeId();
                if (recipeId != null) {
                    append(builder, recipeId.toString());
                    appendMod(builder, recipeId.getNamespace());
                }
                any = normalize(builder.toString());
            }
            return any;
        }

        String mods() {
            if (mods == null) {
                StringBuilder builder = new StringBuilder();
                ResourceLocation recipeId = safeRecipeId();
                if (recipeId != null) {
                    appendMod(builder, recipeId.getNamespace());
                }
                for (EmiIngredient ingredient : ingredients(true, true)) {
                    appendIngredientMods(builder, ingredient);
                }
                for (EmiIngredient ingredient : ingredients(false, false)) {
                    appendIngredientMods(builder, ingredient);
                }
                mods = normalize(builder.toString());
            }
            return mods;
        }

        String resourceIds() {
            if (resourceIds == null) {
                StringBuilder builder = new StringBuilder();
                ResourceLocation recipeId = safeRecipeId();
                if (recipeId != null) {
                    append(builder, recipeId.toString());
                }
                for (EmiIngredient ingredient : ingredients(true, true)) {
                    appendIngredientIds(builder, ingredient);
                }
                for (EmiIngredient ingredient : ingredients(false, false)) {
                    appendIngredientIds(builder, ingredient);
                }
                resourceIds = normalize(builder.toString());
            }
            return resourceIds;
        }

        String tags() {
            if (tags == null) {
                StringBuilder builder = new StringBuilder();
                for (EmiIngredient ingredient : ingredients(true, true)) {
                    appendIngredientTags(builder, ingredient);
                }
                for (EmiIngredient ingredient : ingredients(false, false)) {
                    appendIngredientTags(builder, ingredient);
                }
                tags = normalize(builder.toString());
            }
            return tags;
        }

        String tooltips() {
            if (tooltips == null) {
                StringBuilder builder = new StringBuilder();
                for (EmiIngredient ingredient : ingredients(true, true)) {
                    appendIngredientTooltip(builder, ingredient, false);
                    appendIngredientDirectFormula(builder, ingredient);
                }
                for (EmiIngredient ingredient : ingredients(false, false)) {
                    appendIngredientTooltip(builder, ingredient, false);
                    appendIngredientDirectFormula(builder, ingredient);
                }
                appendRecipeTooltipText(builder);
                tooltips = normalize(builder.toString());
            }
            return tooltips;
        }

        String formulas() {
            if (formulas == null) {
                StringBuilder builder = new StringBuilder();
                for (EmiIngredient ingredient : ingredients(true, true)) {
                    appendIngredientFormula(builder, ingredient);
                }
                for (EmiIngredient ingredient : ingredients(false, false)) {
                    appendIngredientFormula(builder, ingredient);
                }
                formulas = normalize(builder.toString());
            }
            return formulas;
        }

        String voltage() {
            if (voltage == null) {
                voltage = buildVoltageText();
            }
            return voltage;
        }

        private String buildSideText(boolean input) {
            StringBuilder builder = new StringBuilder();
            List<EmiIngredient> list = ingredients(input, input);
            for (EmiIngredient ingredient : list) {
                appendIngredientBase(builder, ingredient);
            }
            return normalize(builder.toString());
        }

        private List<EmiIngredient> ingredients(boolean input, boolean includeCatalysts) {
            if (input) {
                if (inputIngredients == null) {
                    List<EmiIngredient> result = new ArrayList<>();
                    addIngredientsFromMethod(result, "getInputs");
                    if (includeCatalysts) {
                        addIngredientsFromMethod(result, "getCatalysts");
                    }
                    inputIngredients = List.copyOf(result);
                }
                return inputIngredients;
            }

            if (outputIngredients == null) {
                List<EmiIngredient> result = new ArrayList<>();
                addIngredientsFromMethod(result, "getOutputs");
                outputIngredients = List.copyOf(result);
            }
            return outputIngredients;
        }

        private void addIngredientsFromMethod(List<EmiIngredient> result, String methodName) {
            Object raw = invokeNoArgs(recipe, methodName);
            if (!(raw instanceof Iterable<?> iterable)) {
                return;
            }
            for (Object value : iterable) {
                if (value instanceof EmiIngredient ingredient) {
                    result.add(ingredient);
                }
            }
        }

        private ResourceLocation safeRecipeId() {
            try {
                return recipe.getId();
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void appendIngredientBase(StringBuilder builder, EmiIngredient ingredient) {
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    append(builder, itemStack.getHoverName().getString());
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                    if (id != null) {
                        append(builder, id.toString());
                        appendMod(builder, id.getNamespace());
                    }
                    long amount = safeAmount(stack);
                    if (amount > 0L) {
                        append(builder, Long.toString(amount));
                        append(builder, amount + "x");
                    }
                    continue;
                }

                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    FluidStack fluidStack = new FluidStack(fluid, 1000);
                    append(builder, fluidStack.getDisplayName().getString());
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                    if (id != null) {
                        append(builder, id.toString());
                        appendMod(builder, id.getNamespace());
                    }
                    long amount = safeAmount(stack);
                    if (amount > 0L) {
                        append(builder, Long.toString(amount));
                        append(builder, amount + "mb");
                    }
                } else if (key != null) {
                    append(builder, String.valueOf(key));
                }
            }
        }

        private void appendIngredientMods(StringBuilder builder, EmiIngredient ingredient) {
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                    if (id != null) {
                        appendMod(builder, id.getNamespace());
                    }
                    continue;
                }
                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                    if (id != null) {
                        appendMod(builder, id.getNamespace());
                    }
                }
            }
        }

        private void appendIngredientIds(StringBuilder builder, EmiIngredient ingredient) {
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
                    if (id != null) {
                        append(builder, id.toString());
                    }
                    continue;
                }
                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
                    if (id != null) {
                        append(builder, id.toString());
                    }
                }
            }
        }

        private void appendIngredientTags(StringBuilder builder, EmiIngredient ingredient) {
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    try {
                        itemStack.getItem().builtInRegistryHolder().tags().forEach(tag -> appendTag(builder, tag));
                    } catch (Throwable ignored) {
                    }
                    continue;
                }
                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    try {
                        fluid.builtInRegistryHolder().tags().forEach(tag -> appendTag(builder, tag));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private void appendRecipeTooltipText(StringBuilder builder) {
            Object definition = gtRecipeDefinition();
            if (definition == null) {
                return;
            }

            Object conditions = readField(definition, "conditions");
            if (conditions instanceof Object[] array) {
                for (Object condition : array) {
                    appendTextValue(builder, invokeNoArgs(condition, "getTooltips"));
                }
            } else if (conditions instanceof Iterable<?> iterable) {
                for (Object condition : iterable) {
                    appendTextValue(builder, invokeNoArgs(condition, "getTooltips"));
                }
            }

            Object recipeType = readField(definition, "recipeType");
            Object dataInfos = invokeNoArgs(recipeType, "getDataInfos");
            if (dataInfos instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (!(value instanceof Function<?, ?> function)) {
                        continue;
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Function<Object, Object> typed = (Function<Object, Object>) function;
                        appendTextValue(builder, typed.apply(definition));
                    } catch (Throwable ignored) {
                    }
                }
            }

            append(builder, voltage());
        }

        private String buildVoltageText() {
            Object definition = gtRecipeDefinition();
            if (definition == null) {
                return "";
            }

            StringBuilder builder = new StringBuilder();
            long inputEu = numberAsLong(invokeNoArgs(definition, "getInputEUt"));
            long outputEu = numberAsLong(invokeNoArgs(definition, "getOutputEUt"));
            long eu = inputEu != 0L ? inputEu : outputEu;
            if (eu == 0L) {
                eu = numberAsLong(readField(definition, "eut"));
            }

            if (eu != 0L) {
                long absolute = eu == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(eu);
                append(builder, Long.toString(absolute));
                append(builder, absolute + " EU/t");
                append(builder, absolute + "EU/t");
                append(builder, absolute + " EUt");
                append(builder, absolute + "EUt");
                append(builder, "usage " + absolute);
            }

            int tier = numberAsInt(readField(definition, "tier"), -1);
            if (tier >= 0) {
                append(builder, Integer.toString(tier));
                append(builder, "tier " + tier);
                resolveGtVoltageConstants();
                appendArrayEntry(builder, gtVoltageNames, tier);
                appendArrayEntry(builder, gtVoltageNamesFormatted, tier);
                if (gtVoltages != null && tier < gtVoltages.length) {
                    long nominal = gtVoltages[tier];
                    append(builder, Long.toString(nominal));
                    append(builder, nominal + " V");
                    append(builder, nominal + "V");
                    append(builder, "voltage " + nominal);
                }
            }

            return normalize(builder.toString());
        }

        private Object gtRecipeDefinition() {
            if (gtRecipeDefinitionResolved) {
                return gtRecipeDefinition;
            }
            gtRecipeDefinitionResolved = true;

            Object value = readField(recipe, "recipe");
            if (value == null) {
                value = invokeNoArgs(recipe, "getRecipe");
            }
            if (value == null) {
                value = invokeNoArgs(recipe, "getRecipeDefinition");
            }
            if (value != null && value.getClass().getName().endsWith("GTRecipeDefinition")) {
                gtRecipeDefinition = value;
            }
            return gtRecipeDefinition;
        }

        private static void appendTextValue(StringBuilder builder, Object value) {
            if (value == null) {
                return;
            }
            if (value instanceof Component component) {
                appendTooltipText(builder, component.getString(), false);
                return;
            }
            if (value instanceof CharSequence sequence) {
                appendTooltipText(builder, sequence.toString(), false);
                return;
            }
            if (value instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    appendTextValue(builder, element);
                }
                return;
            }
            appendTooltipText(builder, componentOrString(value), false);
        }

        private static long numberAsLong(Object value) {
            return value instanceof Number number ? number.longValue() : 0L;
        }

        private static int numberAsInt(Object value, int fallback) {
            return value instanceof Number number ? number.intValue() : fallback;
        }

        private static void appendArrayEntry(StringBuilder builder, String[] values, int index) {
            if (values == null || index < 0 || index >= values.length) {
                return;
            }
            String value = ChatFormatting.stripFormatting(values[index]);
            append(builder, value == null ? values[index] : value);
        }

        private static void resolveGtVoltageConstants() {
            if (gtVoltageConstantsResolved) {
                return;
            }
            synchronized (Document.class) {
                if (gtVoltageConstantsResolved) {
                    return;
                }
                try {
                    Class<?> values = Class.forName(
                            "com.gregtechceu.gtceu.api.GTValues",
                            false,
                            Document.class.getClassLoader()
                    );
                    Object vn = readStaticField(values, "VN");
                    if (vn instanceof String[] array) {
                        gtVoltageNames = array;
                    }
                    Object vnf = readStaticField(values, "VNF");
                    if (vnf instanceof String[] array) {
                        gtVoltageNamesFormatted = array;
                    }
                    Object volts = readStaticField(values, "V");
                    if (volts instanceof long[] array) {
                        gtVoltages = array;
                    }
                } catch (Throwable ignored) {
                    gtVoltageNames = null;
                    gtVoltageNamesFormatted = null;
                    gtVoltages = null;
                }
                gtVoltageConstantsResolved = true;
            }
        }

        private static Object readStaticField(Class<?> type, String name) {
            if (type == null) {
                return null;
            }
            try {
                Field field = type.getField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (Throwable ignored) {
            }
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void appendIngredientTooltip(StringBuilder builder, EmiIngredient ingredient, boolean formulaOnly) {
            Minecraft minecraft = Minecraft.getInstance();
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    try {
                        List<Component> tooltip = itemStack.getTooltipLines(minecraft.player, TooltipFlag.Default.NORMAL);
                        for (Component line : tooltip) {
                            appendTooltipLine(builder, line, formulaOnly);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    long rawAmount = safeAmount(stack);
                    int amount = rawAmount <= 0L ? 1000 : (int) Math.min(Integer.MAX_VALUE, rawAmount);
                    appendGtFluidTooltip(builder, new FluidStack(fluid, amount), formulaOnly);
                }

                appendEmiTooltipReflectively(builder, stack, formulaOnly);
            }
        }

        private void appendIngredientFormula(StringBuilder builder, EmiIngredient ingredient) {
            boolean foundDirect = appendIngredientDirectFormula(builder, ingredient);
            if (!foundDirect) {
                appendIngredientTooltip(builder, ingredient, true);
            }
        }

        private boolean appendIngredientDirectFormula(StringBuilder builder, EmiIngredient ingredient) {
            boolean found = false;
            for (EmiStack stack : safeStacks(ingredient)) {
                ItemStack itemStack = safeItemStack(stack);
                if (!itemStack.isEmpty()) {
                    String formula = tryGtFormula(itemStack);
                    if (!formula.isBlank()) {
                        append(builder, formula);
                        found = true;
                    }
                }
                Object key = safeKey(stack);
                if (key instanceof Fluid fluid) {
                    String fluidFormula = tryGtFormula(fluid);
                    if (!fluidFormula.isBlank()) {
                        append(builder, fluidFormula);
                        found = true;
                    }
                    String stackFormula = tryGtFormula(new FluidStack(fluid, 1000));
                    if (!stackFormula.isBlank()) {
                        append(builder, stackFormula);
                        found = true;
                    }
                }
            }
            return found;
        }

        private static void appendEmiTooltipReflectively(StringBuilder builder, EmiStack stack, boolean formulaOnly) {
            String[] names = {"getTooltipText", "getTooltip"};
            for (String name : names) {
                Object raw = invokeNoArgs(stack, name);
                if (!(raw instanceof Iterable<?> iterable)) {
                    continue;
                }
                for (Object value : iterable) {
                    String text = null;
                    if (value instanceof Component component) {
                        text = component.getString();
                    } else if (value != null) {
                        Object component = invokeNoArgs(value, "getText");
                        if (component instanceof Component c) {
                            text = c.getString();
                        }
                    }
                    if (text != null) {
                        appendTooltipText(builder, text, formulaOnly);
                    }
                }
                return;
            }
        }

        private static void appendGtFluidTooltip(StringBuilder builder, FluidStack fluidStack, boolean formulaOnly) {
            if (fluidStack == null || fluidStack.isEmpty()) {
                return;
            }

            Method method = gtFluidTooltipMethod();
            if (method == null) {
                return;
            }

            Consumer<Component> consumer = component -> appendTooltipLine(builder, component, formulaOnly);
            try {
                method.invoke(null, fluidStack, consumer, TooltipFlag.Default.NORMAL);
            } catch (Throwable ignored) {
            }
        }

        private static Method gtFluidTooltipMethod() {
            if (gtFluidTooltipResolved) {
                return gtFluidTooltipMethod;
            }

            synchronized (Document.class) {
                if (!gtFluidTooltipResolved) {
                    try {
                        Class<?> type = Class.forName(
                                "com.gregtechceu.gtceu.client.TooltipsHandler",
                                false,
                                Document.class.getClassLoader()
                        );
                        for (Method method : type.getDeclaredMethods()) {
                            if (!Modifier.isStatic(method.getModifiers())
                                    || !"appendFluidTooltips".equals(method.getName())) {
                                continue;
                            }
                            Class<?>[] parameters = method.getParameterTypes();
                            if (parameters.length == 3
                                    && FluidStack.class.isAssignableFrom(parameters[0])
                                    && Consumer.class.isAssignableFrom(parameters[1])
                                    && TooltipFlag.class.isAssignableFrom(parameters[2])) {
                                method.setAccessible(true);
                                gtFluidTooltipMethod = method;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        gtFluidTooltipMethod = null;
                    }
                    gtFluidTooltipResolved = true;
                }
            }
            return gtFluidTooltipMethod;
        }

        private static void appendTooltipLine(StringBuilder builder, Component component, boolean formulaOnly) {
            if (component == null) {
                return;
            }
            appendTooltipText(builder, component.getString(), formulaOnly);
        }

        private static void appendTooltipText(StringBuilder builder, String value, boolean formulaOnly) {
            if (value == null) {
                return;
            }
            String text = ChatFormatting.stripFormatting(value);
            if (text == null || text.isBlank()) {
                return;
            }
            if (!formulaOnly || looksLikeFormula(text)) {
                append(builder, text);
            }
        }

        private static String tryGtFormula(Object value) {
            try {
                Class<?> helper = chemicalHelper();
                if (helper == null || value == null) {
                    return "";
                }

                String direct = extractFormula(value);
                if (!direct.isBlank()) {
                    return direct;
                }

                String[] lookupNames = {"getMaterial", "getMaterialStack"};
                for (String lookupName : lookupNames) {
                    Object materialStack = invokeCompatibleStatic(helper, lookupName, value);
                    String formula = extractFormula(materialStack);
                    if (!formula.isBlank()) {
                        return formula;
                    }
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

        private static String extractFormula(Object value) {
            if (value == null) {
                return "";
            }

            Object isNull = invokeNoArgs(value, "isNull");
            if (Boolean.TRUE.equals(isNull)) {
                return "";
            }

            Object formula = invokeNoArgs(value, "getChemicalFormula");
            if (formula == null) {
                formula = invokeNoArgs(value, "getFormula");
            }
            String formulaText = componentOrString(formula);
            if (!formulaText.isBlank()) {
                return formulaText;
            }

            Object material = invokeNoArgs(value, "material");
            if (material == null) {
                material = invokeNoArgs(value, "getMaterial");
            }
            if (material == null) {
                material = readField(value, "material");
            }
            if (material == null || material == value) {
                return "";
            }

            formula = invokeNoArgs(material, "getChemicalFormula");
            if (formula == null) {
                formula = invokeNoArgs(material, "getFormula");
            }
            return componentOrString(formula);
        }

        private static String componentOrString(Object value) {
            if (value == null) {
                return "";
            }
            if (value instanceof Component component) {
                return component.getString();
            }
            Object string = invokeNoArgs(value, "getString");
            if (string instanceof String text) {
                return text;
            }
            return String.valueOf(value);
        }

        private static Class<?> chemicalHelper() {
            if (chemicalHelperResolved) {
                return chemicalHelperClass;
            }
            synchronized (Document.class) {
                if (!chemicalHelperResolved) {
                    try {
                        chemicalHelperClass = Class.forName(
                                "com.gregtechceu.gtceu.api.data.chemical.ChemicalHelper",
                                false,
                                Document.class.getClassLoader()
                        );
                    } catch (Throwable ignored) {
                        chemicalHelperClass = null;
                    }
                    chemicalHelperResolved = true;
                }
            }
            return chemicalHelperClass;
        }

        private static Object invokeCompatibleStatic(Class<?> type, String name, Object argument) {
            for (Method method : type.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || !name.equals(method.getName())) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 1 || !parameters[0].isInstance(argument)) {
                    continue;
                }
                try {
                    return method.invoke(null, argument);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        private static Object readField(Object target, String name) {
            if (target == null) {
                return null;
            }
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }

        private static void appendTag(StringBuilder builder, TagKey<?> tag) {
            if (tag == null || tag.location() == null) {
                return;
            }
            append(builder, tag.location().toString());
            append(builder, tag.location().getPath());
        }

        private static boolean looksLikeFormula(String value) {
            if (value == null) {
                return false;
            }
            String text = RecipeFilterText.normalizeChemicalGlyphs(value.trim());
            if (text.length() < 2 || text.length() > 128 || text.indexOf(' ') >= 0) {
                return false;
            }
            boolean upper = false;
            boolean structure = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (Character.isUpperCase(c)) {
                    upper = true;
                }
                if (Character.isDigit(c) || c == '(' || c == ')' || c == '[' || c == ']') {
                    structure = true;
                }
            }
            return upper && structure;
        }

        private static void appendMod(StringBuilder builder, String namespace) {
            if (namespace == null || namespace.isBlank()) {
                return;
            }
            append(builder, namespace);
            String display = MOD_NAMES.computeIfAbsent(namespace, id -> {
                try {
                    return ModList.get().getModContainerById(id)
                            .map(container -> container.getModInfo().getDisplayName())
                            .orElse(id);
                } catch (Throwable ignored) {
                    return id;
                }
            });
            append(builder, display);
        }

        private static List<EmiStack> safeStacks(EmiIngredient ingredient) {
            if (ingredient == null) {
                return List.of();
            }
            try {
                List<EmiStack> stacks = ingredient.getEmiStacks();
                return stacks == null ? List.of() : stacks;
            } catch (Throwable ignored) {
                return List.of();
            }
        }

        private static ItemStack safeItemStack(EmiStack stack) {
            if (stack == null) {
                return ItemStack.EMPTY;
            }
            try {
                ItemStack value = stack.getItemStack();
                return value == null ? ItemStack.EMPTY : value;
            } catch (Throwable ignored) {
                return ItemStack.EMPTY;
            }
        }

        private static Object safeKey(EmiStack stack) {
            try {
                return stack == null ? null : stack.getKey();
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static long safeAmount(EmiStack stack) {
            try {
                return stack == null ? 0L : stack.getAmount();
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        private static Object invokeNoArgs(Object target, String name) {
            if (target == null) {
                return null;
            }
            for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (NoSuchMethodException ignored) {
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        private static void append(StringBuilder builder, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(value);
        }

        private static String normalize(String value) {
            return RecipeFilterText.normalize(value);
        }
    }
}
