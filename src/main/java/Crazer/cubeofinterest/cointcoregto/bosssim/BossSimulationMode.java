package Crazer.cubeofinterest.cointcoregto.bosssim;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Optional;

public enum BossSimulationMode {
    OVERWORLD("overworld_simulation", "minecraft:overworld"),
    NETHER("nether_simulation", "minecraft:the_nether"),
    END("end_simulation", "minecraft:the_end");

    public static final String MOD_ID = "cointcoregto";

    private final ResourceLocation selectorId;
    private final ResourceLocation dimensionId;

    BossSimulationMode(String selectorPath, String dimensionId) {
        this.selectorId = new ResourceLocation(MOD_ID, selectorPath);
        this.dimensionId = new ResourceLocation(dimensionId);
    }

    public ResourceLocation selectorId() {
        return selectorId;
    }

    public ResourceLocation dimensionId() {
        return dimensionId;
    }

    public Item selectorItem() {
        Item item = ForgeRegistries.ITEMS.getValue(selectorId);
        if (item == null) {
            throw new IllegalStateException("Missing selector item: " + selectorId);
        }
        return item;
    }

    public ItemStack selectorStack() {
        return new ItemStack(selectorItem());
    }

    public ServerLevel resolve(ServerLevel physicalLevel) {
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return physicalLevel.getServer().getLevel(key);
    }

    public static Optional<BossSimulationMode> fromStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Optional.empty();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return Optional.empty();
        for (BossSimulationMode mode : values()) {
            if (mode.selectorId.equals(id)) return Optional.of(mode);
        }
        return Optional.empty();
    }
}
