package orbis.compat;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;

/**
 * The Forge side of the built-in controller support. Forge runs without Fabric's mixin loader, so
 * the launcher starts the compatibility JAR as a Java agent (-javaagent, a JVM option the runner
 * accepts) and this premain drives ControllerBridge from a daemon thread instead of a client tick:
 * it waits for Minecraft's class to load, takes the game's instance (the only static field of the
 * class's own type) and polls the controller around twenty times a second, which is how fast the
 * game reads its key states.
 *
 * <p>Forge 1.17+ runs the game's classes on the module path through its own class loaders; the
 * agent therefore resolves every class through the game's loader once found, and polls GLFW through
 * reflection against the same loader (ControllerBridge does that already). No game class is touched.
 */
public final class ControllerAgent {
    private ControllerAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        Thread worker = new Thread("orbis-controller") {
            @Override public void run() {
                poll(instrumentation);
            }
        };
        worker.setDaemon(true);
        worker.start();
        System.out.println("orbis-compat: controller agent started for Forge");
    }

    private static void poll(Instrumentation instrumentation) {
        Class<?> minecraft = null;
        for (int waited = 0; waited < 600 && minecraft == null; waited++) {
            try {
                minecraft = findLoaded(instrumentation, "net.minecraft.client.Minecraft");
            } catch (Throwable next) {
                // classes are still loading
            }
            if (minecraft == null) {
                try {
                    minecraft = findLoaded(instrumentation, "net.minecraft.class_310");
                } catch (Throwable next) {
                    // intermediary names only on Fabric, which does not run this agent
                }
            }
            if (minecraft == null) sleep(100);
        }
        if (minecraft == null) {
            System.err.println("orbis-compat: no Minecraft class found; controller agent stops");
            return;
        }
        ClassLoader loader = minecraft.getClassLoader();
        try {
            Class.forName("org.lwjgl.glfw.GLFW", false, loader);
        } catch (Throwable missing) {
            // ControllerBridge falls back to its own loader when the game's has no GLFW
        }
        long frame = 50;
        while (true) {
            Object instance = instance(minecraft);
            if (instance == null) {
                sleep(200);
                continue;
            }
            try {
                ControllerBridge.tick(instance);
            } catch (Throwable failure) {
                System.err.println("orbis-compat: controller agent off: " + failure);
                return;
            }
            sleep(frame);
        }
    }

    /** A loaded class by name, through the agent's instrumentation. */
    private static Class<?> findLoaded(Instrumentation instrumentation, String name) {
        Class<?> best = null;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (loaded.getName().equals(name)) return loaded;
            if (best == null && loaded.getName().startsWith(name)) best = loaded;
        }
        return null;
    }

    /** The Minecraft instance: the only static field of the class's own type, once the game set it. */
    private static Object instance(Class<?> minecraft) {
        for (Class<?> c = minecraft; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == minecraft && java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value != null) return value;
                    } catch (Throwable unreadable) {
                        // the next self-typed field
                    }
                }
            }
        }
        return null;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
