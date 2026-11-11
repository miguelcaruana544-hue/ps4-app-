package orbis.compat;

/**
 * Built-in controller support for Minecraft itself, without the Controlify mod: the launcher always
 * injects this compatibility mod (Fabric and Quilt) or loads it as a Java agent (Forge), so every
 * instance can be played with the console's controller.
 *
 * <p>Each client tick (Fabric and Quilt: ClientTickMixin) or poll cycle (Forge: ControllerAgent)
 * reads the console's controller through GLFW's gamepad API, which the Orbis GLFW facade implements
 * for the PS4 pad, and drives the game through reflection so one JAR serves every mapping set:
 * Mojang's names (26.1+), Forge's SRG names (1.14 to 1.20.x) and Fabric's intermediary names.
 *
 * <p>PS-style layout, the console's own glyphs: left stick walk, right stick look, Cross jump,
 * Circle sneak, Square inventory, Triangle swap offhand, L2 mine/attack, R2 use/place, L1/R1
 * hotbar, L3 sprint, R3 pick block, D-pad up perspective, D-pad down drop item, D-pad left/right
 * hotbar, touchpad chat, Options pause. With a menu open the pad drives it instead: D-pad arrows,
 * Cross Enter, Circle Escape, Triangle shift-Tab.
 *
 * <p>Nothing here runs when the Controlify mod is installed, so the two never double-drive the
 * game. Every reflection target carries its name in all mapping sets and every failure disables the
 * bridge with one log line, so a Minecraft this does not know still starts.
 */
public final class ControllerBridge {
    /** System property switches, written into the runner's JVM options by the launcher. */
    private static final String ENABLED_PROPERTY = "orbis.controller.enabled";
    private static final String SENSITIVITY_PROPERTY = "orbis.controller.sensitivity";
    private static final String DEADZONE_PROPERTY = "orbis.controller.deadzone";

    // GLFW keyboard codes (stable GLFW 3 values, read directly).
    private static final int KEY_ESCAPE = 256, KEY_ENTER = 257, KEY_TAB = 258,
            KEY_RIGHT = 262, KEY_LEFT = 263, KEY_DOWN = 264, KEY_UP = 265;
    private static final int GLFW_RELEASE = 0, GLFW_PRESS = 1, GLFW_MOD_SHIFT = 1;
    // GLFW gamepad buttons and axes (glfw3.h layout, the same order on every version).
    private static final int BTN_COUNT = 15;
    private static final int BTN_A = 0, BTN_B = 1, BTN_X = 2, BTN_Y = 3,
            BTN_BACK = 4, BTN_START = 6, BTN_LEFT_STICK = 7, BTN_RIGHT_STICK = 8,
            BTN_LEFT_BUMPER = 9, BTN_RIGHT_BUMPER = 10, BTN_UP = 11, BTN_DOWN = 12,
            BTN_LEFT = 13, BTN_RIGHT = 14;
    private static final int AXIS_LEFT_X = 0, AXIS_LEFT_Y = 1, AXIS_RIGHT_X = 2,
            AXIS_RIGHT_Y = 3, AXIS_LEFT_TRIGGER = 4, AXIS_RIGHT_TRIGGER = 5;
    private static final int AXIS_COUNT = 6;

    /** Option keys, in the order of OPTION_KEYS below (named KEYIDX_* to stay clear of the GLFW codes). */
    private static final int KEYIDX_FORWARD = 0, KEYIDX_BACK = 1, KEYIDX_LEFT = 2, KEYIDX_RIGHT = 3, KEYIDX_JUMP = 4,
            KEYIDX_SNEAK = 5, KEYIDX_SPRINT = 6, KEYIDX_INVENTORY = 7, KEYIDX_SWAP = 8, KEYIDX_DROP = 9,
            KEYIDX_CHAT = 10, KEYIDX_ATTACK = 11, KEYIDX_USE = 12, KEYIDX_PICK = 13, KEYIDX_PERSPECTIVE = 14;
    private static final int KEYIDX_COUNT = 15;

