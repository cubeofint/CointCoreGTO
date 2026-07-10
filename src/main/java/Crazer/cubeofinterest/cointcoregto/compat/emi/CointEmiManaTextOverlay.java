package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointEmiManaTextOverlay {

    private static final int BACKGROUND_COLOR = 0xCC101018;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static Screen cachedScreen = null;
    private static int cachedPage = -1;
    private static int cachedTab = -1;
    private static long lastScanMillis = 0L;
    private static ManaRecipeInfo cachedInfo = null;

    private static final List<ManaRecipeInfo> RECIPES = List.of(
            new ManaRecipeInfo(
                    "Таумий (Слиток)",
                    500_000,
                    List.of(
                            req("ars_nouveau:manipulation_essence"),
                            req("ars_nouveau:source_gem"),
                            req("gtocore:livingsteel_ingot"),
                            req("gtocore:original_bronze_ingot")
                    )
            ),

            new ManaRecipeInfo(
                    "Вселенная",
                    1_000_000,
                    List.of(
                            req("extrabotany:the_chaos"),
                            req("extrabotany:the_end"),
                            req("extrabotany:the_origin")
                    )
            ),

            new ManaRecipeInfo(
                    "Слиток аэриалита",
                    500_000,
                    List.of(
                            req("botania:dragonstone"),
                            req("botania:ender_air_bottle"),
                            req("minecraft:phantom_membrane")
                    )
            ),

            new ManaRecipeInfo(
                    "Террастальной слиток",
                    500_000,
                    List.of(
                            req("botania:mana_diamond"),
                            req("botania:mana_pearl"),
                            req("botania:manasteel_ingot")
                    )
            ),

            new ManaRecipeInfo(
                    "Рейн молот",
                    4_000_000,
                    List.of(
                            req("extrabotany:aerialite_hammer"),
                            req("extrabotany:das_rheingold"),
                            req("extrabotany:elementium_hammer"),
                            req("extrabotany:gaia_hammer"),
                            req("extrabotany:manasteel_hammer"),
                            req("extrabotany:orichalcos_hammer"),
                            req("extrabotany:photonium_hammer"),
                            req("extrabotany:shadowium_hammer"),
                            req("extrabotany:terrasteel_hammer"),
                            req("extrabotany:the_universe")
                    )
            ),

            new ManaRecipeInfo(
                    "Слиток духа Гайи",
                    5_000_000,
                    List.of(
                            req("botania:life_essence"),
                            req("botania:life_essence"),
                            req("gtocore:gaiasteel_ingot", "mythicbotany:gaiasteel_ingot"),
                            req("gtocore:gaiasteel_ingot", "mythicbotany:gaiasteel_ingot")
                    )
            ),

            new ManaRecipeInfo(
                    "Гайясталь (Слиток)",
                    2_500_000,
                    List.of(
                            req("gtocore:runerock_ingot"),
                            req("gtocore:runerock_ingot"),
                            req("gtocore:runerock_ingot"),
                            req("mythicbotany:alfheim_rune"),
                            req("mythicbotany:alfsteel_ingot"),
                            req("mythicbotany:alfsteel_ingot"),
                            req("mythicbotany:alfsteel_ingot"),
                            req("mythicbotany:asgard_rune"),
                            req("mythicbotany:helheim_rune"),
                            req("mythicbotany:joetunheim_rune", "mythicbotany:jotunheim_rune"),
                            req("mythicbotany:midgard_rune"),
                            req("mythicbotany:muspelheim_rune"),
                            req("mythicbotany:nidavellir_rune"),
                            req("mythicbotany:niflheim_rune"),
                            req("mythicbotany:vanaheim_rune")
                    )
            ),

            new ManaRecipeInfo(
                    "Террастальной слиток",
                    500_000,
                    List.of(
                            req("botania:mana_diamond"),
                            req("botania:mana_pearl"),
                            req("botania:manasteel_ingot")
                    )
            ),

            new ManaRecipeInfo(
                    "Слиток Альфстали",
                    1_500_000,
                    List.of(
                            req(
                                    "botania:pixie_dust",
                                    "mythicbotany:pixie_dust",
                                    "mythicbotany:fairy_dust",
                                    "mythicbotany:fairy_pollen"
                            ),
                            req("botania:elementium_ingot"),
                            req("botania:dragonstone")
                    )
            )
    );

    private CointEmiManaTextOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            clearCache();
            return;
        }

        Screen screen = event.getScreen();

        if (screen == null || !isEmiRecipeScreen(screen)) {
            clearCache();
            return;
        }

        if (tryRenderManaPoolRecipes(event.getGuiGraphics(), minecraft.font, screen)) {
            return;
        }

        ManaRecipeInfo info = getCachedInfo(screen);

        if (info == null || info.mana() < 0) {
            return;
        }

        String text;

        if (info.mana() == 0) {
            text = "§bМана: §fне требуется §7("
                    + info.name()
                    + ")";
        } else {
            text = "§bТребуется маны: §f"
                    + formatNumber(info.mana())
                    + " §7("
                    + info.name()
                    + ")";
        }

        drawManaText(event.getGuiGraphics(), minecraft.font, screen, text);
    }

    private static boolean tryRenderManaPoolRecipes(GuiGraphics graphics, Font font, Screen screen) {
        if (graphics == null || font == null || screen == null) {
            return false;
        }

        Object currentPage = readObjectField(screen, "currentPage");

        if (currentPage == null) {
            return false;
        }

        boolean isManaPoolPage =
                containsClassOrString(currentPage, "mana_infusion", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "manainfusion", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "mana_pool", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "Наполнение маной", new IdentityHashMap<>(), 0);

        if (!isManaPoolPage) {
            return false;
        }

        if (!(currentPage instanceof Collection<?> recipes)) {
            return false;
        }

        int index = 0;
        boolean rendered = false;

        for (Object recipeEntry : recipes) {
            if (recipeEntry == null) {
                continue;
            }

            Integer mana = readManaFromObject(recipeEntry, new IdentityHashMap<>(), 0);

            if (mana == null || mana < 0) {
                index++;
                continue;
            }

            drawManaPoolRecipeText(
                    graphics,
                    font,
                    screen,
                    index,
                    mana
            );

            rendered = true;
            index++;
        }

        return rendered;
    }

    private static void drawManaPoolRecipeText(
            GuiGraphics graphics,
            Font font,
            Screen screen,
            int recipeIndex,
            int mana
    ) {
        if (graphics == null || font == null || screen == null) {
            return;
        }

        String text;

        if (mana == 0) {
            text = "§bМана: §fне требуется";
        } else {
            text = "§bМана: §f" + formatNumber(mana);
        }


        int barCenterX = screen.width / 2;


        int y = screen.height / 2 - 36 + recipeIndex * 112;

        int textWidth = font.width(stripColors(text));
        int x = barCenterX - textWidth / 2;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 600.0F);

        graphics.drawString(
                font,
                text,
                x,
                y,
                TEXT_COLOR,
                true
        );

        graphics.pose().popPose();
    }

    private static ManaRecipeInfo getCachedInfo(Screen screen) {
        long now = System.currentTimeMillis();

        int page = readIntField(screen, "page", "currentPageIndex", "recipePage");
        int tab = readIntField(screen, "tab", "currentTab", "selectedTab");

        if (screen == cachedScreen
                && page == cachedPage
                && tab == cachedTab
                && now - lastScanMillis < 400L) {
            return cachedInfo;
        }

        cachedScreen = screen;
        cachedPage = page;
        cachedTab = tab;
        lastScanMillis = now;

        cachedInfo = scanCurrentPage(screen);

        return cachedInfo;
    }

    private static void clearCache() {
        cachedScreen = null;
        cachedPage = -1;
        cachedTab = -1;
        lastScanMillis = 0L;
        cachedInfo = null;
    }

    private static ManaRecipeInfo resolveRuneRitualMana(Screen screen, Object currentPage) {
        if (screen == null || currentPage == null) {
            return null;
        }

        boolean isRuneRitual =
                containsClassOrString(currentPage, "rune_ritual", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "runeritual", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "Рунический ритуал", new IdentityHashMap<>(), 0);

        if (!isRuneRitual) {
            return null;
        }

        Integer mana = readRuneRitualMana(currentPage);

        if (mana == null || mana < 0) {
            return null;
        }

        return new ManaRecipeInfo("Рунический ритуал", mana, List.of());
    }

    private static Integer readRuneRitualMana(Object currentPage) {
        if (currentPage == null) {
            return null;
        }

        if (currentPage instanceof Collection<?> collection) {
            for (Object entry : collection) {
                Integer mana = readRuneRitualManaFromEntry(entry);

                if (mana != null) {
                    return mana;
                }
            }

            return null;
        }

        return readRuneRitualManaFromEntry(currentPage);
    }

    private static Integer readRuneRitualManaFromEntry(Object entry) {
        if (entry == null) {
            return null;
        }
        Object recipeWrapper = readObjectField(entry, "recipe");

        if (recipeWrapper != null) {
            Object realRecipe = readObjectField(recipeWrapper, "recipe");

            if (realRecipe != null) {
                Integer mana = readExactIntField(realRecipe, "mana");

                if (mana != null) {
                    return mana;
                }
            }

            Integer mana = readExactIntField(recipeWrapper, "mana");

            if (mana != null) {
                return mana;
            }
        }

        Integer mana = readExactIntField(entry, "mana");

        if (mana != null) {
            return mana;
        }

        return null;
    }

    private static Integer readExactIntField(Object target, String fieldName) {
        if (target == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        Class<?> type = target.getClass();

        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);

                Object value = field.get(target);

                if (value instanceof Integer integer) {
                    return integer;
                }

                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static ManaRecipeInfo scanCurrentPage(Screen screen) {
        Object currentPage = readObjectField(screen, "currentPage");

        if (currentPage == null) {
            return null;
        }

        ManaRecipeInfo runeRitualInfo = resolveRuneRitualMana(screen, currentPage);

        if (runeRitualInfo != null) {
            return runeRitualInfo;
        }

        if (isRunicAltarPage(currentPage)) {
            Integer mana = readManaFromObject(currentPage, new IdentityHashMap<>(), 0);

            if (mana != null && mana > 0) {
                return new ManaRecipeInfo("рунический рецепт", mana, List.of());
            }

            return null;
        }

        if (!isTerraPlateOrManaInfuserPage(currentPage)) {
            return null;
        }

        ArrayList<String> ids = collectItemIds(currentPage);

        return findMatchingRecipe(ids);
    }

    private static boolean isRunicAltarPage(Object currentPage) {
        if (currentPage == null) {
            return false;
        }

        boolean runic =
                containsClassOrString(currentPage, "runic", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "runic_altar", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "Руническое наполнение", new IdentityHashMap<>(), 0);

        boolean runeRitual =
                containsClassOrString(currentPage, "rune_ritual", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "runeritual", new IdentityHashMap<>(), 0)
                        || containsClassOrString(currentPage, "Рунический ритуал", new IdentityHashMap<>(), 0);

        return runic && !runeRitual;
    }

    private static boolean isTerraPlateOrManaInfuserPage(Object currentPage) {
        if (currentPage == null) {
            return false;
        }

        return containsClassOrString(currentPage, "terrestrial", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "agglomeration", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "terra_plate", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "mana_infuser", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "manainfuser", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "Мана-инфузер", new IdentityHashMap<>(), 0)
                || containsClassOrString(currentPage, "Теллурическая агломерация", new IdentityHashMap<>(), 0);
    }

    private static ManaRecipeInfo findMatchingRecipe(ArrayList<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }

        for (ManaRecipeInfo recipe : RECIPES) {
            if (recipeMatches(ids, recipe.requirements())) {
                return recipe;
            }
        }

        return null;
    }

    private static boolean recipeMatches(ArrayList<String> ids, List<List<String>> requirements) {
        if (ids == null || ids.isEmpty() || requirements == null || requirements.isEmpty()) {
            return false;
        }

        ArrayList<String> available = new ArrayList<>(ids);

        for (List<String> requirement : requirements) {
            boolean found = false;

            for (int i = 0; i < available.size(); i++) {
                String id = available.get(i);

                if (matchesRequirement(id, requirement)) {
                    available.remove(i);
                    found = true;
                    break;
                }
            }

            if (!found) {
                return false;
            }
        }

        return true;
    }

    private static boolean matchesRequirement(String id, List<String> alternatives) {
        if (id == null || alternatives == null || alternatives.isEmpty()) {
            return false;
        }

        String lowered = id.toLowerCase(Locale.ROOT);

        for (String alternative : alternatives) {
            if (alternative == null || alternative.isBlank()) {
                continue;
            }

            if (lowered.equals(alternative.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static ArrayList<String> collectItemIds(Object root) {
        ArrayList<String> result = new ArrayList<>();

        collectItemIdsRecursive(root, result, new IdentityHashMap<>(), 0);

        return result;
    }

    private static void collectItemIdsRecursive(
            Object object,
            ArrayList<String> result,
            IdentityHashMap<Object, Boolean> visited,
            int depth
    ) {
        if (object == null || depth > 6 || result.size() > 120) {
            return;
        }

        if (visited.containsKey(object)) {
            return;
        }

        visited.put(object, Boolean.TRUE);

        if (object instanceof ItemStack stack) {
            addItemStackId(result, stack);
            return;
        }

        if (object instanceof Item item) {
            addItemId(result, item);
            return;
        }

        if (object instanceof ResourceLocation resourceLocation) {
            addPossibleItemId(result, resourceLocation.toString());
            return;
        }

        if (object instanceof String string) {
            addPossibleItemId(result, string);
            return;
        }

        if (object instanceof Optional<?> optional) {
            optional.ifPresent(value -> collectItemIdsRecursive(value, result, visited, depth + 1));
            return;
        }

        Class<?> objectClass = object.getClass();

        if (objectClass.isArray()) {
            int length = Math.min(Array.getLength(object), 80);

            for (int i = 0; i < length; i++) {
                collectItemIdsRecursive(Array.get(object, i), result, visited, depth + 1);
            }

            return;
        }

        if (object instanceof Collection<?> collection) {
            int count = 0;

            for (Object value : collection) {
                if (count++ > 80) {
                    break;
                }

                collectItemIdsRecursive(value, result, visited, depth + 1);
            }

            return;
        }

        if (object instanceof Map<?, ?> map) {
            int count = 0;

            for (Object value : map.values()) {
                if (count++ > 80) {
                    break;
                }

                collectItemIdsRecursive(value, result, visited, depth + 1);
            }

            return;
        }

        Class<?> type = object.getClass();

        if (isSimpleValue(type)) {
            return;
        }

        String className = type.getName().toLowerCase(Locale.ROOT);

        if (!isSafeObject(className)) {
            return;
        }

        Object stackObject = readObjectMethod(
                object,
                "getItemStack",
                "getStack",
                "getDefaultStack",
                "getRemainder",
                "getKey"
        );

        if (stackObject != null && stackObject != object) {
            collectItemIdsRecursive(stackObject, result, visited, depth + 1);
        }

        Object stacksObject = readObjectMethod(
                object,
                "getEmiStacks",
                "getStacks",
                "getInputs",
                "getOutputs",
                "getCatalysts",
                "getIngredients",
                "getItems",
                "getWidgets"
        );

        if (stacksObject != null && stacksObject != object) {
            collectItemIdsRecursive(stacksObject, result, visited, depth + 1);
        }

        while (type != null) {
            Field[] fields;

            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                type = type.getSuperclass();
                continue;
            }

            int checked = 0;

            for (Field field : fields) {
                if (checked++ > 45) {
                    break;
                }

                try {
                    field.setAccessible(true);

                    Object value = field.get(object);

                    if (value == null || value == object) {
                        continue;
                    }

                    String fieldName = field.getName().toLowerCase(Locale.ROOT);
                    String fieldClass = value.getClass().getName().toLowerCase(Locale.ROOT);

                    boolean useful =
                            fieldName.contains("stack")
                                    || fieldName.contains("item")
                                    || fieldName.contains("ingredient")
                                    || fieldName.contains("input")
                                    || fieldName.contains("output")
                                    || fieldName.contains("recipe")
                                    || fieldName.contains("widget")
                                    || fieldName.contains("id")
                                    || fieldClass.contains("emi")
                                    || fieldClass.contains("item")
                                    || fieldClass.contains("ingredient")
                                    || fieldClass.contains("widget")
                                    || value instanceof ItemStack
                                    || value instanceof Item
                                    || value instanceof ResourceLocation
                                    || value instanceof String
                                    || value instanceof Collection<?>
                                    || value instanceof Map<?, ?>
                                    || value.getClass().isArray();

                    if (!useful) {
                        continue;
                    }

                    collectItemIdsRecursive(value, result, visited, depth + 1);
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }
    }

    private static Integer readManaFromObject(
            Object object,
            IdentityHashMap<Object, Boolean> visited,
            int depth
    ) {
        if (object == null || depth > 6) {
            return null;
        }

        if (visited.containsKey(object)) {
            return null;
        }

        visited.put(object, Boolean.TRUE);

        Integer mana = readIntMethod(
                object,
                "getMana",
                "getManaCost",
                "getManaUsage",
                "getRequiredMana",
                "getManaRequired"
        );

        if (mana != null && mana > 0) {
            return mana;
        }

        mana = readIntFieldNullable(
                object,
                "mana",
                "manaCost",
                "manaUsage",
                "requiredMana",
                "manaRequired"
        );

        if (mana != null && mana > 0) {
            return mana;
        }

        if (object instanceof Optional<?> optional) {
            return optional
                    .map(value -> readManaFromObject(value, visited, depth + 1))
                    .orElse(null);
        }

        if (object instanceof Collection<?> collection) {
            for (Object value : collection) {
                Integer found = readManaFromObject(value, visited, depth + 1);

                if (found != null && found > 0) {
                    return found;
                }
            }

            return null;
        }

        if (object instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                Integer found = readManaFromObject(value, visited, depth + 1);

                if (found != null && found > 0) {
                    return found;
                }
            }

            return null;
        }

        Class<?> type = object.getClass();

        if (isSimpleValue(type)) {
            return null;
        }

        String className = type.getName().toLowerCase(Locale.ROOT);

        if (!isSafeObject(className)) {
            return null;
        }

        while (type != null) {
            Field[] fields;

            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                type = type.getSuperclass();
                continue;
            }

            int checked = 0;

            for (Field field : fields) {
                if (checked++ > 40) {
                    break;
                }

                try {
                    field.setAccessible(true);

                    Object value = field.get(object);

                    if (value == null || value == object) {
                        continue;
                    }

                    String fieldName = field.getName().toLowerCase(Locale.ROOT);
                    String fieldClass = value.getClass().getName().toLowerCase(Locale.ROOT);

                    boolean useful =
                            fieldName.contains("mana")
                                    || fieldName.contains("recipe")
                                    || fieldName.contains("display")
                                    || fieldName.contains("widget")
                                    || fieldName.contains("page")
                                    || fieldClass.contains("botania")
                                    || fieldClass.contains("mythicbotany")
                                    || fieldClass.contains("extrabotany")
                                    || fieldClass.contains("runic")
                                    || fieldClass.contains("recipe")
                                    || fieldClass.contains("emi")
                                    || value instanceof Collection<?>
                                    || value instanceof Map<?, ?>
                                    || value instanceof Optional<?>;

                    if (!useful) {
                        continue;
                    }

                    Integer found = readManaFromObject(value, visited, depth + 1);

                    if (found != null && found > 0) {
                        return found;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static boolean containsClassOrString(
            Object object,
            String needle,
            IdentityHashMap<Object, Boolean> visited,
            int depth
    ) {
        if (object == null || needle == null || needle.isBlank() || depth > 5) {
            return false;
        }

        if (visited.containsKey(object)) {
            return false;
        }

        visited.put(object, Boolean.TRUE);

        String loweredNeedle = needle.toLowerCase(Locale.ROOT);
        String className = object.getClass().getName().toLowerCase(Locale.ROOT);

        if (className.contains(loweredNeedle)) {
            return true;
        }

        if (object instanceof String string) {
            return string.toLowerCase(Locale.ROOT).contains(loweredNeedle);
        }

        if (object instanceof Optional<?> optional) {
            return optional
                    .map(value -> containsClassOrString(value, needle, visited, depth + 1))
                    .orElse(false);
        }

        if (object instanceof Collection<?> collection) {
            int count = 0;

            for (Object value : collection) {
                if (count++ > 50) {
                    break;
                }

                if (containsClassOrString(value, needle, visited, depth + 1)) {
                    return true;
                }
            }

            return false;
        }

        if (object instanceof Map<?, ?> map) {
            int count = 0;

            for (Object value : map.values()) {
                if (count++ > 50) {
                    break;
                }

                if (containsClassOrString(value, needle, visited, depth + 1)) {
                    return true;
                }
            }

            return false;
        }

        Class<?> type = object.getClass();

        if (isSimpleValue(type) || !isSafeObject(className)) {
            return false;
        }

        while (type != null) {
            Field[] fields;

            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                type = type.getSuperclass();
                continue;
            }

            int checked = 0;

            for (Field field : fields) {
                if (checked++ > 35) {
                    break;
                }

                try {
                    field.setAccessible(true);

                    Object value = field.get(object);

                    if (value == null || value == object) {
                        continue;
                    }

                    if (containsClassOrString(value, needle, visited, depth + 1)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return false;
    }

    private static void addItemStackId(ArrayList<String> result, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());

        if (id == null) {
            return;
        }

        int count = Math.max(1, stack.getCount());

        for (int i = 0; i < count; i++) {
            addPossibleItemId(result, id.toString());
        }
    }

    private static void addItemId(ArrayList<String> result, Item item) {
        if (item == null) {
            return;
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);

        if (id != null) {
            addPossibleItemId(result, id.toString());
        }
    }

    private static void addPossibleItemId(ArrayList<String> result, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        String lowered = value.toLowerCase(Locale.ROOT)
                .replace("itemstack", " ")
                .replace("emistack", " ")
                .replace("emstack", " ")
                .replace("[", " ")
                .replace("]", " ")
                .replace("{", " ")
                .replace("}", " ")
                .replace(",", " ")
                .replace("'", " ")
                .replace("\"", " ")
                .trim();

        String[] parts = lowered.split("[^a-z0-9_:.\\-]+");

        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }

            if (!part.contains(":")) {
                continue;
            }

            if (part.startsWith("minecraft:textures")
                    || part.startsWith("emi:textures")
                    || part.startsWith("patchouli:textures")) {
                continue;
            }

            result.add(part);
        }
    }

    private static Object readObjectField(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) {
            return null;
        }

        for (String fieldName : fieldNames) {
            Class<?> type = target.getClass();

            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);

                    Object result = field.get(target);

                    if (result != null) {
                        return result;
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static Object readObjectMethod(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            Class<?> type = target.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(target);

                    if (result != null) {
                        return result;
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static Integer readIntMethod(Object target, String... methodNames) {
        if (target == null || methodNames == null) {
            return null;
        }

        for (String methodName : methodNames) {
            Class<?> type = target.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(target);

                    if (result instanceof Integer integer) {
                        return integer;
                    }

                    if (result instanceof Number number) {
                        return number.intValue();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static int readIntField(Object target, String... fieldNames) {
        Integer value = readIntFieldNullable(target, fieldNames);
        return value == null ? 0 : value;
    }

    private static Integer readIntFieldNullable(Object target, String... fieldNames) {
        if (target == null || fieldNames == null) {
            return null;
        }

        for (String fieldName : fieldNames) {
            Class<?> type = target.getClass();

            while (type != null) {
                try {
                    Field field = type.getDeclaredField(fieldName);
                    field.setAccessible(true);

                    Object result = field.get(target);

                    if (result instanceof Integer integer) {
                        return integer;
                    }

                    if (result instanceof Number number) {
                        return number.intValue();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static boolean isSafeObject(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }

        return className.contains("emi")
                || className.contains("botania")
                || className.contains("mythicbotany")
                || className.contains("extrabotany")
                || className.contains("recipe")
                || className.contains("ingredient")
                || className.contains("stack")
                || className.contains("item")
                || className.contains("widget")
                || className.contains("page");
    }

    private static boolean isSimpleValue(Class<?> type) {
        if (type == null) {
            return true;
        }

        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }

        String name = type.getName();

        return name.startsWith("java.lang.")
                || name.startsWith("java.time.")
                || name.startsWith("java.util.concurrent.")
                || name.startsWith("org.joml.")
                || name.startsWith("com.mojang.blaze3d.")
                || name.startsWith("net.minecraft.client.Minecraft")
                || name.startsWith("net.minecraft.client.gui.Font");
    }

    private static boolean isEmiRecipeScreen(Screen screen) {
        if (screen == null) {
            return false;
        }

        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);

        return className.contains("dev.emi")
                && className.contains("recipescreen");
    }

    private static void drawManaText(GuiGraphics graphics, Font font, Screen screen, String text) {
        if (graphics == null || font == null || screen == null || text == null || text.isBlank()) {
            return;
        }

        int cleanWidth = font.width(stripColors(text));
        int boxWidth = Math.max(230, cleanWidth + 14);

        int x = screen.width / 2 - boxWidth / 2;
        int y = screen.height / 2 + 124;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 500.0F);

        graphics.fill(
                x - 4,
                y - 3,
                x + boxWidth,
                y + 12,
                BACKGROUND_COLOR
        );

        graphics.drawString(
                font,
                text,
                x,
                y,
                TEXT_COLOR,
                true
        );

        graphics.pose().popPose();
    }

    private static String formatNumber(int value) {
        return String.format(Locale.ROOT, "%,d", Math.max(0, value)).replace(",", " ");
    }

    private static String stripColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text.replaceAll("§.", "");
    }

    private static List<String> req(String... alternatives) {
        return List.of(alternatives);
    }

    private record ManaRecipeInfo(String name, int mana, List<List<String>> requirements) {
    }
}