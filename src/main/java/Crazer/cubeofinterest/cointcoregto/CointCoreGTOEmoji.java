package Crazer.cubeofinterest.cointcoregto;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiFunction;
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
    @SuppressWarnings({"deprecation", "removal"})
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "emoji"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static final Pattern DISCORD_CUSTOM_EMOJI_PATTERN = Pattern.compile("<a?:([A-Za-z0-9_]{2,64}):(\\d{10,32})>");
    private static final Pattern MINECRAFT_EMOJI_TOKEN_PATTERN = Pattern.compile(":([A-Za-z0-9_]{2,64}):");
    private static final String EMOJI_INSERTION_PREFIX = "cointcoregto:emoji:";
    private static final int CHAT_MESSAGE_LIFETIME_TICKS = 200;
    private static final int AUTOCOMPLETE_MAX_ROWS = 10;
    private static final int CHAT_INPUT_EMOJI_SIZE = 8;
    private static final String CHAT_INPUT_EMOJI_PLACEHOLDER = "  ";
    private static final int EMOJI_PICKER_BUTTON_SIZE = 12;
    private static final int EMOJI_PICKER_COLUMNS = 8;
    private static final int EMOJI_PICKER_ROWS = 5;
    private static final int EMOJI_PICKER_CELL_SIZE = 20;
    private static final int EMOJI_PICKER_HEADER_HEIGHT = 17;
    private static final int EMOJI_PICKER_FOOTER_HEIGHT = 13;

    private static final Map<String, EmojiInfo> SERVER_EMOJIS_BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, EmojiInfo> CLIENT_EMOJIS_BY_NAME = new ConcurrentHashMap<>();
    private static final Map<String, ClientEmojiTexture> CLIENT_TEXTURES = new ConcurrentHashMap<>();
    private static volatile List<EmojiInfo> CLIENT_AUTOCOMPLETE_MATCHES = List.of();
    private static int clientAutocompleteSelection = 0;
    private static String clientAutocompleteSignature = "";
    private static int clientAutocompleteTokenStart = -1;
    private static int clientAutocompleteTokenEnd = -1;
    private static int clientAutocompleteBoxX = -1;
    private static int clientAutocompleteBoxY = -1;
    private static int clientAutocompleteBoxWidth = 0;
    private static int clientAutocompleteRowHeight = 0;
    private static boolean clientEmojiPickerOpen = false;
    private static int clientEmojiPickerPage = 0;
    private static int clientPickerButtonX = -1;
    private static int clientPickerButtonY = -1;
    private static int clientPickerPanelX = -1;
    private static int clientPickerPanelY = -1;
    private static int clientPickerPanelWidth = 0;
    private static int clientPickerPanelHeight = 0;
    private static EditBox clientFormattedInput;
    private static BiFunction<String, Integer, FormattedCharSequence> clientInstalledFormatter;

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
                    System.out.println("[CointCoreGTOEmoji] guild=\"" + guildName + "\" id=" + guildId + " emojis unavailable.");
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
                        System.out.println("[CointCoreGTOEmoji] skipped emoji rawName=\"" + rawName + "\" id=" + id);
                        continue;
                    }

                    EmojiInfo info = new EmojiInfo(name, id, animated);
                    putServerEmoji(info);
                    loaded++;

                    if (printed < 30) {
                        System.out.println("[CointCoreGTOEmoji] emoji name=\"" + name + "\" id=" + id + " animated=" + animated);
                        printed++;
                    }
                }

                if (emojis.size() > printed) {
                    System.out.println("[CointCoreGTOEmoji] guild=\"" + guildName + "\" printed " + printed + "/" + emojis.size() + " emojis.");
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

            if (result instanceof Boolean value) {
                return value;
            }

            return fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
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

            if (name == null || id == null || id.isBlank()) {
                matcher.appendReplacement(buffer, "");
                continue;
            }

            if (!SERVER_EMOJIS_BY_NAME.containsKey(name)
                    && !SERVER_EMOJIS_BY_NAME.containsKey(name.toLowerCase(Locale.ROOT))) {
                putServerEmoji(new EmojiInfo(name, id, animated));
                changedRegistry = true;
                System.out.println("[CointCoreGTOEmoji] discordToMinecraft: learned emoji name=\"" + name + "\" id=" + id + " animated=" + animated);
            }

            String minecraftEmojiName = name.toLowerCase(Locale.ROOT);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(":" + minecraftEmojiName + ":"));
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
    public static void onClientChatReceived(ClientChatReceivedEvent event) {
        if (event == null || event.getMessage() == null || CLIENT_EMOJIS_BY_NAME.isEmpty()) {
            return;
        }

        Component replaced = replaceEmojiTokensForClient(event.getMessage());
        if (replaced != null) {
            event.setMessage(replaced);
        }
    }

    private static Component replaceEmojiTokensForClient(Component message) {
        MutableComponent result = Component.empty();
        boolean[] changed = {false};

        message.visit((style, text) -> {
            if (text == null || text.isEmpty()) {
                return Optional.empty();
            }

            Matcher matcher = MINECRAFT_EMOJI_TOKEN_PATTERN.matcher(text);
            int last = 0;

            while (matcher.find()) {
                String name = sanitizeEmojiName(matcher.group(1));
                EmojiInfo info = name == null ? null : findClientEmoji(name);

                if (info == null && name != null) {
                    info = findClientEmoji(name.toLowerCase(Locale.ROOT));
                }

                if (info == null) {
                    continue;
                }

                if (matcher.start() > last) {
                    result.append(Component.literal(text.substring(last, matcher.start())).setStyle(style));
                }

                getOrLoadClientTexture(info);
                Style emojiStyle = style.withInsertion(EMOJI_INSERTION_PREFIX + info.name());
                result.append(Component.literal(" ").setStyle(emojiStyle));
                result.append(Component.literal(" ").setStyle(style));
                last = matcher.end();
                changed[0] = true;
            }

            if (last < text.length()) {
                result.append(Component.literal(text.substring(last)).setStyle(style));
            }

            return Optional.empty();
        }, Style.EMPTY);

        return changed[0] ? result : message;
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

        List<VisibleChatLine> visibleLines = getVisibleChatLines(chat);
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
        int currentGuiTick = mc.gui.getGuiTicks();
        boolean chatFocused = mc.screen instanceof ChatScreen;

        for (int lineIndex = 0; lineIndex < count; lineIndex++) {
            VisibleChatLine line = visibleLines.get(lineIndex);
            if (line == null || line.text() == null || line.emojis().isEmpty()) {
                continue;
            }

            float lineAlpha = getChatLineAlpha(mc, currentGuiTick, line.addedTime(), chatFocused);
            if (lineAlpha <= 0.01F) {
                continue;
            }

            for (EmojiVisual emoji : line.emojis()) {
                ClientEmojiTexture texture = getOrLoadClientTexture(emoji.info());
                if (texture == null || texture.location() == null || !texture.ready()) {
                    continue;
                }

                int safeIndex = Math.max(0, Math.min(emoji.charIndex(), line.text().length()));
                int beforeWidth = mc.font.width(line.text().substring(0, safeIndex));
                int textY = chatBottom - (lineIndex + 1) * lineHeight;
                int emojiX = 4 + beforeWidth;
                int emojiY = textY;

                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 2100.0F);
                graphics.setColor(1.0F, 1.0F, 1.0F, lineAlpha);
                graphics.blit(texture.location(), emojiX, emojiY, 0, 0, 8, 8, 8, 8);
                graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                graphics.pose().popPose();
            }
        }
    }

    private static float getChatLineAlpha(Minecraft mc, int currentGuiTick, int addedTime, boolean chatFocused) {
        double chatOpacity = mc.options.chatOpacity().get() * 0.9D + 0.1D;

        if (chatFocused || addedTime < 0) {
            return (float) chatOpacity;
        }

        int age = Math.max(0, currentGuiTick - addedTime);
        if (age >= CHAT_MESSAGE_LIFETIME_TICKS) {
            return 0.0F;
        }

        double fade = (double) age / (double) CHAT_MESSAGE_LIFETIME_TICKS;
        fade = 1.0D - fade;
        fade *= 10.0D;
        fade = Math.max(0.0D, Math.min(1.0D, fade));
        fade *= fade;

        return (float) (fade * chatOpacity);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChatScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ChatScreen chatScreen)) {
            return;
        }

        disableEmojifulUi(chatScreen);
        EditBox input = findChatInput(chatScreen);
        if (input != null) {
            prepareClientChatInput(chatScreen, input);
        }

        clientEmojiPickerOpen = false;
        clientEmojiPickerPage = 0;
        clearClientAutocomplete();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBeforeChatScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen chatScreen)) {
            return;
        }
        disableEmojifulUi(chatScreen);

        EditBox input = findChatInput(chatScreen);
        if (input != null) {
            prepareClientChatInput(chatScreen, input);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChatScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen chatScreen)) {
            clearClientAutocomplete();
            clientEmojiPickerOpen = false;
            return;
        }

        disableEmojifulUi(chatScreen);

        EditBox input = findChatInput(chatScreen);
        if (input == null) {
            return;
        }

        prepareClientChatInput(chatScreen, input);

        int key = event.getKeyCode();
        if (clientEmojiPickerOpen && key == GLFW.GLFW_KEY_ESCAPE) {
            clientEmojiPickerOpen = false;
            event.setCanceled(true);
            return;
        }

        if (!refreshClientAutocomplete(input)) {
            return;
        }

        if (key == GLFW.GLFW_KEY_UP) {
            clientAutocompleteSelection = Math.floorMod(clientAutocompleteSelection - 1, CLIENT_AUTOCOMPLETE_MATCHES.size());
            event.setCanceled(true);
            return;
        }

        if (key == GLFW.GLFW_KEY_DOWN) {
            clientAutocompleteSelection = Math.floorMod(clientAutocompleteSelection + 1, CLIENT_AUTOCOMPLETE_MATCHES.size());
            event.setCanceled(true);
            return;
        }

        if (key == GLFW.GLFW_KEY_TAB || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            applyClientAutocomplete(input);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChatScreenMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen chatScreen)
                || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }

        disableEmojifulUi(chatScreen);

        EditBox input = findChatInput(chatScreen);
        if (input == null) {
            return;
        }

        prepareClientChatInput(chatScreen, input);

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        if (isInside(mouseX, mouseY,
                clientPickerButtonX,
                clientPickerButtonY,
                EMOJI_PICKER_BUTTON_SIZE,
                EMOJI_PICKER_BUTTON_SIZE)) {
            clientEmojiPickerOpen = !clientEmojiPickerOpen;
            if (clientEmojiPickerOpen) {
                clampClientEmojiPickerPage();
            }
            input.setFocused(true);
            event.setCanceled(true);
            return;
        }

        if (clientEmojiPickerOpen && isInside(mouseX, mouseY,
                clientPickerPanelX,
                clientPickerPanelY,
                clientPickerPanelWidth,
                clientPickerPanelHeight)) {
            List<EmojiInfo> emojis = getSortedClientEmojis();
            int pageSize = EMOJI_PICKER_COLUMNS * EMOJI_PICKER_ROWS;
            int pageCount = Math.max(1, (emojis.size() + pageSize - 1) / pageSize);

            int previousX = clientPickerPanelX + 5;
            int nextX = clientPickerPanelX + clientPickerPanelWidth - 14;
            if (pageCount > 1 && isInside(mouseX, mouseY, previousX, clientPickerPanelY + 3, 10, 11)) {
                clientEmojiPickerPage = Math.floorMod(clientEmojiPickerPage - 1, pageCount);
                event.setCanceled(true);
                return;
            }
            if (pageCount > 1 && isInside(mouseX, mouseY, nextX, clientPickerPanelY + 3, 10, 11)) {
                clientEmojiPickerPage = Math.floorMod(clientEmojiPickerPage + 1, pageCount);
                event.setCanceled(true);
                return;
            }

            int gridX = clientPickerPanelX + 4;
            int gridY = clientPickerPanelY + EMOJI_PICKER_HEADER_HEIGHT;
            int column = (int) ((mouseX - gridX) / EMOJI_PICKER_CELL_SIZE);
            int row = (int) ((mouseY - gridY) / EMOJI_PICKER_CELL_SIZE);

            if (column >= 0 && column < EMOJI_PICKER_COLUMNS
                    && row >= 0 && row < EMOJI_PICKER_ROWS) {
                int index = clientEmojiPickerPage * pageSize + row * EMOJI_PICKER_COLUMNS + column;
                if (index >= 0 && index < emojis.size()) {
                    EmojiInfo info = emojis.get(index);
                    input.insertText(":" + info.name().toLowerCase(Locale.ROOT) + ":");
                    input.setFocused(true);
                    clearClientAutocomplete();
                }
            }

            event.setCanceled(true);
            return;
        }

        if (!CLIENT_AUTOCOMPLETE_MATCHES.isEmpty()
                && clientAutocompleteBoxWidth > 0
                && clientAutocompleteRowHeight > 0
                && isInside(mouseX, mouseY,
                clientAutocompleteBoxX,
                clientAutocompleteBoxY,
                clientAutocompleteBoxWidth,
                4 + CLIENT_AUTOCOMPLETE_MATCHES.size() * clientAutocompleteRowHeight)) {
            int row = (int) ((mouseY - clientAutocompleteBoxY - 2) / clientAutocompleteRowHeight);
            if (row >= 0 && row < CLIENT_AUTOCOMPLETE_MATCHES.size()) {
                clientAutocompleteSelection = row;
                applyClientAutocomplete(input);
                input.setFocused(true);
                event.setCanceled(true);
                return;
            }
        }

        if (clientEmojiPickerOpen) {
            clientEmojiPickerOpen = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChatScreenMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen) || !clientEmojiPickerOpen) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();
        if (!isInside(mouseX, mouseY,
                clientPickerPanelX,
                clientPickerPanelY,
                clientPickerPanelWidth,
                clientPickerPanelHeight)) {
            return;
        }

        List<EmojiInfo> emojis = getSortedClientEmojis();
        int pageSize = EMOJI_PICKER_COLUMNS * EMOJI_PICKER_ROWS;
        int pageCount = Math.max(1, (emojis.size() + pageSize - 1) / pageSize);
        if (pageCount <= 1) {
            event.setCanceled(true);
            return;
        }

        if (event.getScrollDelta() > 0.0D) {
            clientEmojiPickerPage = Math.floorMod(clientEmojiPickerPage - 1, pageCount);
        } else if (event.getScrollDelta() < 0.0D) {
            clientEmojiPickerPage = Math.floorMod(clientEmojiPickerPage + 1, pageCount);
        }

        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onRenderChatAutocomplete(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen chatScreen)) {
            clearClientAutocomplete();
            clientEmojiPickerOpen = false;
            resetClientEmojiUiBounds();
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null || CLIENT_EMOJIS_BY_NAME.isEmpty()) {
            clearClientAutocomplete();
            clientEmojiPickerOpen = false;
            resetClientEmojiUiBounds();
            return;
        }

        EditBox input = findChatInput(chatScreen);
        if (input == null) {
            return;
        }

        prepareClientChatInput(chatScreen, input);
        renderClientChatInputEmojis(event.getGuiGraphics(), mc, input);
        renderClientEmojiPickerButton(event.getGuiGraphics(), mc, input, event.getMouseX(), event.getMouseY());

        if (clientEmojiPickerOpen) {
            renderClientEmojiPicker(event.getGuiGraphics(), mc, chatScreen, input, event.getMouseX(), event.getMouseY());
        } else {
            resetClientPickerPanelBounds();
        }

        if (!refreshClientAutocomplete(input)) {
            resetClientAutocompleteBounds();
            return;
        }

        List<EmojiInfo> matches = CLIENT_AUTOCOMPLETE_MATCHES;
        if (matches.isEmpty()) {
            resetClientAutocompleteBounds();
            return;
        }

        GuiGraphics graphics = event.getGuiGraphics();
        int rowHeight = 12;
        int padding = 4;
        int maxTextWidth = 0;

        for (EmojiInfo info : matches) {
            maxTextWidth = Math.max(maxTextWidth, mc.font.width(":" + info.name().toLowerCase(Locale.ROOT) + ":"));
        }

        int width = Math.min(mc.getWindow().getGuiScaledWidth() - 8, Math.max(120, maxTextWidth + 26));
        int x = Math.max(2, Math.min(input.getX(), mc.getWindow().getGuiScaledWidth() - width - 2));
        int height = matches.size() * rowHeight + padding;
        int y = Math.max(2, input.getY() - height - 2);

        clientAutocompleteBoxX = x;
        clientAutocompleteBoxY = y;
        clientAutocompleteBoxWidth = width;
        clientAutocompleteRowHeight = rowHeight;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2300.0F);
        graphics.fill(x, y, x + width, y + height, 0xE0101010);

        for (int i = 0; i < matches.size(); i++) {
            EmojiInfo info = matches.get(i);
            int rowY = y + 2 + i * rowHeight;
            boolean hovered = isInside(event.getMouseX(), event.getMouseY(), x + 1, rowY - 1, width - 2, rowHeight);

            if (i == clientAutocompleteSelection || hovered) {
                graphics.fill(x + 1, rowY - 1, x + width - 1, rowY + rowHeight - 1, 0xCC5A5A5A);
            }

            ClientEmojiTexture texture = getOrLoadClientTexture(info);
            if (texture != null && texture.location() != null && texture.ready()) {
                graphics.blit(texture.location(), x + 3, rowY + 1, 0, 0, 9, 9, 9, 9);
            } else {
                drawEmojiPlaceholder(graphics, x + 4, rowY + 2, 7);
            }

            int color = i == clientAutocompleteSelection ? 0xFFFFFF55 : 0xFFFFFFFF;
            graphics.drawString(mc.font, ":" + info.name().toLowerCase(Locale.ROOT) + ":", x + 16, rowY + 1, color, false);
        }

        graphics.pose().popPose();
    }

    private static boolean refreshClientAutocomplete(EditBox input) {
        if (input == null || CLIENT_EMOJIS_BY_NAME.isEmpty()) {
            clearClientAutocomplete();
            return false;
        }

        String value = input.getValue();
        int cursor = Math.max(0, Math.min(input.getCursorPosition(), value.length()));
        AutocompleteToken token = findAutocompleteToken(value, cursor);

        if (token == null) {
            clearClientAutocomplete();
            return false;
        }

        String prefix = token.prefix().toLowerCase(Locale.ROOT);
        Set<String> seen = new HashSet<>();
        ArrayList<EmojiInfo> matches = new ArrayList<>();

        for (EmojiInfo info : CLIENT_EMOJIS_BY_NAME.values()) {
            if (info == null || info.name() == null) {
                continue;
            }

            String lowerName = info.name().toLowerCase(Locale.ROOT);
            if (!lowerName.startsWith(prefix) || !seen.add(lowerName)) {
                continue;
            }

            matches.add(info);
        }

        matches.sort(Comparator.comparing(EmojiInfo::name, String.CASE_INSENSITIVE_ORDER));
        if (matches.size() > AUTOCOMPLETE_MAX_ROWS) {
            matches = new ArrayList<>(matches.subList(0, AUTOCOMPLETE_MAX_ROWS));
        }

        if (matches.isEmpty()) {
            clearClientAutocomplete();
            return false;
        }

        String signature = token.start() + "|" + token.end() + "|" + prefix;
        if (!signature.equals(clientAutocompleteSignature)) {
            clientAutocompleteSelection = 0;
            clientAutocompleteSignature = signature;
        }

        clientAutocompleteSelection = Math.max(0, Math.min(clientAutocompleteSelection, matches.size() - 1));
        clientAutocompleteTokenStart = token.start();
        clientAutocompleteTokenEnd = token.end();
        CLIENT_AUTOCOMPLETE_MATCHES = List.copyOf(matches);
        return true;
    }

    private static AutocompleteToken findAutocompleteToken(String value, int cursor) {
        if (value == null || value.isEmpty() || cursor <= 0) {
            return null;
        }

        int colon = value.lastIndexOf(':', cursor - 1);
        if (colon < 0) {
            return null;
        }

        if (colon > 0) {
            char previous = value.charAt(colon - 1);
            if (Character.isLetterOrDigit(previous) || previous == '_' || previous == ':') {
                return null;
            }
        }

        String prefix = value.substring(colon + 1, cursor);
        if (prefix.length() > 64) {
            return null;
        }

        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return null;
            }
        }

        return new AutocompleteToken(colon, cursor, prefix);
    }

    private static void applyClientAutocomplete(EditBox input) {
        List<EmojiInfo> matches = CLIENT_AUTOCOMPLETE_MATCHES;
        if (input == null || matches.isEmpty()) {
            return;
        }

        int index = Math.max(0, Math.min(clientAutocompleteSelection, matches.size() - 1));
        EmojiInfo info = matches.get(index);
        String value = input.getValue();
        int start = Math.max(0, Math.min(clientAutocompleteTokenStart, value.length()));
        int end = Math.max(start, Math.min(clientAutocompleteTokenEnd, value.length()));
        String replacement = ":" + info.name().toLowerCase(Locale.ROOT) + ":";
        String newValue = value.substring(0, start) + replacement + value.substring(end);

        input.setValue(newValue);
        input.setCursorPosition(start + replacement.length());
        input.setHighlightPos(start + replacement.length());
        clearClientAutocomplete();
    }

    private static void prepareClientChatInput(ChatScreen screen, EditBox input) {
        ensureClientEmojiFormatter(input);

        int reserved = EMOJI_PICKER_BUTTON_SIZE + 5;
        int desiredWidth = Math.max(20, screen.width - input.getX() - reserved - 2);
        if (input.getWidth() > desiredWidth) {
            input.setWidth(desiredWidth);
        }

        clientPickerButtonX = Math.min(screen.width - EMOJI_PICKER_BUTTON_SIZE - 2,
                input.getX() + input.getWidth() + 2);
        clientPickerButtonY = input.getY();
    }

    @SuppressWarnings("unchecked")
    private static void ensureClientEmojiFormatter(EditBox input) {
        if (input == null) {
            return;
        }

        BiFunction<String, Integer, FormattedCharSequence> currentFormatter = null;
        try {
            Field field = findField(EditBox.class, "formatter", "f_94091_", "field_2099");
            if (field != null) {
                field.setAccessible(true);
                Object value = field.get(input);
                if (value instanceof BiFunction<?, ?, ?> function) {
                    currentFormatter = (BiFunction<String, Integer, FormattedCharSequence>) function;
                }
            }
        } catch (Throwable ignored) {
        }

        if (clientFormattedInput == input && currentFormatter == clientInstalledFormatter) {
            return;
        }

        BiFunction<String, Integer, FormattedCharSequence> original = currentFormatter;
        BiFunction<String, Integer, FormattedCharSequence> installed = (text, offset) -> {
            String safeText = text == null ? "" : text;
            int safeOffset = offset == null ? 0 : Math.max(0, offset);
            String transformed = replaceKnownEmojiTokensForInput(safeText, safeOffset, input.getCursorPosition());

            if (transformed.equals(safeText) && original != null) {
                try {
                    return original.apply(safeText, offset);
                } catch (Throwable ignored) {
                }
            }

            return FormattedCharSequence.forward(transformed, Style.EMPTY);
        };

        input.setFormatter(installed);
        clientFormattedInput = input;
        clientInstalledFormatter = installed;
    }

    private static String replaceKnownEmojiTokensForInput(String text, int globalOffset, int cursorPosition) {
        if (text == null || text.isEmpty() || CLIENT_EMOJIS_BY_NAME.isEmpty()) {
            return text == null ? "" : text;
        }

        Matcher matcher = MINECRAFT_EMOJI_TOKEN_PATTERN.matcher(text);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;

        while (matcher.find()) {
            EmojiInfo info = findClientEmoji(matcher.group(1));
            if (info == null) {
                continue;
            }

            int globalStart = globalOffset + matcher.start();
            int globalEnd = globalOffset + matcher.end();
            if (cursorPosition > globalStart && cursorPosition < globalEnd) {
                continue;
            }

            matcher.appendReplacement(buffer, Matcher.quoteReplacement(CHAT_INPUT_EMOJI_PLACEHOLDER));
            changed = true;
        }

        if (!changed) {
            return text;
        }

        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static void renderClientChatInputEmojis(GuiGraphics graphics, Minecraft mc, EditBox input) {
        String value = input.getValue();
        if (value == null || value.isEmpty()) {
            return;
        }

        int displayPos = getEditBoxDisplayPos(input);
        displayPos = Math.max(0, Math.min(displayPos, value.length()));

        String remaining = value.substring(displayPos);
        String visible = mc.font.plainSubstrByWidth(remaining, Math.max(1, input.getInnerWidth()));
        int visibleEnd = Math.min(value.length(), displayPos + visible.length());
        int cursor = Math.max(0, Math.min(input.getCursorPosition(), value.length()));
        boolean bordered = isEditBoxBordered(input);
        int baseX = input.getX() + (bordered ? 4 : 0);
        int emojiY = input.getY() + Math.max(0, (input.getHeight() - CHAT_INPUT_EMOJI_SIZE) / 2);

        Matcher matcher = MINECRAFT_EMOJI_TOKEN_PATTERN.matcher(value);
        while (matcher.find()) {
            EmojiInfo info = findClientEmoji(matcher.group(1));
            if (info == null) {
                continue;
            }

            int start = matcher.start();
            int end = matcher.end();
            if (cursor > start && cursor < end) {
                continue;
            }
            if (start < displayPos || end > visibleEnd) {
                continue;
            }

            String before = value.substring(displayPos, start);
            String transformedBefore = replaceKnownEmojiTokensForInput(before, displayPos, cursor);
            int emojiX = baseX + mc.font.width(transformedBefore);

            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 2250.0F);
            ClientEmojiTexture texture = getOrLoadClientTexture(info);
            if (texture != null && texture.location() != null && texture.ready()) {
                graphics.blit(texture.location(), emojiX, emojiY, 0, 0,
                        CHAT_INPUT_EMOJI_SIZE, CHAT_INPUT_EMOJI_SIZE,
                        CHAT_INPUT_EMOJI_SIZE, CHAT_INPUT_EMOJI_SIZE);
            } else {
                drawEmojiPlaceholder(graphics, emojiX, emojiY, CHAT_INPUT_EMOJI_SIZE);
            }
            graphics.pose().popPose();
        }
    }

    private static void renderClientEmojiPickerButton(GuiGraphics graphics,
                                                        Minecraft mc,
                                                        EditBox input,
                                                        int mouseX,
                                                        int mouseY) {
        clientPickerButtonX = Math.max(2, input.getX() + input.getWidth() + 2);
        clientPickerButtonY = input.getY();

        boolean hovered = isInside(mouseX, mouseY,
                clientPickerButtonX,
                clientPickerButtonY,
                EMOJI_PICKER_BUTTON_SIZE,
                EMOJI_PICKER_BUTTON_SIZE);

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2350.0F);
        graphics.fill(clientPickerButtonX,
                clientPickerButtonY,
                clientPickerButtonX + EMOJI_PICKER_BUTTON_SIZE,
                clientPickerButtonY + EMOJI_PICKER_BUTTON_SIZE,
                hovered || clientEmojiPickerOpen ? 0xE0606060 : 0xD0202020);

        int faceX = clientPickerButtonX + 2;
        int faceY = clientPickerButtonY + 2;
        int faceSize = EMOJI_PICKER_BUTTON_SIZE - 4;
        graphics.fill(faceX, faceY, faceX + faceSize, faceY + faceSize, 0xFFF2C94C);
        graphics.fill(faceX + 2, faceY + 2, faceX + 3, faceY + 3, 0xFF202020);
        graphics.fill(faceX + faceSize - 3, faceY + 2, faceX + faceSize - 2, faceY + 3, 0xFF202020);
        graphics.fill(faceX + 2, faceY + faceSize - 3, faceX + faceSize - 2, faceY + faceSize - 2, 0xFF202020);
        graphics.pose().popPose();
    }

    private static void renderClientEmojiPicker(GuiGraphics graphics,
                                                  Minecraft mc,
                                                  ChatScreen screen,
                                                  EditBox input,
                                                  int mouseX,
                                                  int mouseY) {
        List<EmojiInfo> emojis = getSortedClientEmojis();
        if (emojis.isEmpty()) {
            clientEmojiPickerOpen = false;
            resetClientPickerPanelBounds();
            return;
        }

        int pageSize = EMOJI_PICKER_COLUMNS * EMOJI_PICKER_ROWS;
        int pageCount = Math.max(1, (emojis.size() + pageSize - 1) / pageSize);
        clientEmojiPickerPage = Math.max(0, Math.min(clientEmojiPickerPage, pageCount - 1));

        int panelWidth = EMOJI_PICKER_COLUMNS * EMOJI_PICKER_CELL_SIZE + 8;
        int panelHeight = EMOJI_PICKER_HEADER_HEIGHT
                + EMOJI_PICKER_ROWS * EMOJI_PICKER_CELL_SIZE
                + EMOJI_PICKER_FOOTER_HEIGHT;
        int panelX = Math.max(2, Math.min(screen.width - panelWidth - 2,
                clientPickerButtonX + EMOJI_PICKER_BUTTON_SIZE - panelWidth));
        int panelY = Math.max(2, input.getY() - panelHeight - 2);

        clientPickerPanelX = panelX;
        clientPickerPanelY = panelY;
        clientPickerPanelWidth = panelWidth;
        clientPickerPanelHeight = panelHeight;

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 2400.0F);
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, 0xF0101010);
        graphics.fill(panelX + 1, panelY + 1, panelX + panelWidth - 1, panelY + EMOJI_PICKER_HEADER_HEIGHT - 1, 0xE0303030);

        String pageLabel = "Emoji " + (clientEmojiPickerPage + 1) + "/" + pageCount;
        int pageLabelX = panelX + (panelWidth - mc.font.width(pageLabel)) / 2;
        graphics.drawString(mc.font, pageLabel, pageLabelX, panelY + 4, 0xFFFFFFFF, false);

        if (pageCount > 1) {
            int previousX = panelX + 5;
            int nextX = panelX + panelWidth - 14;
            boolean previousHovered = isInside(mouseX, mouseY, previousX, panelY + 3, 10, 11);
            boolean nextHovered = isInside(mouseX, mouseY, nextX, panelY + 3, 10, 11);
            graphics.drawString(mc.font, "<", previousX + 2, panelY + 4,
                    previousHovered ? 0xFFFFFF55 : 0xFFFFFFFF, false);
            graphics.drawString(mc.font, ">", nextX + 2, panelY + 4,
                    nextHovered ? 0xFFFFFF55 : 0xFFFFFFFF, false);
        }

        int gridX = panelX + 4;
        int gridY = panelY + EMOJI_PICKER_HEADER_HEIGHT;
        int first = clientEmojiPickerPage * pageSize;
        EmojiInfo hoveredEmoji = null;

        for (int slot = 0; slot < pageSize; slot++) {
            int index = first + slot;
            int row = slot / EMOJI_PICKER_COLUMNS;
            int column = slot % EMOJI_PICKER_COLUMNS;
            int cellX = gridX + column * EMOJI_PICKER_CELL_SIZE;
            int cellY = gridY + row * EMOJI_PICKER_CELL_SIZE;
            boolean hovered = isInside(mouseX, mouseY,
                    cellX,
                    cellY,
                    EMOJI_PICKER_CELL_SIZE,
                    EMOJI_PICKER_CELL_SIZE);

            if (hovered) {
                graphics.fill(cellX + 1, cellY + 1,
                        cellX + EMOJI_PICKER_CELL_SIZE - 1,
                        cellY + EMOJI_PICKER_CELL_SIZE - 1,
                        0xCC555555);
            }

            if (index >= emojis.size()) {
                continue;
            }

            EmojiInfo info = emojis.get(index);
            if (hovered) {
                hoveredEmoji = info;
            }

            ClientEmojiTexture texture = getOrLoadClientTexture(info);
            int emojiSize = 14;
            int emojiX = cellX + (EMOJI_PICKER_CELL_SIZE - emojiSize) / 2;
            int emojiY = cellY + (EMOJI_PICKER_CELL_SIZE - emojiSize) / 2;
            if (texture != null && texture.location() != null && texture.ready()) {
                graphics.blit(texture.location(), emojiX, emojiY, 0, 0,
                        emojiSize, emojiSize, emojiSize, emojiSize);
            } else {
                drawEmojiPlaceholder(graphics, emojiX, emojiY, emojiSize);
            }
        }

        int footerY = panelY + panelHeight - EMOJI_PICKER_FOOTER_HEIGHT;
        graphics.fill(panelX + 1, footerY, panelX + panelWidth - 1, panelY + panelHeight - 1, 0xE0202020);
        if (hoveredEmoji != null) {
            String label = ":" + hoveredEmoji.name().toLowerCase(Locale.ROOT) + ":";
            label = mc.font.plainSubstrByWidth(label, panelWidth - 8);
            graphics.drawString(mc.font, label, panelX + 4, footerY + 2, 0xFFFFFFFF, false);
        }

        graphics.pose().popPose();
    }

    private static void drawEmojiPlaceholder(GuiGraphics graphics, int x, int y, int size) {
        int safeSize = Math.max(4, size);
        graphics.fill(x, y, x + safeSize, y + safeSize, 0xFF555555);
        int eyeY = y + Math.max(1, safeSize / 3);
        int leftEyeX = x + Math.max(1, safeSize / 4);
        int rightEyeX = x + Math.max(2, (safeSize * 3) / 4);
        graphics.fill(leftEyeX, eyeY, leftEyeX + 1, eyeY + 1, 0xFFFFFFFF);
        graphics.fill(rightEyeX, eyeY, rightEyeX + 1, eyeY + 1, 0xFFFFFFFF);
    }

    private static List<EmojiInfo> getSortedClientEmojis() {
        Set<String> seen = new HashSet<>();
        ArrayList<EmojiInfo> result = new ArrayList<>();

        for (EmojiInfo info : CLIENT_EMOJIS_BY_NAME.values()) {
            if (info == null || info.name() == null) {
                continue;
            }

            String key = info.name().toLowerCase(Locale.ROOT);
            if (seen.add(key)) {
                result.add(info);
            }
        }

        result.sort(Comparator.comparing(EmojiInfo::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static void clampClientEmojiPickerPage() {
        int pageSize = EMOJI_PICKER_COLUMNS * EMOJI_PICKER_ROWS;
        int count = getSortedClientEmojis().size();
        int pageCount = Math.max(1, (count + pageSize - 1) / pageSize);
        clientEmojiPickerPage = Math.max(0, Math.min(clientEmojiPickerPage, pageCount - 1));
    }

    private static int getEditBoxDisplayPos(EditBox input) {
        try {
            Field field = findField(EditBox.class, "displayPos", "f_94100_", "field_2103");
            if (field != null) {
                field.setAccessible(true);
                return Math.max(0, field.getInt(input));
            }
        } catch (Throwable ignored) {
        }

        return 0;
    }

    private static boolean isEditBoxBordered(EditBox input) {
        try {
            Field field = findField(EditBox.class, "bordered", "f_94096_", "field_2095");
            if (field != null) {
                field.setAccessible(true);
                return field.getBoolean(input);
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static void disableEmojifulUi(Screen screen) {
        if (screen == null || !"com.hrznstudio.emojiful.gui.EmojifulChatScreen".equals(screen.getClass().getName())) {
            return;
        }

        clearOptionalScreenField(screen, "emojiSuggestionHelper");
        clearOptionalScreenField(screen, "emojiSelectionGui");
    }

    private static void clearOptionalScreenField(Screen screen, String fieldName) {
        try {
            Field field = findField(screen.getClass(), fieldName);
            if (field != null) {
                field.setAccessible(true);
                field.set(screen, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static EditBox findChatInput(ChatScreen screen) {
        Class<?> current = screen.getClass();

        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!EditBox.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(screen);
                    if (value instanceof EditBox editBox) {
                        return editBox;
                    }
                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && width > 0 && height > 0
                && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + height;
    }

    private static void clearClientAutocomplete() {
        CLIENT_AUTOCOMPLETE_MATCHES = List.of();
        clientAutocompleteSelection = 0;
        clientAutocompleteSignature = "";
        clientAutocompleteTokenStart = -1;
        clientAutocompleteTokenEnd = -1;
        resetClientAutocompleteBounds();
    }

    private static void resetClientAutocompleteBounds() {
        clientAutocompleteBoxX = -1;
        clientAutocompleteBoxY = -1;
        clientAutocompleteBoxWidth = 0;
        clientAutocompleteRowHeight = 0;
    }

    private static void resetClientPickerPanelBounds() {
        clientPickerPanelX = -1;
        clientPickerPanelY = -1;
        clientPickerPanelWidth = 0;
        clientPickerPanelHeight = 0;
    }

    private static void resetClientEmojiUiBounds() {
        resetClientAutocompleteBounds();
        resetClientPickerPanelBounds();
        clientPickerButtonX = -1;
        clientPickerButtonY = -1;
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
        clearClientAutocomplete();
        clientEmojiPickerOpen = false;
        clientEmojiPickerPage = 0;

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

    private static List<VisibleChatLine> getVisibleChatLines(ChatComponent chat) {
        ArrayList<VisibleChatLine> lines = new ArrayList<>();

        try {
            Field trimmedMessagesField = findField(ChatComponent.class, "trimmedMessages", "f_93761_", "field_2064");
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
                lines.add(lineToVisibleChatLine(rawLines.get(i)));
            }
        } catch (Throwable ignored) {
        }

        return lines;
    }

    private static VisibleChatLine lineToVisibleChatLine(Object line) {
        if (line == null) {
            return new VisibleChatLine("", List.of(), -1);
        }

        try {
            FormattedCharSequence sequence = null;
            int addedTime = -1;

            if (line instanceof GuiMessage.Line guiLine) {
                sequence = guiLine.content();
                addedTime = guiLine.addedTime();
            }

            if (sequence == null) {
                Object content = null;

                try {
                    content = line.getClass().getMethod("content").invoke(line);
                } catch (Throwable ignored) {
                    try {
                        content = line.getClass().getMethod("f_240339_").invoke(line);
                    } catch (Throwable ignoredToo) {
                        Field contentField = findField(line.getClass(), "content", "f_240339_", "field_39766");
                        if (contentField != null) {
                            contentField.setAccessible(true);
                            content = contentField.get(line);
                        }
                    }
                }

                if (content instanceof FormattedCharSequence formatted) {
                    sequence = formatted;
                } else if (content instanceof Component component) {
                    sequence = component.getVisualOrderText();
                }
            }

            if (sequence == null) {
                return new VisibleChatLine("", List.of(), addedTime);
            }

            StringBuilder builder = new StringBuilder();
            ArrayList<EmojiVisual> emojis = new ArrayList<>();
            String[] lastInsertion = {null};

            sequence.accept((index, style, codePoint) -> {
                String insertion = style == null ? null : style.getInsertion();
                if (insertion != null && insertion.startsWith(EMOJI_INSERTION_PREFIX)
                        && !insertion.equals(lastInsertion[0])) {
                    String name = sanitizeEmojiName(insertion.substring(EMOJI_INSERTION_PREFIX.length()));
                    EmojiInfo info = name == null ? null : findClientEmoji(name);
                    if (info == null && name != null) {
                        info = findClientEmoji(name.toLowerCase(Locale.ROOT));
                    }
                    if (info != null) {
                        emojis.add(new EmojiVisual(info, builder.length()));
                    }
                }

                lastInsertion[0] = insertion;
                builder.appendCodePoint(codePoint);
                return true;
            });

            return new VisibleChatLine(builder.toString(), emojis, addedTime);
        } catch (Throwable ignored) {
            return new VisibleChatLine("", List.of(), -1);
        }
    }

    private static int getChatScroll(ChatComponent chat) {
        try {
            Field field = findField(ChatComponent.class, "chatScrollbarPos", "scrollPos", "f_93763_", "field_2066");
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
            return Math.max(1, chat.getHeight());
        } catch (Throwable ignored) {
            return 180;
        }
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

    private record EmojiVisual(EmojiInfo info, int charIndex) {
    }

    private record VisibleChatLine(String text, List<EmojiVisual> emojis, int addedTime) {
    }

    private record AutocompleteToken(int start, int end, String prefix) {
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
