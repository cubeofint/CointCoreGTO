package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterPersonalSpaceCompatBootstrap {
    private static final Logger LOGGER =
            LogManager.getLogger("CointCoreGTO:PersonalSpaceCompat");
    private static final String API_CLASS =
            "me.eigenraven.personalspace.api.cluster.PersonalSpaceClusterApi";
    private static final String HANDLER_CLASS =
            "me.eigenraven.personalspace.api.cluster.PersonalSpaceClusterHandler";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Method loadDimensionMethod;
    private static volatile Object handlerProxy;

    private ClusterPersonalSpaceCompatBootstrap() {
    }

    public static void registerIfAvailable() {
        if (!ModList.get().isLoaded("personalspace")
                || !REGISTERED.compareAndSet(false, true)) {
            return;
        }

        try {
            ClassLoader classLoader =
                    ClusterPersonalSpaceCompatBootstrap.class.getClassLoader();
            Class<?> apiClass = Class.forName(
                    API_CLASS,
                    true,
                    classLoader
            );
            Class<?> handlerClass = Class.forName(
                    HANDLER_CLASS,
                    true,
                    classLoader
            );

            Object proxy = Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{handlerClass},
                    (proxyInstance, method, arguments) -> invokeHandler(
                            proxyInstance,
                            method,
                            arguments
                    )
            );
            handlerProxy = proxy;

            apiClass.getMethod(
                    "register",
                    String.class,
                    handlerClass
            ).invoke(null, CointCoreGTO.MODID, proxy);

            loadDimensionMethod = apiClass.getMethod(
                    "loadDimension",
                    MinecraftServer.class,
                    ResourceKey.class
            );
        } catch (Throwable throwable) {
            handlerProxy = null;
            loadDimensionMethod = null;
            REGISTERED.set(false);
            LOGGER.error(
                    "Unable to register Personal Space cluster integration",
                    unwrap(throwable)
            );
        }
    }

    public static boolean isPersonalSpaceDimension(String dimensionId) {
        ResourceLocation id = ResourceLocation.tryParse(dimensionId);
        if (id == null || !id.getNamespace().equals("personalspace")) {
            return false;
        }

        String path = id.getPath();
        if (!path.startsWith("personal_space_dimensions/")) {
            return false;
        }

        String name = path.substring("personal_space_dimensions/".length());
        return name.startsWith("ps_") || name.startsWith("team_");
    }

    public static ServerLevel loadDimension(
            MinecraftServer server,
            ResourceKey<Level> dimension
    ) throws Exception {
        if (!ModList.get().isLoaded("personalspace")) {
            return null;
        }

        Method method = loadDimensionMethod;
        if (method == null) {
            registerIfAvailable();
            method = loadDimensionMethod;
        }
        if (method == null) {
            return null;
        }

        try {
            Object result = method.invoke(null, server, dimension);
            return result instanceof ServerLevel level ? level : null;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            throw exception;
        }
    }

    private static Object invokeHandler(
            Object identity,
            Method method,
            Object[] arguments
    ) throws Exception {
        String methodName = method.getName();
        if (methodName.equals("toString") && method.getParameterCount() == 0) {
            return "CointCoreGTO PersonalSpaceClusterHandler";
        }
        if (methodName.equals("hashCode") && method.getParameterCount() == 0) {
            return System.identityHashCode(identity);
        }
        if (methodName.equals("equals") && method.getParameterCount() == 1) {
            return identity == arguments[0];
        }

        if (arguments == null || arguments.length != 1) {
            return enumResult(method.getReturnType(), false);
        }

        Object context = arguments[0];
        ServerPlayer player = (ServerPlayer) invokeContext(context, "player");
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension =
                (ResourceKey<Level>) invokeContext(context, "dimension");
        BlockPos destination = (BlockPos) invokeContext(context, "destination");
        float yaw = ((Number) invokeContext(context, "yaw")).floatValue();
        float pitch = ((Number) invokeContext(context, "pitch")).floatValue();
        Runnable localContinuation = () -> {
            try {
                invokeContext(context, "continueLocally");
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to continue Personal Space operation locally",
                        exception
                );
            }
        };

        boolean handled;
        if (methodName.equals("onPersonalSpaceCreated")) {
            handled = ClusterTestModule.routeNewPersonalSpace(
                    player,
                    dimension,
                    destination,
                    yaw,
                    pitch,
                    localContinuation
            );
        } else if (methodName.equals("onPortalTravel")) {
            handled = ClusterTestModule.routePersonalSpacePortal(
                    player,
                    dimension,
                    destination,
                    yaw,
                    pitch,
                    localContinuation
            );
        } else {
            handled = false;
        }

        return enumResult(method.getReturnType(), handled);
    }

    private static Object invokeContext(
            Object context,
            String methodName
    ) throws Exception {
        try {
            return context.getClass().getMethod(methodName).invoke(context);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception nested) {
                throw nested;
            }
            throw exception;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumResult(
            Class<?> returnType,
            boolean handled
    ) {
        if (!returnType.isEnum()) {
            return null;
        }
        return Enum.valueOf(
                (Class<? extends Enum>) returnType,
                handled ? "HANDLED" : "CONTINUE"
        );
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null) {
            return invocation.getCause();
        }
        return throwable;
    }
}
