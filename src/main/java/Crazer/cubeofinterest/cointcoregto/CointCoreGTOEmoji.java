package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CointCoreGTOEmoji {
    private static final String NETWORK_PROTOCOL_VERSION = "1";
    @SuppressWarnings({"deprecation", "removal"})
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "emoji"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static final Pattern DISCORD_CUSTOM_EMOJI_PATTERN = Pattern.compile("<a?:([A-Za-z0-9_]{2,64}):(\\d{10,32})>");
    private static final Pattern MINECRAFT_EMOJI_TOKEN_PATTERN = Pattern.compile(":([A-Za-z0-9_]{2,64}):");
    private static final Map<String, EmojiInfo> SERVER_EMOJIS_BY_NAME = new ConcurrentHashMap<>();
    private static boolean registered = false;

    private CointCoreGTOEmoji() {
    }

    public static void registerNetwork() {
        if (registered) {
            return;
        }

        CHANNEL.messageBuilder(EmojiRegistryPacket.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EmojiRegistryPacket::encode)
                .decoder(EmojiRegistryPacket::decode)
                .consumerMainThread(EmojiRegistryPacket::handle)
                .add();

        registered = true;
    }

    public static void refreshFromJda(Object jda) {
        SERVER_EMOJIS_BY_NAME.clear();

        System.out.println("[CointCoreGTOEmoji] refreshFromJda called. jda=" + (jda != null));

        if (jda == null) {
            System.out.println("[CointCoreGTOEmoji] Cannot load Discord emojis: JDA is null.");
            return;
        }

        int loaded = 0;

        try {
            Object guildsObject = jda.getClass().getMethod("getGuilds").invoke(jda);

            if (!(guildsObject instanceof List<?> guilds)) {
                System.out.println("[CointCoreGTOEmoji] Cannot load Discord emojis: getGuilds did not return List.");
                return;
            }

            System.out.println("[CointCoreGTOEmoji] guilds=" + guilds.size());

            for (Object guild : guilds) {
                if (guild == null) {
                    continue;
                }

                String guildName = getStringByMethod(guild, "getName", "unknown");
                String guildId = getStringByMethod(guild, "getId", "unknown");

                Object emojisObject = guild.getClass().getMethod("getEmojis").invoke(guild);

                if (!(emojisObject instanceof List<?> emojis)) {
                    System.out.println("[CointCoreGTOEmoji] Cannot read emojis for guild=\"" + guildName + "\" id=" + guildId);
                    continue;
                }

                System.out.println("[CointCoreGTOEmoji] guild=\"" + guildName + "\" id=" + guildId + " emojis=" + emojis.size());

                int printed = 0;
                for (Object emoji : emojis) {
                    if (emoji == null) {
                        continue;
                    }

                    String rawName = getStringByMethod(emoji, "getName", null);
                    String name = sanitizeEmojiName(rawName);
                    String id = getStringByMethod(emoji, "getId", null);
                    boolean animated = getBooleanByMethod(emoji, "isAnimated", false);

                    if (name == null || id == null || id.isBlank()) {
                        continue;
                    }

                    putServerEmoji(new EmojiInfo(name, id, animated));
                    loaded++;
                    if (printed < 30) {
                        System.out.println("[CointCoreGTOEmoji] emoji name=\"" + name + "\" id=" + id + " animated=" + animated);
                        printed++;
                    }
                }
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTOEmoji] Failed to load Discord emojis: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[CointCoreGTOEmoji] Loaded Discord emojis: " + loaded);
    }

    private static String getStringByMethod(Object target, String methodName, String fallback) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return fallback;
        }
        try {
            Object result = target.getClass().getMethod(methodName).invoke(target);
            return result == null ? fallback : String.valueOf(result);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean getBooleanByMethod(Object target, String methodName, boolean fallback) {
        if (target == null || methodName == null || methodName.isBlank()) {
            return fallback;
        }
        try {
            Object result = target.getClass().getMethod(methodName).invoke(target);
            return result instanceof Boolean value ? value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    public static void clearServerRegistry() {
        SERVER_EMOJIS_BY_NAME.clear();
    }

    public static void sendEmojiRegistry(ServerPlayer player) {
        if (player == null || SERVER_EMOJIS_BY_NAME.isEmpty()) {
            return;
        }
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new EmojiRegistryPacket(new ArrayList<>(SERVER_EMOJIS_BY_NAME.values()))
        );
    }

    public static void broadcastEmojiRegistry() {
        try {
            net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null || SERVER_EMOJIS_BY_NAME.isEmpty()) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                sendEmojiRegistry(player);
            }
        } catch (Throwable ignored) {
        }
    }

    public static String discordToMinecraft(String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        boolean changedRegistry = false;
        Matcher matcher = DISCORD_CUSTOM_EMOJI_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String name = sanitizeEmojiName(matcher.group(1));
            String id = matcher.group(2);
            boolean animated = matcher.group(0).startsWith("<a:");

            if (name == null || id == null || id.isBlank()) {
                matcher.appendReplacement(buffer, "");
                continue;
            }

            if (!SERVER_EMOJIS_BY_NAME.containsKey(name)
                    && !SERVER_EMOJIS_BY_NAME.containsKey(name.toLowerCase(Locale.ROOT))) {
                putServerEmoji(new EmojiInfo(name, id, animated));
                changedRegistry = true;
            }

            matcher.appendReplacement(buffer, Matcher.quoteReplacement(":" + name.toLowerCase(Locale.ROOT) + ":"));
        }

        matcher.appendTail(buffer);
        if (changedRegistry) {
            broadcastEmojiRegistry();
        }
        return buffer.toString();
    }

    public static String minecraftToDiscord(String message) {
        if (message == null || message.isBlank() || SERVER_EMOJIS_BY_NAME.isEmpty()) {
            return message;
        }

        Matcher matcher = MINECRAFT_EMOJI_TOKEN_PATTERN.matcher(message);
        StringBuffer buffer = new StringBuffer();

        while (matcher.find()) {
            String rawName = matcher.group(1);
            String name = sanitizeEmojiName(rawName);
            EmojiInfo info = name == null ? null : findServerEmoji(name);
            if (info == null) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(0)));
                continue;
            }

            String discord = "<" + (info.animated() ? "a" : "") + ":" + info.name() + ":" + info.id() + ">";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(discord));
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static void putServerEmoji(EmojiInfo info) {
        if (info == null || info.name() == null) {
            return;
        }
        SERVER_EMOJIS_BY_NAME.put(info.name(), info);
        SERVER_EMOJIS_BY_NAME.put(info.name().toLowerCase(Locale.ROOT), info);
    }

    private static EmojiInfo findServerEmoji(String name) {
        if (name == null) {
            return null;
        }
        EmojiInfo exact = SERVER_EMOJIS_BY_NAME.get(name);
        return exact != null ? exact : SERVER_EMOJIS_BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    static String sanitizeEmojiName(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.trim();
        return cleaned.matches("[A-Za-z0-9_]{2,64}") ? cleaned : null;
    }

    public record EmojiInfo(String name, String id, boolean animated) {
    }

    private record EmojiRegistryPacket(List<EmojiInfo> emojis) {
        private static void encode(EmojiRegistryPacket packet, FriendlyByteBuf buffer) {
            List<EmojiInfo> emojis = packet.emojis == null ? List.of() : packet.emojis.stream()
                    .sorted(Comparator.comparing(EmojiInfo::name))
                    .toList();
            buffer.writeVarInt(emojis.size());
            for (EmojiInfo emoji : emojis) {
                buffer.writeUtf(emoji.name(), 64);
                buffer.writeUtf(emoji.id(), 32);
                buffer.writeBoolean(emoji.animated());
            }
        }

        private static EmojiRegistryPacket decode(FriendlyByteBuf buffer) {
            int size = Math.min(buffer.readVarInt(), 5000);
            ArrayList<EmojiInfo> emojis = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                String name = sanitizeEmojiName(buffer.readUtf(64));
                String id = buffer.readUtf(32);
                boolean animated = buffer.readBoolean();
                if (name != null && !id.isBlank()) {
                    emojis.add(new EmojiInfo(name, id, animated));
                }
            }
            return new EmojiRegistryPacket(emojis);
        }

        private static void handle(EmojiRegistryPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointCoreGTOEmojiClient.applyClientRegistry(
                            packet.emojis == null ? List.of() : packet.emojis
                    )
            ));
            context.setPacketHandled(true);
        }
    }
}