    private static final String[] MINECRAFT_CLASSES = {"net.minecraft.client.Minecraft", "net.minecraft.class_310"};
    private static final String[] OPTIONS_CLASSES = {"net.minecraft.client.Options",
            "net.minecraft.client.GameSettings", "net.minecraft.class_315"};
    private static final String[] KEY_CLASSES = {"net.minecraft.client.KeyMapping",
            "net.minecraft.client.settings.KeyBinding", "net.minecraft.class_304"};
    private static final String[] ENTITY_CLASSES = {"net.minecraft.world.entity.Entity",
            "net.minecraft.entity.Entity", "net.minecraft.class_1297"};
    private static final String[] INVENTORY_CLASSES = {"net.minecraft.world.entity.player.Inventory",
            "net.minecraft.entity.player.PlayerInventory", "net.minecraft.class_1661"};
    private static final String[] KEYBOARD_CLASSES = {"net.minecraft.client.KeyboardHandler",
            "net.minecraft.client.KeyboardListener", "net.minecraft.class_309"};
    private static final String[] WINDOW_CLASSES = {"com.mojang.blaze3d.platform.Window",
            "net.minecraft.client.MainWindow", "net.minecraft.class_1041"};

    // Every target in all four mapping sets: Mojang, Forge 1.17+ SRG (m_/f_), Forge 1.14-1.16 SRG
    // (func_/field_, MCP class names), Fabric intermediary (method_/field_).
    private static final String[][] FIELD_PLAYER = {{"player", "f_91074_", "field_71439_g", "field_1724"}};
    private static final String[][] FIELD_OPTIONS = {{"options", "f_91066_", "field_71474_y", "field_1690"}};
    private static final String[][] FIELD_SCREEN = {{"screen", "f_91080_", "field_71462_r", "field_1755"}};
    private static final String[][] METHOD_WINDOW_HANDLE = {{"getWindow", "m_91268_", "func_228018_at_", "method_22683"}};
    private static final String[][] METHOD_GAME_WINDOW = {{"getWindow", "m_85439_", "func_198092_i", "method_4490"}};
    private static final String[][] METHOD_TURN = {{"turn", "m_19884_", "func_195049_a", "method_5872"}};
    private static final String[][] FIELD_SELECTED = {{"selected", "f_35977_", "field_70461_c", "field_7545"}};
    private static final String[][] METHOD_KEY_PRESS = {{"keyPress", "m_90893_", "func_197961_a", "method_1466"}};
    private static final String[][] METHOD_SET_DOWN = {{"setDown", "m_7249_", "func_225593_a_", "method_23481"}};
    private static final String[][] FIELD_CLICKS = {{"clickCount", "f_90818_", "field_151474_i", "field_1661"}};

    /** Option key fields: forward, back, left, right, jump, sneak, sprint, inventory, swap, drop, chat, attack, use, pick, perspective. */
    private static final String[][] OPTION_KEYS = {
            {"keyUp", "f_92085_", "field_74351_w", "field_1894"},
            {"keyDown", "f_92087_", "field_74368_y", "field_1881"},
            {"keyLeft", "f_92086_", "field_74370_x", "field_1913"},
            {"keyRight", "f_92088_", "field_74366_z", "field_1849"},
            {"keyJump", "f_92089_", "field_74314_A", "field_1903"},
            {"keyShift", "f_92090_", "field_228046_af_", "field_1832"},
            {"keySprint", "f_92091_", "field_151444_V", "field_1867"},
            {"keyInventory", "f_92092_", "field_151445_Q", "field_1822"},
            {"keySwapOffhand", "f_92093_", "field_186718_X", "field_1831"},
            {"keyDrop", "f_92094_", "field_74316_C", "field_1869"},
            {"keyChat", "f_92098_", "field_74310_D", "field_1890"},
            {"keyAttack", "f_92096_", "field_74312_F", "field_1886"},
            {"keyUse", "f_92095_", "field_74313_G", "field_1904"},
            {"keyPickItem", "f_92097_", "field_74322_I", "field_1871"},
            {"keyTogglePerspective", "f_92103_", "field_151457_aa", "field_1824"},
    };

    private static boolean tried, resolved, disabled;
    private static boolean enabled = true;
    private static float sensitivity = 1.0f, deadzone = 0.25f;
    private static boolean controlify;

