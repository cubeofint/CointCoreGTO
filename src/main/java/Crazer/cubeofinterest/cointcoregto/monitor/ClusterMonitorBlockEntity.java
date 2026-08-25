package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class ClusterMonitorBlockEntity extends BlockEntity implements MenuProvider {
    public ClusterMonitorBlockEntity(BlockPos pos, BlockState state) {
        super(ClusterMonitorRegistry.CLUSTER_MONITOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Кластерный монитор");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new ClusterMonitorMenu(windowId, inventory, this);
    }
}
