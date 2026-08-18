package Crazer.cubeofinterest.cointcoregto.invview;

import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InvViewMenu extends AbstractContainerMenu {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:InvView");
    private static final int TARGET_X = 12;
    private static final int TARGET_Y = 48;
    private static final int VIEWER_Y = 144;

    private final Inventory viewerInventory;
    private final ServerPlayer serverTarget;
    private final UUID targetId;
    private final String targetName;
    private final InvViewMode mode;
    private final int page;
    private final int pageCount;
    private final boolean offline;
    private final boolean editable;
    private final int targetSlotCount;
    private final List<String> targetSlotLabels;
    private final boolean selfMainView;
    private boolean registered;

    public InvViewMenu(int windowId, Inventory inventory, FriendlyByteBuf data) {
        this(
                windowId,
                inventory,
                null,
                data.readUUID(),
                data.readUtf(64),
                InvViewMode.byId(data.readVarInt()),
                data.readVarInt(),
                data.readVarInt(),
                data.readBoolean(),
                data.readBoolean(),
                data.readVarInt(),
                readLabels(data),
                List.of()
        );
    }

    private InvViewMenu(
            int windowId,
            Inventory inventory,
            ServerPlayer serverTarget,
            UUID targetId,
            String targetName,
            InvViewMode mode,
            int page,
            int pageCount,
            boolean offline,
            boolean editable,
            int targetSlotCount,
            List<String> targetSlotLabels,
            List<InvViewCuriosBridge.SlotRef> curioSlots
    ) {
        super(InvViewRegistry.MENU.get(), windowId);
        this.viewerInventory = inventory;
        this.serverTarget = serverTarget;
        this.targetId = targetId;
        this.targetName = targetName;
        this.mode = mode;
        this.page = Math.max(0, page);
        this.pageCount = Math.max(1, pageCount);
        this.offline = offline;
        this.editable = editable;
        this.targetSlotCount = Math.max(0, targetSlotCount);
        this.targetSlotLabels = List.copyOf(targetSlotLabels);
        this.selfMainView = mode == InvViewMode.MAIN && targetId.equals(inventory.player.getUUID());

        if (serverTarget == null) {
            addClientTargetSlots();
        } else {
            addServerTargetSlots(curioSlots);
        }
        if (!selfMainView) {
            addViewerInventory(inventory);
        }

        if (serverTarget != null && inventory.player instanceof ServerPlayer) {
            InvViewSessions.register(this);
            registered = true;
        }
    }

    static InvViewMenu server(
            int windowId,
            Inventory inventory,
            ServerPlayer target,
            UUID targetId,
            String targetName,
            InvViewMode mode,
            int page,
            int pageCount,
            boolean offline,
            boolean editable,
            List<InvViewCuriosBridge.SlotRef> curioSlots
    ) {
        int count = switch (mode) {
            case MAIN -> 41;
            case ENDER -> 27;
            case CURIOS -> curioSlots.size();
        };
        List<String> labels = curioSlots.stream().map(InvViewCuriosBridge.SlotRef::label).toList();
        return new InvViewMenu(
                windowId,
                inventory,
                target,
                targetId,
                targetName,
                mode,
                page,
                pageCount,
                offline,
                editable,
                count,
                labels,
                curioSlots
        );
    }

    public UUID targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public InvViewMode mode() {
        return mode;
    }

    public int page() {
        return page;
    }

    public int pageCount() {
        return pageCount;
    }

    public boolean offline() {
        return offline;
    }

    public boolean editable() {
        return editable;
    }

    public int targetSlotCount() {
        return targetSlotCount;
    }

    public boolean showsViewerInventory() {
        return !selfMainView;
    }

    public String targetSlotLabel(int menuSlot) {
        if (mode != InvViewMode.CURIOS || menuSlot < 0 || menuSlot >= targetSlotLabels.size()) {
            return "";
        }
        return targetSlotLabels.get(menuSlot);
    }

    ServerPlayer viewer() {
        return viewerInventory.player instanceof ServerPlayer player ? player : null;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        if (!(player instanceof ServerPlayer viewer) || !InvViewService.canView(viewer)) {
            return false;
        }
        MinecraftServer server = viewer.getServer();
        if (server == null) {
            return false;
        }
        ServerPlayer live = server.getPlayerList().getPlayer(targetId);
        if (offline) {
            return live == null && InvViewService.canOffline(viewer);
        }
        return live == serverTarget;
    }

    @Override
    public void removed(Player player) {
        if (!player.level().isClientSide && registered) {
            saveOfflineNow();
            InvViewSessions.unregister(this);
            registered = false;
        }
        super.removed(player);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!editable && slotId >= 0 && slotId < targetSlotCount) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (!editable || selfMainView || index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        int viewerStart = targetSlotCount;
        int viewerEnd = viewerStart + 36;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved;
        if (index < targetSlotCount) {
            moved = moveItemStackTo(stack, viewerStart, viewerEnd, false);
        } else if (index < viewerEnd && targetSlotCount > 0) {
            moved = moveItemStackTo(stack, 0, targetSlotCount, false);
        } else {
            return ItemStack.EMPTY;
        }
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        int index = slots.indexOf(slot);
        if (!editable && index >= 0 && index < targetSlotCount) {
            return false;
        }
        return super.canTakeItemForPickAll(stack, slot);
    }

    private void addClientTargetSlots() {
        SimpleContainer dummy = new SimpleContainer(targetSlotCount);
        switch (mode) {
            case MAIN -> addMainSlots(dummy, null);
            case ENDER -> addGridSlots(dummy, targetSlotCount, 3);
            case CURIOS -> addGridSlots(dummy, targetSlotCount, 4);
        }
    }

    private void addServerTargetSlots(List<InvViewCuriosBridge.SlotRef> curioSlots) {
        switch (mode) {
            case MAIN -> addMainSlots(serverTarget.getInventory(), serverTarget);
            case ENDER -> addGridSlots(serverTarget.getEnderChestInventory(), 27, 3);
            case CURIOS -> {
                for (int i = 0; i < curioSlots.size(); i++) {
                    InvViewCuriosBridge.SlotRef ref = curioSlots.get(i);
                    int x = TARGET_X + (i % 9) * 18;
                    int y = TARGET_Y + (i / 9) * 18;
                    addSlot(new TargetItemHandlerSlot(ref, x, y));
                }
            }
        }
    }

    private void addMainSlots(Container container, ServerPlayer target) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int inventoryIndex = column + row * 9 + 9;
                addSlot(new TargetSlot(container, inventoryIndex, TARGET_X + column * 18, TARGET_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new TargetSlot(container, column, TARGET_X + column * 18, TARGET_Y + 60));
        }

        EquipmentSlot[] equipment = {
                EquipmentSlot.HEAD,
                EquipmentSlot.CHEST,
                EquipmentSlot.LEGS,
                EquipmentSlot.FEET
        };
        int[] inventoryIndices = {39, 38, 37, 36};
        for (int i = 0; i < equipment.length; i++) {
            addSlot(new TargetEquipmentSlot(
                    container,
                    inventoryIndices[i],
                    TARGET_X + 174,
                    TARGET_Y + i * 18,
                    target,
                    equipment[i]
            ));
        }
        addSlot(new TargetSlot(container, 40, TARGET_X + 174, TARGET_Y + 72));
    }

    private void addGridSlots(Container container, int count, int rows) {
        int max = Math.min(count, rows * 9);
        for (int i = 0; i < max; i++) {
            addSlot(new TargetSlot(container, i, TARGET_X + (i % 9) * 18, TARGET_Y + (i / 9) * 18));
        }
    }

    private void addViewerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, TARGET_X + column * 18, VIEWER_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, TARGET_X + column * 18, VIEWER_Y + 60));
        }
    }

    private void targetChanged() {
        if (serverTarget == null) {
            return;
        }
        if (offline) {
            saveOfflineNow();
            return;
        }
        if (serverTarget != viewerInventory.player) {
            serverTarget.inventoryMenu.broadcastChanges();
            if (serverTarget.containerMenu != serverTarget.inventoryMenu) {
                serverTarget.containerMenu.broadcastChanges();
            }
        }
    }

    private void saveOfflineNow() {
        if (!offline || !editable || serverTarget == null) {
            return;
        }
        MinecraftServer server = serverTarget.getServer();
        if (server == null || server.getPlayerList().getPlayer(targetId) != null) {
            return;
        }
        try {
            CompoundTag data = serverTarget.saveWithoutId(new CompoundTag());
            Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Files.createDirectories(playerDir);
            String uuid = serverTarget.getStringUUID();
            Path temp = Files.createTempFile(playerDir, uuid + "-", ".dat");
            try (var output = Files.newOutputStream(temp)) {
                NbtIo.writeCompressed(data, output);
            }
            Path current = playerDir.resolve(uuid + ".dat");
            Path old = playerDir.resolve(uuid + ".dat_old");
            Util.safeReplaceFile(current, temp, old);
            ForgeEventFactory.firePlayerSavingEvent(serverTarget, playerDir.toFile(), uuid);
        } catch (Exception exception) {
            LOGGER.error("Failed to save offline inventory for {}", targetName, exception);
        }
    }

    private static List<String> readLabels(FriendlyByteBuf data) {
        int count = Math.max(0, Math.min(256, data.readVarInt()));
        List<String> labels = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            labels.add(data.readUtf(128));
        }
        return labels;
    }

    private class TargetSlot extends Slot {
        private TargetSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return editable && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return editable;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            targetChanged();
        }
    }

    private final class TargetEquipmentSlot extends TargetSlot {
        private final ServerPlayer target;
        private final EquipmentSlot equipmentSlot;

        private TargetEquipmentSlot(
                Container container,
                int index,
                int x,
                int y,
                ServerPlayer target,
                EquipmentSlot equipmentSlot
        ) {
            super(container, index, x, y);
            this.target = target;
            this.equipmentSlot = equipmentSlot;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            if (!editable) {
                return false;
            }
            return target == null || stack.canEquip(equipmentSlot, target);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    private final class TargetItemHandlerSlot extends SlotItemHandler {
        private TargetItemHandlerSlot(InvViewCuriosBridge.SlotRef ref, int x, int y) {
            super(ref.handler(), ref.index(), x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return editable && super.mayPlace(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return editable;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            targetChanged();
        }
    }
}
