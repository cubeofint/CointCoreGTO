package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointManaOverlayDragMode {

    private static final Map<Target, Bounds> BOUNDS = new EnumMap<>(Target.class);

    private static boolean enabled = false;
    private static Target draggingTarget = null;

    private static int dragStartMouseX = 0;
    private static int dragStartMouseY = 0;

    private static int dragStartOffsetX = 0;
    private static int dragStartOffsetY = 0;

    private CointManaOverlayDragMode() {
    }

    public static boolean tryToggleForCurrentScreen() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.screen == null) {
            return false;
        }

        if (!isEmiRecipeScreen(minecraft.screen)) {
            return false;
        }

        enabled = !enabled;
        draggingTarget = null;
        BOUNDS.clear();

        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal(enabled
                            ? "§b[CointCoreGTO] §fРежим перемещения GUI маны включён. Потяни надпись мышкой."
                            : "§b[CointCoreGTO] §fРежим перемещения GUI маны выключен."),
                    true
            );
        }

        if (!enabled) {
            CointManaOverlayClientSettings.save();
        }

        return true;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void updateBounds(Target target, int x, int y, int width, int height) {
        if (target == null || width <= 0 || height <= 0) {
            return;
        }

        BOUNDS.put(target, new Bounds(x, y, width, height));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (!enabled) {
            return;
        }

        Screen screen = event.getScreen();

        if (screen == null || !isEmiRecipeScreen(screen)) {
            BOUNDS.clear();
            draggingTarget = null;
            return;
        }
        BOUNDS.clear();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!enabled) {
            return;
        }

        Screen screen = event.getScreen();

        if (screen == null || !isEmiRecipeScreen(screen)) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        Minecraft minecraft = Minecraft.getInstance();

        if (graphics == null || minecraft == null || minecraft.font == null) {
            return;
        }

        for (Map.Entry<Target, Bounds> entry : BOUNDS.entrySet()) {
            Bounds bounds = entry.getValue();

            if (bounds == null) {
                continue;
            }

            int color = entry.getKey() == draggingTarget ? 0xFF55FFFF : 0xAAFFFFFF;

            drawBorder(graphics, bounds.x(), bounds.y(), bounds.width(), bounds.height(), color);
        }

        Font font = minecraft.font;
        String hint = "§bРежим настройки GUI маны: §fперетащи нужную строку. Нажми кнопку ещё раз, чтобы сохранить.";
        int textWidth = font.width(stripColors(hint));
        int x = screen.width / 2 - textWidth / 2;
        int y = screen.height - 42;

        graphics.drawString(font, hint, x, y, 0xFFFFFFFF, true);
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!enabled) {
            return;
        }

        Screen screen = event.getScreen();

        if (screen == null || !isEmiRecipeScreen(screen)) {
            return;
        }

        int mouseX = (int) event.getMouseX();
        int mouseY = (int) event.getMouseY();

        for (Map.Entry<Target, Bounds> entry : BOUNDS.entrySet()) {
            Bounds bounds = entry.getValue();

            if (bounds == null || !bounds.contains(mouseX, mouseY)) {
                continue;
            }

            draggingTarget = entry.getKey();

            dragStartMouseX = mouseX;
            dragStartMouseY = mouseY;

            dragStartOffsetX = getOffsetX(draggingTarget);
            dragStartOffsetY = getOffsetY(draggingTarget);

            event.setCanceled(true);
            return;
        }
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!enabled || draggingTarget == null) {
            return;
        }

        int mouseX = (int) event.getMouseX();
        int mouseY = (int) event.getMouseY();

        int dx = mouseX - dragStartMouseX;
        int dy = mouseY - dragStartMouseY;

        setOffset(
                draggingTarget,
                dragStartOffsetX + dx,
                dragStartOffsetY + dy
        );

        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!enabled || draggingTarget == null) {
            return;
        }

        draggingTarget = null;
        CointManaOverlayClientSettings.save();
        event.setCanceled(true);
    }

    private static int getOffsetX(Target target) {
        return switch (target) {
            case EMI_RUNIC_ALTAR -> CointManaOverlayClientSettings.getEmiRunicAltarXOffset();
            case EMI_TERRA_PLATE -> CointManaOverlayClientSettings.getEmiTerraPlateXOffset();
            case EMI_MANA_INFUSER -> CointManaOverlayClientSettings.getEmiManaInfuserXOffset();
            case EMI_RUNE_RITUAL -> CointManaOverlayClientSettings.getEmiRuneRitualXOffset();
            case EMI_POOL_FIRST -> CointManaOverlayClientSettings.getEmiPoolXOffset();
            case EMI_POOL_SECOND -> CointManaOverlayClientSettings.getEmiPoolSecondXOffset();
            case WORLD_MANA -> CointManaOverlayClientSettings.getWorldManaXOffset();
        };
    }

    private static int getOffsetY(Target target) {
        return switch (target) {
            case EMI_RUNIC_ALTAR -> CointManaOverlayClientSettings.getEmiRunicAltarYOffset();
            case EMI_TERRA_PLATE -> CointManaOverlayClientSettings.getEmiTerraPlateYOffset();
            case EMI_MANA_INFUSER -> CointManaOverlayClientSettings.getEmiManaInfuserYOffset();
            case EMI_RUNE_RITUAL -> CointManaOverlayClientSettings.getEmiRuneRitualYOffset();
            case EMI_POOL_FIRST -> CointManaOverlayClientSettings.getEmiPoolYOffset();
            case EMI_POOL_SECOND -> CointManaOverlayClientSettings.getEmiPoolSecondYOffset();
            case WORLD_MANA -> CointManaOverlayClientSettings.getWorldManaYOffset();
        };
    }

    private static void setOffset(Target target, int x, int y) {
        switch (target) {
            case EMI_RUNIC_ALTAR -> CointManaOverlayClientSettings.setEmiRunicAltarOffset(x, y);
            case EMI_TERRA_PLATE -> CointManaOverlayClientSettings.setEmiTerraPlateOffset(x, y);
            case EMI_MANA_INFUSER -> CointManaOverlayClientSettings.setEmiManaInfuserOffset(x, y);
            case EMI_RUNE_RITUAL -> CointManaOverlayClientSettings.setEmiRuneRitualOffset(x, y);
            case EMI_POOL_FIRST -> CointManaOverlayClientSettings.setEmiPoolOffset(x, y);
            case EMI_POOL_SECOND -> CointManaOverlayClientSettings.setEmiPoolSecondOffset(x, y);
            case WORLD_MANA -> CointManaOverlayClientSettings.setWorldManaOffset(x, y);
        }
    }

    private static void drawBorder(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static boolean isEmiRecipeScreen(Screen screen) {
        if (screen == null) {
            return false;
        }

        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);

        return className.contains("dev.emi")
                && className.contains("recipescreen");
    }

    private static String stripColors(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text.replaceAll("§.", "");
    }

    public enum Target {
        EMI_RUNIC_ALTAR,
        EMI_TERRA_PLATE,
        EMI_MANA_INFUSER,
        EMI_RUNE_RITUAL,
        EMI_POOL_FIRST,
        EMI_POOL_SECOND,
        WORLD_MANA
    }

    private record Bounds(int x, int y, int width, int height) {

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= x
                    && mouseX <= x + width
                    && mouseY >= y
                    && mouseY <= y + height;
        }
    }
}