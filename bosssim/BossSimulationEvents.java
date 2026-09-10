package Crazer.cubeofinterest.cointcoregto.bosssim;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

@Mod.EventBusSubscriber(modid = "cointcoregto", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossSimulationEvents {
    private static final ResourceLocation BOSS_SUMMONER_ID =
            new ResourceLocation("apotheosis", "boss_summoner");
    private static final ResourceLocation BOSS_SIMULATION_CHAMBER_ID =
            new ResourceLocation("cointcoregto", "boss_simulation_chamber");
    private static final ResourceLocation BOSS_SIMULATION_CASING_ID =
            new ResourceLocation("cointcoregto", "boss_simulation_casing");

    private BossSimulationEvents() {}

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack == null || stack.isEmpty()) return;

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return;

        List<Component> tooltip = event.getToolTip();

        if (BOSS_SIMULATION_CHAMBER_ID.equals(id)) {
            if (!tooltip.isEmpty()) {
                tooltip.set(0, Component.literal("Камера симуляции боссов")
                        .withStyle(ChatFormatting.WHITE));
            }

            tooltip.add(Component.literal("Виртуально создаёт награды боссов из Apotheosis без появления мобов.")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Положите ровно один модуль симуляции во входную шину предметов:")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("  Симуляция Овера / Симуляция Ада / Симуляция Энда")
                    .withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("Модуль симуляции не расходуется.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Награды поступают в выходную шину предметов после каждого цикла.")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Каждый новый цикл заново выбирает боссов и их награды.")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Минимальный уровень энергетического хэтча: LV.")
                    .withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("Количество независимых симуляций зависит от уровня энергетического хэтча:")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("  LV ×1, MV ×2, HV ×3, EV ×4, IV ×5, LuV ×6, ZPM ×7, UV ×8 ...")
                    .withStyle(ChatFormatting.GOLD));
            tooltip.add(Component.literal("Больше доступной мощности — быстрее цикл и выше удача генерации.")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Больше ампер ускоряет цикл, но не увеличивает число симуляций.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("В интерфейсе машины есть переключатель «Автоматическая разборка». ")
                    .withStyle(ChatFormatting.YELLOW));
            tooltip.add(Component.literal("Если он включён, все награды, которые поддерживают разборку, сразу превращаются в материалы Apotheosis.")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Самоцветы тоже разбираются, если для них предусмотрен результат разборки. Остальные награды выходят без изменений.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }

        if (BOSS_SIMULATION_CASING_ID.equals(id)) {
            if (!tooltip.isEmpty()) {
                tooltip.set(0, Component.literal("Корпус камеры симуляции")
                        .withStyle(ChatFormatting.WHITE));
            }
            tooltip.add(Component.literal("Используется для постройки Камеры симуляции боссов.")
                    .withStyle(ChatFormatting.GRAY));
            return;
        }

        BossSimulationMode.fromStack(stack).ifPresent(mode -> {
            String dimensionName = switch (mode) {
                case OVERWORLD -> "Верхний мир";
                case NETHER -> "Ад";
                case END -> "Энд";
            };
            tooltip.add(Component.literal("Выбирает боссов измерения: " + dimensionName + ".")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("Поместите во входную шину предметов Камеры симуляции боссов.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.literal("Не расходуется при работе машины.")
                    .withStyle(ChatFormatting.DARK_GRAY));
        });
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!isBossSummoner(event.getItemStack())) return;
        blockUse(event.getEntity());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!isBossSummoner(event.getItemStack())) return;
        blockUse(event.getEntity());
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cointbosssim")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("test")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    return simulateForTest(player, player.serverLevel());
                                })
                                .then(Commands.argument("dimension", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            String text = StringArgumentType.getString(ctx, "dimension");
                                            ResourceLocation id = ResourceLocation.tryParse(text);
                                            if (id == null) {
                                                ctx.getSource().sendFailure(Component.literal("Invalid dimension id: " + text));
                                                return 0;
                                            }
                                            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
                                            ServerLevel level = ctx.getSource().getServer().getLevel(key);
                                            if (level == null) {
                                                ctx.getSource().sendFailure(Component.literal(
                                                        "Dimension is not loaded/registered: " + id));
                                                return 0;
                                            }
                                            return simulateForTest(player, level);
                                        })))
        );
    }

    private static int simulateForTest(ServerPlayer player, ServerLevel simulatedLevel) {
        if (countEmptyMainInventorySlots(player) < 2) {
            player.sendSystemMessage(Component.literal("Free at least 2 inventory slots before the test.")
                    .withStyle(ChatFormatting.RED));
            return 0;
        }

        try {
            ApotheosisBossSimulator.SimulationResult result = ApotheosisBossSimulator.simulate(
                    simulatedLevel,
                    player,
                    BlockPos.containing(player.position())
            );

            if (!insertAll(player, result.outputs())) {
                player.sendSystemMessage(Component.literal("Test loot was generated but inventory insertion failed.")
                        .withStyle(ChatFormatting.RED));
                return 0;
            }

            String boss = result.bossDefinition() == null ? "<unknown>" : result.bossDefinition().toString();
            String entity = result.entityType() == null ? "<unknown>" : result.entityType().toString();
            String rarity = result.rarity() == null || result.rarity().isBlank()
                    ? "<unknown>" : result.rarity();

            player.sendSystemMessage(Component.literal(
                    "Boss simulation test: boss=" + boss
                            + ", entity=" + entity
                            + ", rarity=" + rarity
                            + ", outputs=" + result.outputs().size()
            ).withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (Throwable throwable) {
            player.sendSystemMessage(Component.literal("Boss simulation test failed: " + conciseMessage(throwable))
                    .withStyle(ChatFormatting.RED));
            return 0;
        }
    }

    private static boolean insertAll(ServerPlayer player, List<ItemStack> outputs) {
        for (ItemStack output : outputs) {
            if (!output.isEmpty() && !player.getInventory().add(output.copy())) {
                return false;
            }
        }
        player.containerMenu.broadcastChanges();
        return true;
    }

    private static int countEmptyMainInventorySlots(ServerPlayer player) {
        int empty = 0;
        int max = Math.min(36, player.getInventory().getContainerSize());
        for (int slot = 0; slot < max; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) empty++;
        }
        return empty;
    }

    private static boolean isBossSummoner(ItemStack stack) {
        return stack != null
                && !stack.isEmpty()
                && BOSS_SUMMONER_ID.equals(ForgeRegistries.ITEMS.getKey(stack.getItem()));
    }

    private static void blockUse(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide) return;
        player.displayClientMessage(
                Component.literal("Прямое использование Призывателя боссов отключено. Используйте Камеру симуляции боссов.")
                        .withStyle(ChatFormatting.RED),
                true
        );
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return cursor.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
