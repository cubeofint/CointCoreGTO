package Crazer.cubeofinterest.cointcoregto;

import com.mojang.blaze3d.platform.NativeImage;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOEmoji {
    private static final String NETWORK_PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "emoji"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static final Pattern DISCORD_CUSTOM_EMOJI_PATTERN = Pattern.compile("<a?:([A-Za-z0-9_]{2,64}):(\\d{10,32})>");
    private static final Pattern MINECRAFT_EMOJI_TOKEN_PATTERN = Pattern.compile(":([A-Za-z0-9_]{2,64}):");

    private static final Map<String, EmojiInfo> SERVER_EMOJIS_BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, EmojiInfo> CLIENT_EMOJIS_BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, ClientEmojiTexture> CLIENT_TEXTURES = new ConcurrentHashMap<>();

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

    public static void refreshFromJda(JDA jda) {
        SERVER_EMOJIS_BY_NAME.clear();

        System.out.println("[CointCoreGTOEmoji] refreshFromJda called. jda=" + (jda != null));

        if (jda == null) {
            System.out.println("[CointCoreGTOEmoji] Cannot load Discord emojis: JDA is null.");
            return;
        }

        int loaded = 0;

        try {
            List<Guild> guilds = jda.getGuilds();
            System.out.println("[CointCoreGTOEmoji] guilds=" + guilds.size());

            for (Guild guild : guilds) {
                if (guild == null) {
                    continue;
                }

                List<RichCustomEmoji> emojis = guild.getEmojis();
                System.out.println("[CointCoreGTOEmoji] guild=\"" + guild.getName() + "\" id=" + guild.getId() + " emojis=" + emojis.size());

                int printed = 0;

                for (RichCustomEmoji emoji : emojis) {
                    if (emoji == null) {
                        continue;
                    }

                    String name = sanitizeEmojiName(emoji.getName());
                    String id = emoji.getId();

                    if (name == null || id == null || id.isBlank()) {
                        System.out.println("[CointCoreGTOEmoji] skipped emoji rawName=\"" + emoji.getName() + "\" id=" + id);
                        continue;
                    }

                    EmojiInfo info = new EmojiInfo(name, id, emoji.isAnimated());
                    putServerEmoji(info);
                    loaded++;

                    if (printed < 30) {
                        System.out.println("[CointCoreGTOEmoji] emoji name=\"" + name + "\" id=" + id + " animated=" + emoji.isAnimated());
                        printed++;
                    }
                }

                if (emojis.size() > printed) {
                    System.out.println("[CointCoreGTOEmoji] guild=\"" + guild.getName() + "\" printed " + printed + "/" + emojis.size() + " emojis.");
                }
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTOEmoji] Failed to load Discord emojis: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("[CointCoreGTOEmoji] Loaded Discord emojis: " + loaded);
    }

    public static void clearServerRegistry() {
        SERVER_EMOJIS_BY_NAME.clear();
    }

    public static void sendEmojiRegistry(ServerPlayer player) {
        if (player == null) {
            return;
        }

        if (SERVER_EMOJIS_BY_NAME.isEmpty()) {
            System.out.println("[CointCoreGTOEmoji] sendEmojiRegistry skipped for " + player.getGameProfile().getName() + ": registry is empty.");
            return;
        }

        try {
            System.out.println("[CointCoreGTOEmoji] Sending emoji registry to " + player.getGameProfile().getName() + ": " + SERVER_EMOJIS_BY_NAME.size());
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new EmojiRegistryPacket(new ArrayList<>(SERVER_EMOJIS_BY_NAME.values()))
            );
        } catch (Throwable error) {
            System.out.println("[CointCoreGTOEmoji] Failed to send emoji registry to " + player.getGameProfile().getName() + ": " + error.getMessage());
        }
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

            if (name != null && id != null && !id.isBlank()) {
                if (!SERVER_EMOJIS_BY_NAME.containsKey(name)) {
                    putServerEmoji(new EmojiInfo(name, id, animated));
                    changedRegistry = true;
                    System.out.println("[CointCoreGTOEmoji] discordToMinecraft: learned emoji name=\"" + name + "\" id=" + id + " animated=" + animated);
                }

                matcher.appendReplacement(buffer, Matcher.quoteReplacement(":" + name + ":"));
            }
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
                System.out.println("[CointCoreGTOEmoji] minecraftToDiscord: no emoji id for :" + rawName + ": registrySize=" + SERVER_EMOJIS_BY_NAME.size());
                continue;
            }

            String discordEmoji = (info.animated() ? "<a:" : "<:") + info.name() + ":" + info.id() + ">";
            System.out.println("[CointCoreGTOEmoji] minecraftToDiscord: :" + rawName + ": -> " + discordEmoji);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(discordEmoji));
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (mc == null || mc.options == null || mc.options.hideGui || mc.font == null || mc.gui == null) {
            return;
        }

        if (CLIENT_EMOJIS_BY_NAME.isEmpty()) {
            return;
        }

        ChatComponent chat = mc.gui.getChat();
        if (chat == null) {
            return;
        }

        List<String> visibleLines = getVisibleChatLines(chat);
        if (visibleLines.isEmpty()) {
            return;
        }

        int screenHeight = mc.getWindow().getGuiScaledHeight();
        int lineHeight = 9;
        int chatBottom = screenHeight - 40;
        int chatHeight = getChatHeight(chat);
        int maxVisibleLines = Math.max(1, chatHeight / lineHeight);
        int count = Math.min(visibleLines.size(), maxVisibleLines);

        GuiGraphics graphics = event.getGuiGraphics();

        for (int lineIndex = 0; lineIndex < count; lineIndex++) {
            String lineText = visibleLines.get(lineIndex);
            if (lineText == null || lineText.isBlank()) {
                continue;
            }

            for (EmojiMatch match : findEmojiMatches(lineText)) {
                ClientEmojiTexture texture = getOrLoadClientTexture(match.info());
                if (texture == null || texture.location() == null || !texture.ready()) {
                    continue;
                }

                int beforeWidth = mc.font.width(lineText.substring(0, match.start()));
                int textY = chatBottom - (lineIndex + 1) * lineHeight;
                int emojiX = 4 + beforeWidth;
                int emojiY = textY;

                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 2100.0F);
                graphics.blit(texture.location(), emojiX, emojiY, 0, 0, 8, 8, 8, 8);
                graphics.pose().popPose();
            }
        }
    }

    private static List<EmojiMatch> findEmojiMatches(String lineText) {
        ArrayList<EmojiMatch> result = new ArrayList<>();

        Matcher matcher = MINECRAFT_EMOJI_TOKEN_PATTERN.matcher(lineText);
        while (matcher.find()) {
            String name = sanitizeEmojiName(matcher.group(1));
            EmojiInfo info = name == null ? null : findClientEmoji(name);

            if (info != null) {
                result.add(new EmojiMatch(info, matcher.start(), matcher.end()));
            }
        }

        return result;
    }

    private static ClientEmojiTexture getOrLoadClientTexture(EmojiInfo info) {
        if (info == null) {
            return null;
        }

        ClientEmojiTexture existing = CLIENT_TEXTURES.get(info.name());
        if (existing != null) {
            return existing;
        }

        ClientEmojiTexture loading = new ClientEmojiTexture(null, false);
        ClientEmojiTexture previous = CLIENT_TEXTURES.putIfAbsent(info.name(), loading);

        if (previous != null) {
            return previous;
        }

        Thread thread = new Thread(() -> loadEmojiTexture(info), "CointCoreGTO-Emoji-" + info.name());
        thread.setDaemon(true);
        thread.start();

        return loading;
    }

    private static void loadEmojiTexture(EmojiInfo info) {
        try {
            String extension = info.animated() ? "gif" : "png";
            String url = "https://cdn.discordapp.com/emojis/" + info.id() + "." + extension + "?size=32&quality=lossless";
            System.out.println("[CointCoreGTOEmoji] Client loading emoji texture :" + info.name() + ": from " + url);

            try (InputStream inputStream = new URL(url).openStream()) {
                NativeImage image = NativeImage.read(inputStream);

                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture texture = new DynamicTexture(image);
                        ResourceLocation location = Minecraft.getInstance().getTextureManager().register(
                                "cointcoregto/emoji/" + texturePathName(info.name()) + "_" + info.id(),
                                texture
                        );

                        CLIENT_TEXTURES.put(info.name(), new ClientEmojiTexture(location, true));
                        CLIENT_TEXTURES.put(info.name().toLowerCase(Locale.ROOT), new ClientEmojiTexture(location, true));
                        System.out.println("[CointCoreGTOEmoji] Client loaded emoji texture :" + info.name() + ": -> " + location);
                    } catch (Throwable e) {
                        CLIENT_TEXTURES.remove(info.name());
                        CLIENT_TEXTURES.remove(info.name().toLowerCase(Locale.ROOT));
                        System.out.println("[CointCoreGTOEmoji] Client failed to register emoji texture :" + info.name() + ": " + e.getClass().getName() + ": " + e.getMessage());
                    }
                });
            }
        } catch (Throwable error) {
            System.out.println("[CointCoreGTOEmoji] Client failed to load emoji texture :" + info.name() + ": " + error.getClass().getName() + ": " + error.getMessage());
            if (info.animated()) {
                tryLoadStaticPngForAnimated(info);
            } else {
                CLIENT_TEXTURES.remove(info.name());
                CLIENT_TEXTURES.remove(info.name().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static void tryLoadStaticPngForAnimated(EmojiInfo info) {
        try {
            String url = "https://cdn.discordapp.com/emojis/" + info.id() + ".png?size=32&quality=lossless";

            try (InputStream inputStream = new URL(url).openStream()) {
                NativeImage image = NativeImage.read(inputStream);

                Minecraft.getInstance().execute(() -> {
                    try {
                        DynamicTexture texture = new DynamicTexture(image);
                        ResourceLocation location = Minecraft.getInstance().getTextureManager().register(
                                "cointcoregto/emoji/" + texturePathName(info.name()) + "_" + info.id(),
                                texture
                        );

                        CLIENT_TEXTURES.put(info.name(), new ClientEmojiTexture(location, true));
                        CLIENT_TEXTURES.put(info.name().toLowerCase(Locale.ROOT), new ClientEmojiTexture(location, true));
                        System.out.println("[CointCoreGTOEmoji] Client loaded emoji texture :" + info.name() + ": -> " + location);
                    } catch (Throwable e) {
                        CLIENT_TEXTURES.remove(info.name());
                    }
                });
            }
        } catch (Throwable ignored) {
            CLIENT_TEXTURES.remove(info.name());
        }
    }

    private static void applyClientRegistry(Collection<EmojiInfo> emojis) {
        CLIENT_EMOJIS_BY_NAME.clear();
        CLIENT_TEXTURES.clear();

        for (EmojiInfo emoji : emojis) {
            if (emoji == null || emoji.name() == null || emoji.id() == null) {
                continue;
            }

            putClientEmoji(emoji);
        }

        System.out.println("[CointCoreGTOEmoji] Client received Discord emojis: " + CLIENT_EMOJIS_BY_NAME.size());
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
        if (exact != null) {
            return exact;
        }

        return SERVER_EMOJIS_BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    private static void putClientEmoji(EmojiInfo info) {
        if (info == null || info.name() == null) {
            return;
        }

        CLIENT_EMOJIS_BY_NAME.put(info.name(), info);
        CLIENT_EMOJIS_BY_NAME.put(info.name().toLowerCase(Locale.ROOT), info);
    }

    private static EmojiInfo findClientEmoji(String name) {
        if (name == null) {
            return null;
        }

        EmojiInfo exact = CLIENT_EMOJIS_BY_NAME.get(name);
        if (exact != null) {
            return exact;
        }

        return CLIENT_EMOJIS_BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    private static String texturePathName(String name) {
        String safe = name == null ? "emoji" : name.toLowerCase(Locale.ROOT);
        safe = safe.replaceAll("[^a-z0-9_./-]", "_");
        return safe.isBlank() ? "emoji" : safe;
    }

    private static String sanitizeEmojiName(String name) {
        if (name == null) {
            return null;
        }

        String cleaned = name.trim();

        if (!cleaned.matches("[A-Za-z0-9_]{2,64}")) {
            return null;
        }

        return cleaned;
    }

    private static List<String> getVisibleChatLines(ChatComponent chat) {
        ArrayList<String> lines = new ArrayList<>();

        try {
            Field trimmedMessagesField = findField(ChatComponent.class, "trimmedMessages", "f_93762_", "field_2064");
            if (trimmedMessagesField == null) {
                return lines;
            }

            trimmedMessagesField.setAccessible(true);

            Object rawValue = trimmedMessagesField.get(chat);
            if (!(rawValue instanceof List<?> rawLines) || rawLines.isEmpty()) {
                return lines;
            }

            int lineHeight = 9;
            int chatHeight = getChatHeight(chat);
            int maxVisibleLines = Math.max(1, chatHeight / lineHeight);
            int scroll = Math.max(0, getChatScroll(chat));
            int end = Math.min(rawLines.size(), scroll + maxVisibleLines + 2);

            for (int i = scroll; i < end; i++) {
                lines.add(lineToString(rawLines.get(i)));
            }
        } catch (Throwable ignored) {
        }

        return lines;
    }

    private static String lineToString(Object line) {
        if (line == null) {
            return "";
        }

        try {
            Object content;

            try {
                content = line.getClass().getMethod("content").invoke(line);
            } catch (Throwable ignored) {
                Field contentField = findField(line.getClass(), "content", "f_242248_", "field_40678");

                if (contentField == null) {
                    return "";
                }

                contentField.setAccessible(true);
                content = contentField.get(line);
            }

            if (content instanceof FormattedCharSequence sequence) {
                StringBuilder builder = new StringBuilder();

                sequence.accept((index, style, codePoint) -> {
                    builder.appendCodePoint(codePoint);
                    return true;
                });

                return builder.toString();
            }

            if (content instanceof net.minecraft.network.chat.Component component) {
                return component.getString();
            }

            return content == null ? "" : content.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int getChatScroll(ChatComponent chat) {
        try {
            Field field = findField(ChatComponent.class, "chatScrollbarPos", "scrollPos", "f_93764_", "field_2065");
            if (field == null) {
                return 0;
            }

            field.setAccessible(true);
            return Math.max(0, field.getInt(chat));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int getChatHeight(ChatComponent chat) {
        try {
            Object value = ChatComponent.class.getMethod("getHeight").invoke(chat);
            if (value instanceof Integer integer) {
                return Math.max(1, integer);
            }
        } catch (Throwable ignored) {
        }

        return 180;
    }

    private static Field findField(Class<?> type, String... names) {
        Class<?> current = type;

        while (current != null) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private record EmojiInfo(String name, String id, boolean animated) {
    }

    private record ClientEmojiTexture(ResourceLocation location, boolean ready) {
    }

    private record EmojiMatch(EmojiInfo info, int start, int end) {
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
                String name = buffer.readUtf(64);
                String id = buffer.readUtf(32);
                boolean animated = buffer.readBoolean();

                String cleanName = sanitizeEmojiName(name);
                if (cleanName != null && id != null && !id.isBlank()) {
                    emojis.add(new EmojiInfo(cleanName, id, animated));
                }
            }

            return new EmojiRegistryPacket(emojis);
        }

        private static void handle(EmojiRegistryPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> applyClientRegistry(packet.emojis == null ? List.of() : packet.emojis));
            context.setPacketHandled(true);
        }
    }
}