    private static Class<?> minecraftClass, keyClass, entityClass, inventoryClass,
            keyboardClass, windowClass;
    private static java.lang.reflect.Field fPlayer, fOptions, fScreen, fSelected, fClicks, fKeyboard;
    private static java.lang.reflect.Method mWindowHandle, mGameWindow, mTurn, mKeyPress, mSetDown;
    private static Object[] optionKeys = new Object[KEYIDX_COUNT];

    private static Object gamepadState;
    private static java.lang.reflect.Method gamepadPoll, stateButtons, stateAxes;
    private static boolean glfwReady, glfwFailed;

    /** Edge flags for the menu keys: Escape, Enter, up, down, left, right, Tab. */
    private static final int EDGE_ESCAPE = 0, EDGE_ENTER = 1, EDGE_UP = 2, EDGE_DOWN = 3,
            EDGE_LEFT = 4, EDGE_RIGHT = 5, EDGE_TAB = 6;
    private static final boolean[] menuEdge = new boolean[7];

    private static final float[] previous = new float[BTN_COUNT];
    private static final boolean[] heldKeys = new boolean[KEYIDX_COUNT];

    private ControllerBridge() {}

    /** One client tick (Fabric/Quilt mixins) or poll cycle (Forge agent) with the Minecraft instance. */
    public static void tick(Object minecraft) {
        if (disabled || minecraft == null) return;
        if (!tried) {
            tried = true;
            configure();
            try {
                controlify = present("dev.isxander.controlify.Controlify")
                        || present("dev.isxander.controlify.controllermanager.ControllerManager");
                if (controlify)
                    System.out.println("orbis-compat: Controlify is installed; the built-in controller stays off");
            } catch (Throwable failure) {
                System.err.println("orbis-compat: built-in controller off: " + failure);
            }
        }
        if (disabled || controlify || !enabled) return;
        try {
            if (!resolve(minecraft)) return;
            Object screen = fScreen != null ? fScreen.get(minecraft) : null;
            float[] pad = poll();
            if (pad == null) {
                releaseAll(minecraft);
                return;
            }
            if (screen != null) menuTick(minecraft, pad);
            else gameTick(minecraft, pad);
            for (int i = 0; i < BTN_COUNT; i++) previous[i] = pad[i];
        } catch (Throwable failure) {
            disabled = true;
            System.err.println("orbis-compat: built-in controller off: " + failure);
        }
    }

    // ------------------------------------------------------------------ game

    private static void gameTick(Object minecraft, float[] pad) throws Throwable {
        // Walk: the left stick, with the dead zone cut off.
        float lx = axis(pad, AXIS_LEFT_X), ly = axis(pad, AXIS_LEFT_Y);
        holdKey(minecraft, KEYIDX_FORWARD, ly < -deadzone);
        holdKey(minecraft, KEYIDX_BACK, ly > deadzone);
        holdKey(minecraft, KEYIDX_LEFT, lx < -deadzone);
        holdKey(minecraft, KEYIDX_RIGHT, lx > deadzone);

        // Look: the right stick, scaled by the sensitivity setting. Pushing up looks up.
        float rx = axis(pad, AXIS_RIGHT_X), ry = axis(pad, AXIS_RIGHT_Y);
        if (Math.abs(rx) > 0.08f || Math.abs(ry) > 0.08f) {
            Object player = fPlayer.get(minecraft);
            if (player != null && mTurn != null) {
                float gain = sensitivity * (Math.abs(rx) > 0.95f || Math.abs(ry) > 0.95f ? 1.35f : 1.0f);
                mTurn.invoke(player, Double.valueOf(rx * gain), Double.valueOf(ry * gain));
            }
        }

        // Holds: Cross jump, Circle sneak, L2 mine/attack, R2 use/place.
        holdKey(minecraft, KEYIDX_JUMP, pad[BTN_A] > 0.5f);
        holdKey(minecraft, KEYIDX_SNEAK, pad[BTN_B] > 0.5f);
        holdKey(minecraft, KEYIDX_ATTACK, pad[15 + AXIS_LEFT_TRIGGER] > 0.5f);
        holdKey(minecraft, KEYIDX_USE, pad[15 + AXIS_RIGHT_TRIGGER] > 0.5f);

        // Taps: Square inventory, Triangle swap offhand, touchpad chat, D-pad perspective/drop,
        // R3 pick block, bumpers and D-pad left/right cycle the hotbar.
        if (pressed(pad, BTN_X)) tap(KEYIDX_INVENTORY);
        if (pressed(pad, BTN_Y)) tap(KEYIDX_SWAP);
        if (pressed(pad, BTN_BACK)) tap(KEYIDX_CHAT);
        if (pressed(pad, BTN_UP)) tap(KEYIDX_PERSPECTIVE);
        if (pressed(pad, BTN_DOWN)) tap(KEYIDX_DROP);
        if (pressed(pad, BTN_RIGHT_STICK)) tap(KEYIDX_PICK);

        Object player = fPlayer.get(minecraft);
        if (player != null && fSelected != null) {
            Object inventory = byType(player, inventoryClass);
            if (inventory != null) {
                int selected = fSelected.getInt(inventory);
                int direction = 0;
                if (pressed(pad, BTN_LEFT_BUMPER) || pressed(pad, BTN_LEFT)) direction--;
                if (pressed(pad, BTN_RIGHT_BUMPER) || pressed(pad, BTN_RIGHT)) direction++;
                if (direction != 0)
                    fSelected.setInt(inventory, ((selected + direction) % 9 + 9) % 9);
            }
        }

        // L3 sprints while held; Options opens the pause menu with an Escape key press.
        holdKey(minecraft, KEYIDX_SPRINT, pad[BTN_LEFT_STICK] > 0.5f);
        menuPress(minecraft, EDGE_ESCAPE, KEY_ESCAPE, pad[BTN_START] > 0.5f, 0);
    }

