package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.NotNull;

public class ClusterMonitorMenu extends AbstractContainerMenu {
    private final ClusterMonitorBlockEntity monitor;
    private final BlockPos blockPos;
    private long lastSnapshotRequestMillis;

    public ClusterMonitorMenu(int windowId, Inventory inventory, BlockPos pos) {
        this(windowId, inventory, getBlockEntity(inventory, pos));
    }

    public ClusterMonitorMenu(int windowId, Inventory inventory, ClusterMonitorBlockEntity monitor) {
        super(ClusterMonitorRegistry.CLUSTER_MONITOR_MENU.get(), windowId);
        this.monitor = monitor;
        this.blockPos = monitor.getBlockPos();
    }

    private static ClusterMonitorBlockEntity getBlockEntity(Inventory inventory, BlockPos pos) {
        BlockEntity blockEntity = inventory.player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof ClusterMonitorBlockEntity monitor)) {
            throw new IllegalStateException("Expected ClusterMonitorBlockEntity at " + pos);
        }
        return monitor;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public boolean tryBeginSnapshotRequest() {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotRequestMillis < 750L) {
            return false;
        }
        lastSnapshotRequestMillis = now;
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(blockPos) == monitor
                && player.distanceToSqr(
                blockPos.getX() + 0.5D,
                blockPos.getY() + 0.5D,
                blockPos.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
