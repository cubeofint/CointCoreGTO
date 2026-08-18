package Crazer.cubeofinterest.cointcoregto.invview;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class InvViewSessions {
    private static final ConcurrentHashMap<UUID, Set<InvViewMenu>> BY_TARGET = new ConcurrentHashMap<>();

    private InvViewSessions() {
    }

    static void register(InvViewMenu menu) {
        BY_TARGET.computeIfAbsent(menu.targetId(), ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>())).add(menu);
    }

    static void unregister(InvViewMenu menu) {
        Set<InvViewMenu> menus = BY_TARGET.get(menu.targetId());
        if (menus == null) {
            return;
        }
        menus.remove(menu);
        if (menus.isEmpty()) {
            BY_TARGET.remove(menu.targetId(), menus);
        }
    }

    static void closeForTarget(UUID targetId) {
        Set<InvViewMenu> menus = BY_TARGET.remove(targetId);
        if (menus == null) {
            return;
        }
        for (InvViewMenu menu : new ArrayList<>(menus)) {
            ServerPlayer viewer = menu.viewer();
            if (viewer != null && viewer.containerMenu == menu) {
                viewer.closeContainer();
            }
        }
    }
}
