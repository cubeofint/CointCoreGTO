package Crazer.cubeofinterest.cointcoregto;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class CointCoreGTOItemShare {
    private static final String NETWORK_PROTOCOL_VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "item_share"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static final String QUEST_MARKER = "CointCoreGTOQuestShare";
    private static final String QUEST_ID = "QuestId";
    private static final String QUEST_CODE = "QuestCode";
    private static final String QUEST_TITLE = "QuestTitle";

    private static boolean registered = false;

    private CointCoreGTOItemShare() {
    }

    public static void registerNetwork() {
        if (registered) return;

        CHANNEL.messageBuilder(ShareItemPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ShareItemPacket::encode)
                .decoder(ShareItemPacket::decode)
                .consumerMainThread(ShareItemPacket::handle)
                .add();
        registered = true;
    }

    public static void sendToServer(ItemStack stack) {
        sendToServer(stack, ItemShareChannel.CURRENT);
    }

    public static void sendToServer(ItemStack stack, ItemShareChannel channel) {
        if (stack == null || stack.isEmpty()) return;

        String displayName = stack.getHoverName().getString();
        if (displayName == null || displayName.isBlank()) {
            displayName = stack.getItem().getDescription().getString();
        }

        CHANNEL.sendToServer(new ShareItemPacket(
                stack.copy(),
                displayName,
                channel == null ? ItemShareChannel.CURRENT : channel
        ));
    }

    public static void sendQuestToServer(Quest quest, ItemShareChannel channel) {
        if (quest == null || quest.getId() == 0L) return;

        ItemShareChannel safeChannel = switch (channel == null ? ItemShareChannel.LOCAL : channel) {
            case GLOBAL -> ItemShareChannel.GLOBAL;
            case PRIVATE -> ItemShareChannel.PRIVATE;
            default -> ItemShareChannel.LOCAL;
        };

        ItemStack marker = new ItemStack(Items.PAPER);
        CompoundTag tag = marker.getOrCreateTag();
        tag.putBoolean(QUEST_MARKER, true);
        tag.putLong(QUEST_ID, quest.getId());
        tag.putString(QUEST_CODE, quest.getCodeString());

        String title = quest.getTitle() == null ? "" : quest.getTitle().getString();
        if (title.length() > 512) {
            title = title.substring(0, 512);
        }
        tag.putString(QUEST_TITLE, title);

        CHANNEL.sendToServer(new ShareItemPacket(marker, "", safeChannel));
    }

    public static void sendIconHintToPlayer(ServerPlayer player, ItemStack stack, String prefixText, String itemText) {
    }

    private static boolean isQuestMarker(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return false;
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(QUEST_MARKER);
    }

    private static void handleQuestMarker(ServerPlayer player, ItemStack stack, ItemShareChannel channel) {
        if (player == null || stack == null || !stack.hasTag()) return;

        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.getBoolean(QUEST_MARKER)) return;

        long questId = tag.getLong(QUEST_ID);
        String questCode = tag.getString(QUEST_CODE);
        String questTitle = tag.getString(QUEST_TITLE);

        if (questId == 0L && !questCode.isBlank()) {
            questId = QuestObjectBase.parseCodeString(questCode);
        }
        if (questId == 0L) return;

        String canonicalCode = QuestObjectBase.getCodeString(questId);
        if (canonicalCode == null || canonicalCode.isBlank()) return;
        try {
            ServerQuestFile file = ServerQuestFile.INSTANCE;
            Quest serverQuest = file == null ? null : file.getQuest(questId);
            if (serverQuest != null) {
                canonicalCode = serverQuest.getCodeString();
                if (serverQuest.getTitle() != null) {
                    questTitle = serverQuest.getTitle().getString();
                }
            }
        } catch (Throwable ignored) {
        }

        if (questTitle == null) questTitle = "";
        questTitle = questTitle.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\s+", " ").trim();
        if (questTitle.length() > 512) {
            questTitle = questTitle.substring(0, 512);
        }
        if (questTitle.isBlank()) {
            questTitle = canonicalCode;
        }

        CointCoreGTO.shareQuest(player, canonicalCode, questTitle, channel);
    }

    private record ShareItemPacket(ItemStack stack, String displayName, ItemShareChannel channel) {
        private static void encode(ShareItemPacket packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.stack);
            buffer.writeUtf(packet.displayName == null ? "" : packet.displayName, 256);
            buffer.writeEnum(packet.channel == null ? ItemShareChannel.CURRENT : packet.channel);
        }

        private static ShareItemPacket decode(FriendlyByteBuf buffer) {
            return new ShareItemPacket(buffer.readItem(), buffer.readUtf(256), buffer.readEnum(ItemShareChannel.class));
        }

        private static void handle(ShareItemPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || packet.stack == null || packet.stack.isEmpty()) return;

                if (isQuestMarker(packet.stack)) {
                    handleQuestMarker(player, packet.stack, packet.channel);
                    return;
                }

                CointCoreGTO.shareItem(player, packet.stack.copy(), packet.displayName, packet.channel);
            });
            context.setPacketHandled(true);
        }
    }
}
