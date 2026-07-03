package Crazer.cubeofinterest.cubechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mod.EventBusSubscriber(
        modid = CubeChat.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CubeChatItemKeyShare {
    private CubeChatItemKeyShare() {
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!CubeChatKeyMappings.isShareItemKey(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        if (!(event.getScreen() instanceof AbstractContainerScreen<?> containerScreen)) {
            return;
        }

        if (isTextInputFocused(event.getScreen())) {
            return;
        }

        Slot hoveredSlot = findHoveredSlot(containerScreen);
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            return;
        }

        ItemStack stack = hoveredSlot.getItem();
        if (stack.isEmpty()) {
            return;
        }

        CubeChatItemShare.sendToServer(stack.copy());

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("§bПредмет отправлен в чат: §f" + stack.getHoverName().getString()), true);
        }

        event.setCanceled(true);
    }

    private static boolean isTextInputFocused(Screen screen) {
        try {
            return screen.getFocused() instanceof EditBox;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Slot findHoveredSlot(AbstractContainerScreen<?> screen) {
        Slot reflected = findHoveredSlotByField(screen);
        if (reflected != null) {
            return reflected;
        }

        return findHoveredSlotByMousePosition(screen);
    }

    private static Slot findHoveredSlotByField(AbstractContainerScreen<?> screen) {
        Class<?> type = screen.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (!Slot.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof Slot slot && slot.hasItem()) {
                        return slot;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static Slot findHoveredSlotByMousePosition(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
            return null;
        }

        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();

        int left = getScreenInt(screen, "getGuiLeft", "leftPos", "f_97735_");
        int top = getScreenInt(screen, "getGuiTop", "topPos", "f_97736_");

        for (Slot slot : screen.getMenu().slots) {
            if (slot == null || !slot.isActive() || !slot.hasItem()) {
                continue;
            }

            int slotX = left + slot.x;
            int slotY = top + slot.y;
            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                return slot;
            }
        }

        return null;
    }

    private static int getScreenInt(AbstractContainerScreen<?> screen, String methodName, String fieldName, String obfuscatedFieldName) {
        try {
            Method method = AbstractContainerScreen.class.getMethod(methodName);
            Object value = method.invoke(screen);
            if (value instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }

        Field field = findField(AbstractContainerScreen.class, fieldName, obfuscatedFieldName);
        if (field != null) {
            try {
                field.setAccessible(true);
                return field.getInt(screen);
            } catch (Throwable ignored) {
            }
        }

        return 0;
    }

    private static Field findField(Class<?> type, String... names) {
        for (String name : names) {
            try {
                return type.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }
}
