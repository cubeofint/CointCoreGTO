package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;

public final class CointCoreGTODiscordProxy {
    private static final String BRIDGE_CLASS_NAME = "Crazer.cubeofinterest.cointcoregto.CointCoreGTODiscordBridge";

    private CointCoreGTODiscordProxy() {
    }

    public static void start(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String configuredWebhookUrl,
            String configuredAvatarUrlTemplate,
            String channelId,
            String logChannelId,
            boolean statusMessages,
            boolean configuredOnlineStatusEnabled,
            String configuredOnlineStatusChannelId,
            int configuredOnlineStatusUpdateSeconds
    ) {
        invokeServerOnly(
                "start",
                new Class<?>[]{
                        MinecraftServer.class,
                        boolean.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        int.class
                },
                minecraftServer,
                bridgeEnabled,
                token,
                configuredWebhookUrl,
                configuredAvatarUrlTemplate,
                channelId,
                logChannelId,
                statusMessages,
                configuredOnlineStatusEnabled,
                configuredOnlineStatusChannelId,
                configuredOnlineStatusUpdateSeconds
        );
    }

    public static void reload(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String configuredWebhookUrl,
            String configuredAvatarUrlTemplate,
            String channelId,
            String logChannelId,
            boolean statusMessages,
            boolean configuredOnlineStatusEnabled,
            String configuredOnlineStatusChannelId,
            int configuredOnlineStatusUpdateSeconds
    ) {
        invokeServerOnly(
                "reload",
                new Class<?>[]{
                        MinecraftServer.class,
                        boolean.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        int.class
                },
                minecraftServer,
                bridgeEnabled,
                token,
                configuredWebhookUrl,
                configuredAvatarUrlTemplate,
                channelId,
                logChannelId,
                statusMessages,
                configuredOnlineStatusEnabled,
                configuredOnlineStatusChannelId,
                configuredOnlineStatusUpdateSeconds
        );
    }

    public static void stop() {
        invokeServerOnly("stop", new Class<?>[]{});
    }

    public static void sendToDiscord(String message) {
        invokeServerOnly("sendToDiscord", new Class<?>[]{String.class}, message);
    }

    public static void sendToDiscordLog(String message) {
        invokeServerOnly("sendToDiscordLog", new Class<?>[]{String.class}, message);
    }

    public static void sendPlayerMessageToDiscord(String username, String message, String uuid, String playerName) {
        invokeServerOnly(
                "sendPlayerMessageToDiscord",
                new Class<?>[]{String.class, String.class, String.class, String.class},
                username,
                message,
                uuid,
                playerName
        );
    }

    private static void invokeServerOnly(String methodName, Class<?>[] parameterTypes, Object... args) {
        if (FMLEnvironment.dist != Dist.DEDICATED_SERVER) {
            return;
        }

        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS_NAME);
            Method method = bridgeClass.getMethod(methodName, parameterTypes);
            method.invoke(null, args);
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to call Discord bridge method " + methodName + ": " + e.getMessage());
        }
    }
}
