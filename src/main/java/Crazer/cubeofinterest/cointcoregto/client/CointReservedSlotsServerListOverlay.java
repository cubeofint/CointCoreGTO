package Crazer.cubeofinterest.cointcoregto.client;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointReservedSlotsServerListOverlay {
    private static boolean loggedActive = false;
    private static boolean loggedNoList = false;
    private static boolean loggedNoEntries = false;

    private CointReservedSlotsServerListOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();

        if (!(screen instanceof JoinMultiplayerScreen)) {
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();

        if (graphics == null) {
            return;
        }

        ServerSelectionList list = findServerSelectionList(screen);

        if (list == null) {
            if (!loggedNoList) {
                loggedNoList = true;
                System.out.println("[CointCoreGTO] Reserved slots overlay: ServerSelectionList not found.");
            }

            return;
        }

        List<?> entries = getChildren(list);

        if (entries == null || entries.isEmpty()) {
            if (!loggedNoEntries) {
                loggedNoEntries = true;
                System.out.println("[CointCoreGTO] Reserved slots overlay: server list entries not found.");
            }

            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.font == null) {
            return;
        }

        Font font = minecraft.font;

        for (int i = 0; i < entries.size(); i++) {
            Object entry = entries.get(i);

            ServerData serverData = findServerData(entry);

            if (serverData == null) {
                continue;
            }

            ReservedStatusInfo info = findReservedStatusInfo(serverData);

            if (info == null) {
                continue;
            }

            int rowTop = getRowTop(list, i);

            if (rowTop < -1000) {
                continue;
            }

            String text = "Резерв: "
                    + info.reservedOnline()
                    + "/"
                    + info.reservedSlots();

            int x = screen.width / 2 + 135;
            int y = rowTop + 21;

            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 700.0F);

            graphics.drawString(
                    font,
                    text,
                    x,
                    y,
                    0xFFAAAAAA,
                    false
            );

            graphics.pose().popPose();

            if (!loggedActive) {
                loggedActive = true;
                System.out.println("[CointCoreGTO] Reserved slots overlay active: " + text);
            }
        }
    }

    private static ServerSelectionList findServerSelectionList(Object target) {
        if (target == null) {
            return null;
        }

        Class<?> type = target.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);

                    Object value = field.get(target);

                    if (value instanceof ServerSelectionList serverSelectionList) {
                        return serverSelectionList;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static List<?> getChildren(ServerSelectionList list) {
        if (list == null) {
            return null;
        }

        try {
            Method method = findMethod(list.getClass(), "children");

            if (method != null) {
                method.setAccessible(true);

                Object result = method.invoke(list);

                if (result instanceof List<?> children) {
                    return children;
                }
            }
        } catch (Throwable ignored) {
        }

        Class<?> type = list.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);

                    Object value = field.get(list);

                    if (value instanceof List<?> children) {
                        return children;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static int getRowTop(ServerSelectionList list, int index) {
        Integer reflected = callIntMethodDeep(
                list,
                "getRowTop",
                new Class<?>[]{int.class},
                index
        );

        if (reflected != null) {
            return reflected;
        }

        int top = readIntFieldDeep(list, 32, "y0", "top", "field_22741", "f_93380_");
        int itemHeight = readIntFieldDeep(list, 36, "itemHeight", "field_22740", "f_93416_");

        return top + 4 + index * itemHeight;
    }

    private static ServerData findServerData(Object entry) {
        if (entry == null) {
            return null;
        }

        Class<?> type = entry.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);

                    Object value = field.get(entry);

                    if (value instanceof ServerData serverData) {
                        return serverData;
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static ReservedStatusInfo findReservedStatusInfo(ServerData serverData) {
        List<Component> playerList = findPlayerList(serverData);

        if (playerList == null || playerList.isEmpty()) {
            return null;
        }

        for (Component component : playerList) {
            if (component == null) {
                continue;
            }

            ReservedStatusInfo info = parseReservedStatusMarker(component.getString());

            if (info != null) {
                return info;
            }
        }

        return null;
    }

    private static ReservedStatusInfo parseReservedStatusMarker(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String clean = raw.replaceAll("§.", "").trim();

        if (clean.startsWith("CRS_")) {
            clean = clean.substring("CRS_".length());
        } else if (clean.startsWith("R")) {
            clean = clean.substring(1);

            if (clean.startsWith("_")) {
                clean = clean.substring(1);
            }
        } else {
            return null;
        }

        String[] parts = clean.split("_");

        if (parts.length != 4) {
            return null;
        }

        try {
            int regularOnline = parseBase36(parts[0]);
            int reservedOnline = parseBase36(parts[1]);
            int publicSlots = parseBase36(parts[2]);
            int reservedSlots = parseBase36(parts[3]);

            if (publicSlots <= 0 || reservedSlots < 0) {
                return null;
            }

            return new ReservedStatusInfo(
                    regularOnline,
                    reservedOnline,
                    publicSlots,
                    reservedSlots
            );
        } catch (Throwable ignored) {
            return null;
        }
    }
    @SuppressWarnings("unchecked")
    private static List<Component> findPlayerList(ServerData serverData) {
        if (serverData == null) {
            return null;
        }

        return serverData.playerList;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;

        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (Throwable ignored) {
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static Integer callIntMethodDeep(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... args
    ) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return null;
        }

        Class<?> type = target.getClass();

        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName, parameterTypes);
                method.setAccessible(true);

                Object result = method.invoke(target, args);

                if (result instanceof Number number) {
                    return number.intValue();
                }
            } catch (Throwable ignored) {
            }

            type = type.getSuperclass();
        }

        return null;
    }

    private static int readIntFieldDeep(Object target, int fallback, String... names) {
        if (target == null || names == null) {
            return fallback;
        }

        Class<?> type = target.getClass();

        while (type != null) {
            for (String name : names) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);

                    Object value = field.get(target);

                    if (value instanceof Number number) {
                        return number.intValue();
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return fallback;
    }

    private static int parseBase36(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }

        return Integer.parseInt(value.toLowerCase(Locale.ROOT), 36);
    }

    private record ReservedStatusInfo(
            int regularOnline,
            int reservedOnline,
            int publicSlots,
            int reservedSlots
    ) {
    }
}