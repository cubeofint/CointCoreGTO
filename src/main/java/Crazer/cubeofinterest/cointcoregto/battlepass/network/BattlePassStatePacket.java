package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.battlepass.client.BattlePassClientHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record BattlePassStatePacket(
        boolean enabled,
        String title,
        String premiumLabel,
        int visibleDays,
        int streak,
        boolean premiumUnlocked,
        String statusMessage,
        List<DayState> days
) {
    public BattlePassStatePacket {
        title = title == null ? "Battle Pass" : title;
        premiumLabel = premiumLabel == null ? "Premium" : premiumLabel;
        statusMessage = statusMessage == null ? "" : statusMessage;
        days = List.copyOf(days == null ? List.of() : days);
    }

    public static void encode(BattlePassStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled);
        buffer.writeUtf(packet.title, 128);
        buffer.writeUtf(packet.premiumLabel, 128);
        buffer.writeVarInt(packet.visibleDays);
        buffer.writeVarInt(packet.streak);
        buffer.writeBoolean(packet.premiumUnlocked);
        buffer.writeUtf(packet.statusMessage, 256);
        buffer.writeVarInt(packet.days.size());
        for (DayState day : packet.days) {
            writeStacks(buffer, day.freeRewards());
            writeStacks(buffer, day.premiumRewards());
            buffer.writeBoolean(day.freeClaimed());
            buffer.writeBoolean(day.premiumClaimed());
        }
    }

    public static BattlePassStatePacket decode(FriendlyByteBuf buffer) {
        boolean enabled = buffer.readBoolean();
        String title = buffer.readUtf(128);
        String premiumLabel = buffer.readUtf(128);
        int visibleDays = buffer.readVarInt();
        int streak = buffer.readVarInt();
        boolean premiumUnlocked = buffer.readBoolean();
        String statusMessage = buffer.readUtf(256);
        int dayCount = Math.max(0, Math.min(60, buffer.readVarInt()));
        List<DayState> days = new ArrayList<>(dayCount);
        for (int index = 0; index < dayCount; index++) {
            days.add(new DayState(
                    readStacks(buffer),
                    readStacks(buffer),
                    buffer.readBoolean(),
                    buffer.readBoolean()
            ));
        }
        return new BattlePassStatePacket(
                enabled,
                title,
                premiumLabel,
                visibleDays,
                streak,
                premiumUnlocked,
                statusMessage,
                days
        );
    }

    public static void handle(BattlePassStatePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> BattlePassClientHandler.handle(packet)
        ));
        context.setPacketHandled(true);
    }

    private static void writeStacks(FriendlyByteBuf buffer, List<ItemStack> stacks) {
        int size = Math.min(16, stacks == null ? 0 : stacks.size());
        buffer.writeVarInt(size);
        for (int index = 0; index < size; index++) {
            buffer.writeItem(stacks.get(index));
        }
    }

    private static List<ItemStack> readStacks(FriendlyByteBuf buffer) {
        int size = Math.max(0, Math.min(16, buffer.readVarInt()));
        List<ItemStack> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            result.add(buffer.readItem());
        }
        return result;
    }

    public record DayState(
            List<ItemStack> freeRewards,
            List<ItemStack> premiumRewards,
            boolean freeClaimed,
            boolean premiumClaimed
    ) {
        public DayState {
            freeRewards = copyStacks(freeRewards);
            premiumRewards = copyStacks(premiumRewards);
        }

        private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
            List<ItemStack> copy = new ArrayList<>();
            if (stacks != null) {
                for (ItemStack stack : stacks) {
                    if (stack != null && !stack.isEmpty()) {
                        copy.add(stack.copy());
                    }
                }
            }
            return List.copyOf(copy);
        }
    }
}