    /** A tap: one click in the mapping's click counter, which the game's own key handling consumes. */
    private static void tap(int key) throws Throwable {
        Object mapping = optionKeys[key];
        if (mapping != null && fClicks != null) fClicks.setInt(mapping, fClicks.getInt(mapping) + 1);
    }

    private static void holdKey(Object minecraft, int key, boolean down) throws Throwable {
        if (heldKeys[key] == down) return;
        heldKeys[key] = down;
        Object mapping = optionKeys[key];
        if (mapping != null && mSetDown != null) mSetDown.invoke(mapping, Boolean.valueOf(down));
    }

    // ------------------------------------------------------------------ menus

    private static void menuTick(Object minecraft, float[] pad) throws Throwable {
        // Release whatever the game held before the menu opened.
        for (int key = 0; key < KEYIDX_COUNT; key++) holdKey(minecraft, key, false);
        menuPress(minecraft, EDGE_ESCAPE, KEY_ESCAPE, pad[BTN_B] > 0.5f, 0);          // Circle
        menuPress(minecraft, EDGE_ENTER, KEY_ENTER, pad[BTN_A] > 0.5f, 0);            // Cross
        menuPress(minecraft, EDGE_UP, KEY_UP, pad[BTN_UP] > 0.5f || pad[BTN_COUNT + AXIS_LEFT_Y] < -deadzone, 0);
        menuPress(minecraft, EDGE_DOWN, KEY_DOWN, pad[BTN_DOWN] > 0.5f || pad[BTN_COUNT + AXIS_LEFT_Y] > deadzone, 0);
        menuPress(minecraft, EDGE_LEFT, KEY_LEFT, pad[BTN_LEFT] > 0.5f || pad[BTN_COUNT + AXIS_LEFT_X] < -deadzone, 0);
        menuPress(minecraft, EDGE_RIGHT, KEY_RIGHT, pad[BTN_RIGHT] > 0.5f || pad[BTN_COUNT + AXIS_LEFT_X] > deadzone, 0);
        menuPress(minecraft, EDGE_TAB, KEY_TAB, pad[BTN_Y] > 0.5f, GLFW_MOD_SHIFT);   // Triangle: back through controls
    }

    private static void menuPress(Object minecraft, int edge, int key, boolean now, int mods) throws Throwable {
        if (now && !menuEdge[edge]) pressKey(minecraft, key, GLFW_PRESS, mods);
        else if (!now && menuEdge[edge]) pressKey(minecraft, key, GLFW_RELEASE, mods);
        menuEdge[edge] = now;
    }

