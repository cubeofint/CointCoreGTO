package Crazer.cubeofinterest.cointcoregto.compat.jade;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

//@WailaPlugin
public class CointBotaniaJadePlugin implements IWailaPlugin {

    private static final ResourceLocation UID =
            new ResourceLocation(CointCoreGTO.MODID, "botania_mana_numbers");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BotaniaManaProvider.INSTANCE, BlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(BotaniaManaProvider.INSTANCE, Block.class);
    }

    public enum BotaniaManaProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
        INSTANCE;

        private static final String TAG_HAS_MANA = "CointBotaniaHasMana";
        private static final String TAG_MANA = "CointBotaniaMana";
        private static final String TAG_MAX_MANA = "CointBotaniaMaxMana";

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
            BlockEntity blockEntity = accessor.getBlockEntity();

            if (blockEntity == null) {
                return;
            }

            if (!isBotaniaBlockEntity(blockEntity)) {
                return;
            }

            Integer mana = readMana(blockEntity);
            if (mana == null) {
                return;
            }

            Integer maxMana = readMaxMana(blockEntity);

            tag.putBoolean(TAG_HAS_MANA, true);
            tag.putInt(TAG_MANA, Math.max(0, mana));

            if (maxMana != null && maxMana > 0) {
                tag.putInt(TAG_MAX_MANA, maxMana);
            }
        }

        @Override
        public void appendTooltip(
                snownee.jade.api.ITooltip tooltip,
                BlockAccessor accessor,
                IPluginConfig config
        ) {
            CompoundTag tag = accessor.getServerData();

            if (!tag.getBoolean(TAG_HAS_MANA)) {
                return;
            }

            int mana = tag.getInt(TAG_MANA);

            if (tag.contains(TAG_MAX_MANA)) {
                int maxMana = tag.getInt(TAG_MAX_MANA);

                tooltip.add(Component.literal(
                        "§bMana: §f"
                                + formatNumber(mana)
                                + " §7/ §f"
                                + formatNumber(maxMana)
                ));
            } else {
                tooltip.add(Component.literal(
                        "§bMana: §f" + formatNumber(mana)
                ));
            }
        }

        private static boolean isBotaniaBlockEntity(BlockEntity blockEntity) {
            try {
                String className = blockEntity.getClass().getName().toLowerCase(Locale.ROOT);
                if (className.contains("botania")) {
                    return true;
                }

                if (blockEntity.getBlockState() != null && blockEntity.getBlockState().getBlock() != null) {
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(blockEntity.getBlockState().getBlock());
                    return id != null && "botania".equals(id.getNamespace());
                }
            } catch (Throwable ignored) {
            }

            return false;
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

        private static String formatNumber(int value) {
            return String.format(Locale.ROOT, "%,d", value).replace(",", " ");
        }
    }
}