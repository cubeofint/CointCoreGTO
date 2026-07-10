package Crazer.cubeofinterest.cointcoregto.compat.jade;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.compat.emi.CointManaOverlayClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointBotaniaManaHudOverlay {

    private static final int Y_UNDER_JADE = 52;

    private static final int BACKGROUND_COLOR = 0xCC101018;
    private static final int BAR_BACKGROUND_COLOR = 0xCC202020;
    private static final int BAR_BORDER_COLOR = 0xFF555555;
    private static final int BAR_FILL_COLOR = 0xFF00B7FF;

    private static final int RED_COLOR = 0xFFFF3333;
    private static final int GREEN_COLOR = 0xFF44FF44;

    private static final String KIND_TERRA_PLATE = "terra_plate";
    private static final String KIND_MANA_INFUSER = "mana_infuser";

    private static final List<ManaCraftRecipe> HARDCODED_RECIPES = List.of(
            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
                    "Таумий (Слиток)",
                    500_000,
                    List.of(
                            req("ars_nouveau:manipulation_essence"),
                            req("ars_nouveau:source_gem"),
                            req("gtocore:livingsteel_ingot"),
                            req("gtocore:original_bronze_ingot")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
                    "Вселенная",
                    1_000_000,
                    List.of(
                            req("extrabotany:the_chaos"),
                            req("extrabotany:the_end"),
                            req("extrabotany:the_origin")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
                    "Слиток аэриалита",
                    500_000,
                    List.of(
                            req("botania:dragonstone"),
                            req("botania:ender_air_bottle"),
                            req("minecraft:phantom_membrane")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
                    "Террастальной слиток",
                    500_000,
                    List.of(
                            req("botania:mana_diamond"),
                            req("botania:mana_pearl"),
                            req("botania:manasteel_ingot")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
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

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
                    "Слиток духа Гайи",
                    5_000_000,
                    List.of(
                            req("botania:life_essence"),
                            req("botania:life_essence"),
                            req("gtocore:gaiasteel_ingot"),
                            req("gtocore:gaiasteel_ingot")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_TERRA_PLATE,
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
            new ManaCraftRecipe(
                    KIND_MANA_INFUSER,
                    "Террастальной слиток",
                    500_000,
                    List.of(
                            req("botania:mana_diamond"),
                            req("botania:mana_pearl"),
                            req("botania:manasteel_ingot")
                    )
            ),

            new ManaCraftRecipe(
                    KIND_MANA_INFUSER,
                    "Слиток Альфстали",
                    1_500_000,
                    List.of(
                            req(
                                    "botania:pixie_dust",
                                    "mythicbotany:pixie_dust",
                                    "mythicbotany:fairy_dust",
                                    "mythicbotany:fairy_pollen",
                                    "pixie",
                                    "fairy",
                                    "пыльца",
                                    "феи"
                            ),
                            req("botania:elementium_ingot"),
                            req("botania:dragonstone")
                    )
            )
    );

    private CointBotaniaManaHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }

        HitResult hitResult = minecraft.hitResult;

        if (!(hitResult instanceof BlockHitResult blockHitResult)) {
            return;
        }

        BlockPos pos = blockHitResult.getBlockPos();

        if (pos == null) {
            return;
        }

        BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);

        if (blockEntity == null) {
            return;
        }

        if (!isSupportedManaBlockEntity(blockEntity)) {
            return;
        }

        OverlayInfo info = buildOverlayInfo(pos, blockEntity);

        if (info == null || info.text() == null || info.text().isBlank()) {
            return;
        }

        drawUnderJade(event.getGuiGraphics(), minecraft.font, info);
    }

    private static OverlayInfo buildOverlayInfo(BlockPos pos, BlockEntity blockEntity) {
        if (isTerraPlate(blockEntity) || isManaInfuser(blockEntity)) {
            ManaCraftRecipe recipe = findHardcodedRecipe(blockEntity);
            Integer currentMana = readMana(blockEntity);

            if (recipe != null) {
                int requiredMana = recipe.mana();

                if (isManaInfuser(blockEntity)) {
                    currentMana = readManaInfuserProgressMana(blockEntity, requiredMana);

                    if (currentMana == null) {
                        currentMana = 0;
                    }
                } else {
                    if (currentMana == null) {
                        currentMana = 0;
                    }
                }

                currentMana = Math.max(0, Math.min(currentMana, requiredMana));

                boolean ready = currentMana >= requiredMana;

                String text = "§bМана крафта: §f"
                        + formatNumber(currentMana)
                        + " §7/ §f"
                        + formatNumber(requiredMana)
                        + " §7(" + recipe.name() + ")";

                return new OverlayInfo(text, currentMana, requiredMana, true, ready, true);
            }


            Integer maxMana = readMaxMana(blockEntity);

            if (currentMana != null && maxMana != null && maxMana > 0) {
                String title = isManaInfuser(blockEntity) ? "Мана-инфузер" : "Мана пластины";

                String text = "§b" + title + ": §f"
                        + formatNumber(currentMana)
                        + " §7/ §f"
                        + formatNumber(maxMana);

                return new OverlayInfo(text, currentMana, maxMana, true, false, false);
            }

            if (currentMana != null) {
                String title = isManaInfuser(blockEntity) ? "Мана-инфузер" : "Мана пластины";

                return new OverlayInfo(
                        "§b" + title + ": §f" + formatNumber(currentMana),
                        -1,
                        -1,
                        false,
                        false,
                        false
                );
            }

            return null;
        }

        if (isRuneHolder(blockEntity)) {
            Integer mana = readMana(blockEntity);
            Integer maxMana = readMaxMana(blockEntity);

            if (mana != null && maxMana != null && maxMana > 0) {
                String text = "§bМана: §f"
                        + formatNumber(mana)
                        + " §7/ §f"
                        + formatNumber(maxMana);

                return new OverlayInfo(text, mana, maxMana, true, false, false);
            }

            if (mana != null) {
                return new OverlayInfo(
                        "§bМана: §f" + formatNumber(mana),
                        -1,
                        -1,
                        false,
                        false,
                        false
                );
            }

            return new OverlayInfo(
                    "§bХранитель рун",
                    -1,
                    -1,
                    false,
                    false,
                    false
            );
        }

        if (isRunicAltar(blockEntity)) {
            Integer requiredMana = readRunicAltarCraftMana(blockEntity);
            Integer currentMana = readMana(blockEntity);

            if (requiredMana == null || requiredMana <= 0) {
                return null;
            }

            if (currentMana == null) {
                currentMana = 0;
            }

            currentMana = Math.max(0, Math.min(currentMana, requiredMana));

            int estimatedPerSecond = estimateNearbyManaPerSecond(pos);
            boolean ready = currentMana >= requiredMana;

            String text = "§bМана крафта: §f"
                    + formatNumber(currentMana)
                    + " §7/ §f"
                    + formatNumber(requiredMana);

            if (estimatedPerSecond > 0 && !ready) {
                text += " §7| §b≈ " + formatNumber(estimatedPerSecond) + " §7/ сек";
            }

            return new OverlayInfo(text, currentMana, requiredMana, true, ready, true);
        }

        if (isManaSpreader(blockEntity)) {
            Integer mana = readMana(blockEntity);
            Integer maxMana = readMaxMana(blockEntity);

            if (mana == null) {
                mana = 0;
            }

            if (maxMana == null || maxMana <= 0) {
                maxMana = 0;
            }

            int estimatedPerSecond = estimateSpreaderManaPerSecond(blockEntity);

            String text;

            if (maxMana > 0) {
                text = "§bМана: §f"
                        + formatNumber(mana)
                        + " §7/ §f"
                        + formatNumber(maxMana);
            } else {
                text = "§bМана: §f" + formatNumber(mana);
            }

            if (estimatedPerSecond > 0) {
                text += " §7| §b≈ " + formatNumber(estimatedPerSecond) + " §7/ сек";
            }

            if (maxMana > 0) {
                return new OverlayInfo(text, mana, maxMana, true, false, false);
            }

            return new OverlayInfo(text, -1, -1, false, false, false);
        }

        Integer mana = readMana(blockEntity);

        if (mana == null) {
            return null;
        }

        Integer maxMana = readMaxMana(blockEntity);

        if (maxMana != null && maxMana > 0) {
            String text = "§bМана: §f"
                    + formatNumber(mana)
                    + " §7/ §f"
                    + formatNumber(maxMana);

            return new OverlayInfo(text, mana, maxMana, true, false, false);
        }

        return new OverlayInfo("§bМана: §f" + formatNumber(mana), -1, -1, false, false, false);
    }

    private static Integer readManaInfuserProgressMana(Object target, int requiredMana) {
        if (target == null || requiredMana <= 0) {
            return null;
        }

        Double progress = readDoubleMethod(
                target,
                "getProgress",
                "getCraftProgress",
                "getRecipeProgress",
                "getInfusionProgress"
        );

        if (progress == null) {
            progress = readDoubleField(
                    target,
                    "progress",
                    "craftProgress",
                    "recipeProgress",
                    "infusionProgress"
            );
        }

        if (progress == null) {
            return null;
        }

        /*
         * MythicBotany возвращает -1.0, когда активного крафта нет
         * или прогресс ещё не синхронизирован.
         */
        if (progress < 0.0D) {
            return 0;
        }

        /*
         * Если progress в формате 0.0 - 1.0.
         */
        if (progress <= 1.0001D) {
            return Math.max(0, Math.min(requiredMana, (int) Math.round(requiredMana * progress)));
        }

        /*
         * Если progress уже хранится как количество маны.
         */
        return Math.max(0, Math.min(requiredMana, (int) Math.round(progress)));
    }

    private static Double readDoubleMethod(Object target, String... methodNames) {
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

                    if (result instanceof Number number) {
                        return number.doubleValue();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static Double readDoubleField(Object target, String... fieldNames) {
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

                    if (result instanceof Number number) {
                        return number.doubleValue();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return null;
    }

    private static ManaCraftRecipe findHardcodedRecipe(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return null;
        }

        String kind;

        if (isTerraPlate(blockEntity)) {
            kind = KIND_TERRA_PLATE;
        } else if (isManaInfuser(blockEntity)) {
            kind = KIND_MANA_INFUSER;
        } else {
            return null;
        }

        ArrayList<String> nearbyStacks = getNearbyStackSearchTexts(blockEntity.getBlockPos());

        if (nearbyStacks.isEmpty()) {
            return null;
        }

        for (ManaCraftRecipe recipe : HARDCODED_RECIPES) {
            if (!kind.equals(recipe.kind())) {
                continue;
            }

            if (recipeMatches(nearbyStacks, recipe.requirements())) {
                return recipe;
            }
        }

        return null;
    }

    private static boolean recipeMatches(ArrayList<String> stacks, List<List<String>> requirements) {
        if (stacks == null || stacks.isEmpty() || requirements == null || requirements.isEmpty()) {
            return false;
        }

        ArrayList<String> available = new ArrayList<>(stacks);

        for (List<String> requirement : requirements) {
            boolean found = false;

            for (int i = 0; i < available.size(); i++) {
                String stackText = available.get(i);

                if (matchesRequirement(stackText, requirement)) {
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

    private static boolean matchesRequirement(String stackText, List<String> alternatives) {
        if (stackText == null || alternatives == null || alternatives.isEmpty()) {
            return false;
        }

        String loweredStack = stackText.toLowerCase(Locale.ROOT);

        for (String alternative : alternatives) {
            if (alternative == null || alternative.isBlank()) {
                continue;
            }

            if (loweredStack.contains(alternative.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static ArrayList<String> getNearbyStackSearchTexts(BlockPos center) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.level == null || center == null) {
            return new ArrayList<>();
        }

        ArrayList<String> result = new ArrayList<>();

        try {
            AABB box = new AABB(center).inflate(1.55D, 1.55D, 1.55D);

            for (ItemEntity itemEntity : minecraft.level.getEntitiesOfClass(ItemEntity.class, box)) {
                if (itemEntity == null) {
                    continue;
                }

                ItemStack stack = itemEntity.getItem();

                if (stack == null || stack.isEmpty()) {
                    continue;
                }

                String searchText = getStackSearchText(stack);

                if (searchText.isBlank()) {
                    continue;
                }

                int count = Math.max(1, stack.getCount());

                for (int i = 0; i < count; i++) {
                    result.add(searchText);
                }
            }
        } catch (Throwable ignored) {
        }

        return result;
    }

    private static String getStackSearchText(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());

            if (itemId != null) {
                builder.append(itemId).append(' ');
            }
        } catch (Throwable ignored) {
        }

        try {
            builder.append(stack.getHoverName().getString()).append(' ');
        } catch (Throwable ignored) {
        }

        try {
            if (stack.getTag() != null) {
                builder.append(stack.getTag()).append(' ');
            }
        } catch (Throwable ignored) {
        }

        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static void drawUnderJade(GuiGraphics guiGraphics, Font font, OverlayInfo info) {
        if (guiGraphics == null || font == null || info == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.getWindow() == null) {
            return;
        }

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int centerX = screenWidth / 2 + CointManaOverlayClientSettings.getWorldManaXOffset();
        int y = Y_UNDER_JADE + CointManaOverlayClientSettings.getWorldManaYOffset();

        int textWidth = font.width(stripColors(info.text()));
        int textX = centerX - textWidth / 2;

        guiGraphics.drawString(
                font,
                info.text(),
                textX,
                y,
                0xFFFFFFFF,
                true
        );

        if (info.hasBar() && info.max() > 0) {
            int barWidth = Math.max(180, textWidth + 20);
            int drawBarWidth = info.showReadyMark() ? barWidth - 18 : barWidth;
            int barX = centerX - drawBarWidth / 2;

            drawProgressBar(
                    guiGraphics,
                    font,
                    barX,
                    y + 13,
                    drawBarWidth,
                    info.current(),
                    info.max(),
                    info.ready(),
                    info.showReadyMark()
            );
        }
    }

    private static void drawProgressBar(
            GuiGraphics guiGraphics,
            Font font,
            int x,
            int y,
            int width,
            int current,
            int max,
            boolean ready,
            boolean showReadyMark
    ) {
        int barHeight = 6;

        current = Math.max(0, current);
        max = Math.max(1, max);

        int fillWidth = (int) Math.round((double) width * (double) current / (double) max);
        fillWidth = Math.max(0, Math.min(width, fillWidth));

        guiGraphics.fill(x, y, x + width, y + barHeight, BAR_BACKGROUND_COLOR);

        if (fillWidth > 0) {
            guiGraphics.fill(x, y, x + fillWidth, y + barHeight, BAR_FILL_COLOR);
        }

        guiGraphics.fill(x, y, x + width, y + 1, BAR_BORDER_COLOR);
        guiGraphics.fill(x, y + barHeight - 1, x + width, y + barHeight, BAR_BORDER_COLOR);
        guiGraphics.fill(x, y, x + 1, y + barHeight, BAR_BORDER_COLOR);
        guiGraphics.fill(x + width - 1, y, x + width, y + barHeight, BAR_BORDER_COLOR);

        if (showReadyMark) {
            String mark = ready ? "✔" : "✖";
            int color = ready ? GREEN_COLOR : RED_COLOR;

            guiGraphics.drawString(
                    font,
                    mark,
                    x + width + 6,
                    y - 3,
                    color,
                    true
            );
        }
    }

    private static int estimateNearbyManaPerSecond(BlockPos center) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.level == null || center == null) {
            return 0;
        }

        int total = 0;
        int radius = 7;

        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -2, -radius),
                center.offset(radius, 3, radius)
        )) {
            BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);

            if (blockEntity == null) {
                continue;
            }

            if (!isManaSpreader(blockEntity)) {
                continue;
            }

            total += estimateSpreaderManaPerSecond(blockEntity);
        }

        return total;
    }

    private static int estimateSpreaderManaPerSecond(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return 0;
        }

        ResourceLocation id = getBlockId(blockEntity);

        int burstMana = estimateSpreaderBurstMana(blockEntity, id);
        double burstsPerSecond = estimateSpreaderBurstsPerSecond(blockEntity, id);

        return Math.max(0, (int) Math.round(burstMana * burstsPerSecond));
    }

    private static int estimateSpreaderBurstMana(BlockEntity blockEntity, ResourceLocation id) {
        Integer reflected = readIntMethod(
                blockEntity,
                "getBurstMana",
                "getManaPerBurst",
                "getManaPerShot",
                "getOutputMana"
        );

        if (reflected != null && reflected > 0) {
            return reflected;
        }

        reflected = readIntField(
                blockEntity,
                "burstMana",
                "manaPerBurst",
                "manaPerShot",
                "outputMana"
        );

        if (reflected != null && reflected > 0) {
            return reflected;
        }

        if (id != null) {
            String namespace = id.getNamespace();
            String path = id.getPath();

            if (path.contains("gaia")) {
                return 640;
            }

            if (path.contains("elven")) {
                return 160;
            }

            if ("mythicbotany".equals(namespace)) {
                return 640;
            }

            if ("extrabotany".equals(namespace)) {
                return 640;
            }

            if (path.contains("spreader")) {
                return 160;
            }
        }

        return 160;
    }

    private static double estimateSpreaderBurstsPerSecond(BlockEntity blockEntity, ResourceLocation id) {
        Integer reflected = readIntMethod(
                blockEntity,
                "getTicksBetweenBursts",
                "getDelay",
                "getCooldown",
                "getBurstCooldown"
        );

        if (reflected == null || reflected <= 0) {
            reflected = readIntField(
                    blockEntity,
                    "ticksBetweenBursts",
                    "delay",
                    "cooldown",
                    "burstCooldown"
            );
        }

        if (reflected != null && reflected > 0) {
            return 20.0D / reflected;
        }

        if (id != null) {
            String path = id.getPath();

            if (path.contains("gaia")) {
                return 1.5D;
            }

            if (path.contains("elven")) {
                return 1.25D;
            }
        }

        return 1.0D;
    }

    private static boolean isSupportedManaBlockEntity(BlockEntity blockEntity) {
        try {
            String className = blockEntity.getClass().getName().toLowerCase(Locale.ROOT);

            if (className.contains("botania")
                    || className.contains("mythicbotany")
                    || className.contains("extrabotany")) {
                return true;
            }

            ResourceLocation id = getBlockId(blockEntity);

            if (id == null) {
                return false;
            }

            return "botania".equals(id.getNamespace())
                    || "mythicbotany".equals(id.getNamespace())
                    || "extrabotany".equals(id.getNamespace());
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isTerraPlate(BlockEntity blockEntity) {
        ResourceLocation id = getBlockId(blockEntity);

        if (id == null) {
            return false;
        }

        String path = id.getPath();

        return path.contains("terra_plate")
                || path.contains("terraplate")
                || path.contains("telluric")
                || path.contains("agglomeration")
                || path.contains("plate");
    }

    private static boolean isManaInfuser(BlockEntity blockEntity) {
        ResourceLocation id = getBlockId(blockEntity);

        if (id == null) {
            return false;
        }

        String path = id.getPath();

        return path.contains("mana_infuser")
                || path.contains("manainfuser")
                || path.contains("infuser");
    }

    private static boolean isRuneHolder(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        try {
            String className = blockEntity.getClass().getName().toLowerCase(Locale.ROOT);

            if (className.contains("rune_holder")
                    || className.contains("runeholder")) {
                return true;
            }

            ResourceLocation id = getBlockId(blockEntity);

            if (id == null) {
                return false;
            }

            String namespace = id.getNamespace();
            String path = id.getPath();

            return ("mythicbotany".equals(namespace)
                    || "extrabotany".equals(namespace)
                    || "botania".equals(namespace))
                    && (path.contains("rune_holder")
                    || path.contains("runeholder"));
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isRunicAltar(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        try {
            String className = blockEntity.getClass().getName().toLowerCase(Locale.ROOT);

            if (className.contains("runicaltar")
                    || className.contains("runic_altar")
                    || className.contains("runic")
                    || className.contains("altar")) {
                return true;
            }

            ResourceLocation id = getBlockId(blockEntity);

            return id != null
                    && "botania".equals(id.getNamespace())
                    && id.getPath().contains("altar");
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean isManaSpreader(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }

        try {
            String className = blockEntity.getClass().getName().toLowerCase(Locale.ROOT);

            if (className.contains("spreader")) {
                return true;
            }

            ResourceLocation id = getBlockId(blockEntity);

            return id != null && id.getPath().contains("spreader");
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static ResourceLocation getBlockId(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.getBlockState() == null || blockEntity.getBlockState().getBlock() == null) {
            return null;
        }

        return ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
    }

    private static Integer readRunicAltarCraftMana(Object altar) {
        Integer direct = readIntMethod(
                altar,
                "getManaToGet",
                "getManaRequired",
                "getRequiredMana",
                "getRecipeMana",
                "getCraftMana",
                "getManaCost"
        );

        if (direct != null && direct > 0) {
            return direct;
        }

        direct = readIntField(
                altar,
                "manaToGet",
                "manaRequired",
                "requiredMana",
                "recipeMana",
                "craftMana",
                "manaCost"
        );

        if (direct != null && direct > 0) {
            return direct;
        }

        Object recipe = readRecipeObject(altar);

        if (recipe == null) {
            return null;
        }

        Integer fromRecipe = readIntMethod(
                recipe,
                "getManaUsage",
                "getManaCost",
                "getMana",
                "getRequiredMana",
                "getManaRequired"
        );

        if (fromRecipe != null && fromRecipe > 0) {
            return fromRecipe;
        }

        return readIntField(
                recipe,
                "mana",
                "manaUsage",
                "manaCost",
                "requiredMana",
                "manaRequired"
        );
    }

    private static Object readRecipeObject(Object target) {
        Object recipe = readObjectMethod(
                target,
                "getCurrentRecipe",
                "getRecipe",
                "getDisplayedRecipe",
                "getActiveRecipe",
                "getCachedRecipe"
        );

        if (recipe != null) {
            return unwrapOptional(recipe);
        }

        recipe = readObjectField(
                target,
                "currentRecipe",
                "recipe",
                "displayedRecipe",
                "activeRecipe",
                "cachedRecipe",
                "lastRecipe"
        );

        return unwrapOptional(recipe);
    }

    private static Integer readMana(Object target) {
        Integer value = readIntMethod(
                target,
                "getCurrentMana",
                "getMana",
                "getManaLevel",
                "getAvailableMana"
        );

        if (value != null) {
            return value;
        }

        return readIntField(
                target,
                "mana",
                "currentMana",
                "manaReceived",
                "availableMana"
        );
    }

    private static Integer readMaxMana(Object target) {
        Integer value = readIntMethod(
                target,
                "getMaxMana",
                "getMaximumMana",
                "getMaxManaLevel",
                "getManaCapacity",
                "getCapacity"
        );

        if (value != null) {
            return value;
        }

        return readIntField(
                target,
                "maxMana",
                "maximumMana",
                "manaCapacity",
                "capacity"
        );
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

    private static Integer readIntField(Object target, String... fieldNames) {
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

    private static Object unwrapOptional(Object value) {
        if (value instanceof Optional<?> optional) {
            return optional.orElse(null);
        }

        return value;
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

    private record OverlayInfo(String text, int current, int max, boolean hasBar, boolean ready, boolean showReadyMark) {
    }

    private record ManaCraftRecipe(String kind, String name, int mana, List<List<String>> requirements) {
    }
}