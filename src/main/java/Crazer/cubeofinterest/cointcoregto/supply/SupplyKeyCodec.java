package Crazer.cubeofinterest.cointcoregto.supply;

import appeng.api.stacks.AEKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

public final class SupplyKeyCodec {
    private SupplyKeyCodec() {
    }

    public static String encode(AEKey key) {
        if (key == null) {
            throw new IllegalArgumentException("AE key cannot be null");
        }
        return key.toTagGeneric().toString();
    }

    public static AEKey decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AE key payload is empty");
        }

        try {
            CompoundTag tag = TagParser.parseTag(value);
            AEKey key = AEKey.fromTagGeneric(tag);
            if (key == null) {
                throw new IllegalArgumentException("Unsupported AE key payload");
            }
            return key;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AE key payload", exception);
        }
    }
}