    private static void pressKey(Object minecraft, int key, int action, int mods) throws Throwable {
        if (mKeyPress == null || fKeyboard == null || mWindowHandle == null || mGameWindow == null) return;
        Object keyboard = fKeyboard.get(minecraft);
        if (keyboard == null) return;
        Object window = mWindowHandle.invoke(minecraft);
        if (window == null) return;
        long handle = ((Long) mGameWindow.invoke(window)).longValue();
        mKeyPress.invoke(keyboard, Long.valueOf(handle), Integer.valueOf(key), Integer.valueOf(0),
                Integer.valueOf(action), Integer.valueOf(mods));
    }

    private static void releaseAll(Object minecraft) {
        try {
            for (int key = 0; key < KEYIDX_COUNT; key++) holdKey(minecraft, key, false);
            for (int i = 0; i < menuEdge.length; i++) menuEdge[i] = false;
        } catch (Throwable gone) {
            // the controller is gone; the game keeps whatever state it had
        }
    }

    // ------------------------------------------------------------------ pad

    /** The pad's buttons then its six axes as floats, or null without a controller. */
    private static float[] poll() {
        if (!glfwReady && !glfwFailed) {
            try {
                Class<?> glfw = Class.forName("org.lwjgl.glfw.GLFW");
                Class<?> state = Class.forName("org.lwjgl.glfw.GLFWGamepadState");
                gamepadPoll = glfw.getMethod("glfwGetGamepadState", int.class, state);
                stateButtons = state.getMethod("buttons");
                stateAxes = state.getMethod("axes");
                gamepadState = state.getConstructor().newInstance();
                glfwReady = true;
            } catch (Throwable failure) {
                glfwFailed = true;
                System.err.println("orbis-compat: no GLFW gamepad API: " + failure);
            }
        }
        if (!glfwReady) return null;
        try {
            Boolean ok = (Boolean) gamepadPoll.invoke(null, Integer.valueOf(0), gamepadState);
            if (ok == null || !ok.booleanValue()) return null;
            java.nio.ByteBuffer buttons = (java.nio.ByteBuffer) stateButtons.invoke(gamepadState);
            java.nio.FloatBuffer axes = (java.nio.FloatBuffer) stateAxes.invoke(gamepadState);
            float[] out = new float[BTN_COUNT + AXIS_COUNT];
            for (int i = 0; i < BTN_COUNT; i++) out[i] = buttons.get(i) == (byte) 1 ? 1f : 0f;
            for (int i = 0; i < AXIS_COUNT; i++) out[BTN_COUNT + i] = axes.get(i);
            return out;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static float axis(float[] pad, int index) {
        float value = pad[BTN_COUNT + index];
        return Math.abs(value) < deadzone ? 0f : value;
    }

    private static boolean pressed(float[] pad, int button) {
        return pad[button] > 0.5f && previous[button] <= 0.5f;
    }

    // ------------------------------------------------------------------ reflection

    private static void configure() {
        enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true"));
        sensitivity = property(SENSITIVITY_PROPERTY, 1.0f);
        deadzone = property(DEADZONE_PROPERTY, 0.25f);
    }

    private static float property(String name, float fallback) {
        try {
            return Float.parseFloat(System.getProperty(name, String.valueOf(fallback)).trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static boolean present(String name) {
        ClassLoader[] loaders = {ControllerBridge.class.getClassLoader(),
                Thread.currentThread().getContextClassLoader()};
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) continue;
            try {
                Class.forName(name, false, loaders[i]);
                return true;
            } catch (ClassNotFoundException absent) {
                // the next loader
            }
        }
        return false;
    }

    /**
     * Resolves every reflection target once, with the class loader of the running game. Each target
     * walks its names through the class candidates, so the first name that exists on the running
     * Minecraft wins, whatever mapping set it uses.
     */
    private static boolean resolve(Object minecraft) throws Throwable {
        if (resolved) return true;
        ClassLoader loader = minecraft.getClass().getClassLoader();
        minecraftClass = find(MINECRAFT_CLASSES, loader);
        keyClass = find(KEY_CLASSES, loader);
        entityClass = find(ENTITY_CLASSES, loader);
        inventoryClass = find(INVENTORY_CLASSES, loader);
        keyboardClass = find(KEYBOARD_CLASSES, loader);
        windowClass = find(WINDOW_CLASSES, loader);
        if (minecraftClass == null || keyClass == null) return false;
        if (!minecraftClass.isInstance(minecraft)) return false;

        fPlayer = field(minecraftClass, FIELD_PLAYER);
        fOptions = field(minecraftClass, FIELD_OPTIONS);
        fScreen = field(minecraftClass, FIELD_SCREEN);
        fClicks = field(keyClass, FIELD_CLICKS);
        mSetDown = method(keyClass, METHOD_SET_DOWN);
        mWindowHandle = method(minecraftClass, METHOD_WINDOW_HANDLE);
        if (entityClass != null) mTurn = method(entityClass, METHOD_TURN);
        if (inventoryClass != null) fSelected = field(inventoryClass, FIELD_SELECTED);
        if (keyboardClass != null) {
            mKeyPress = method(keyboardClass, METHOD_KEY_PRESS);
            // The game's keyboard handler: the only field of its type on Minecraft.
            fKeyboard = byTypeField(minecraftClass, keyboardClass);
        }
        if (windowClass != null) mGameWindow = method(windowClass, METHOD_GAME_WINDOW);

        Object options = fOptions != null ? fOptions.get(minecraft) : null;
        if (fPlayer == null || fOptions == null || options == null || mSetDown == null) return false;
        for (int i = 0; i < KEYIDX_COUNT; i++) {
            java.lang.reflect.Field f = null;
            for (Class<?> c = options.getClass(); f == null && c != null && c != Object.class; c = c.getSuperclass()) {
                for (int j = 0; j < OPTION_KEYS[i].length; j++) {
                    try {
                        f = c.getDeclaredField(OPTION_KEYS[i][j]);
                        break;
                    } catch (NoSuchFieldException next) {
                        // the next name, or the next class
                    }
                }
            }
            if (f == null) {
                System.err.println("orbis-compat: no key mapping for " + OPTION_KEYS[i][0]);
                return false;
            }
            f.setAccessible(true);
            optionKeys[i] = f.get(options);
        }
        resolved = true;
        System.out.println("orbis-compat: built-in controller ready ("
                + (minecraftClass.getName().endsWith("class_310") ? "intermediary" : minecraftClass.getName())
                + " names)");
        return true;
    }

    /** The first non-static field of the given type on the object's class or its parents, or null. */
    private static Object byType(Object owner, Class<?> type) throws Throwable {
        java.lang.reflect.Field f = byTypeField(owner.getClass(), type);
        return f != null ? f.get(owner) : null;
    }

    private static java.lang.reflect.Field byTypeField(Class<?> owner, Class<?> type) {
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].getType() == type && !java.lang.reflect.Modifier.isStatic(fields[i].getModifiers())) {
                    fields[i].setAccessible(true);
                    return fields[i];
                }
            }
        }
        return null;
    }

    private static Class<?> find(String[] names, ClassLoader loader) {
        ClassLoader[] loaders = {loader, ControllerBridge.class.getClassLoader()};
        for (int i = 0; i < names.length; i++) {
            for (int j = 0; j < loaders.length; j++) {
                if (loaders[j] == null) continue;
                try {
                    return Class.forName(names[i], false, loaders[j]);
                } catch (ClassNotFoundException next) {
                    // the next loader, then the next name
                }
            }
        }
        return null;
    }

    private static java.lang.reflect.Field field(Class<?> type, String[][] candidates) {
        if (type == null) return null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (int i = 0; i < candidates.length; i++) {
                for (int j = 0; j < candidates[i].length; j++) {
                    try {
                        java.lang.reflect.Field f = c.getDeclaredField(candidates[i][j]);
                        f.setAccessible(true);
                        return f;
                    } catch (NoSuchFieldException next) {
                        // the next name, then the next class
                    }
                }
            }
        }
        return null;
    }

    private static java.lang.reflect.Method method(Class<?> type, String[][] candidates) {
        if (type == null) return null;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (int i = 0; i < candidates.length; i++) {
                for (int j = 0; j < candidates[i].length; j++) {
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(candidates[i][j])) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
            }
        }
        return null;
    }
}
