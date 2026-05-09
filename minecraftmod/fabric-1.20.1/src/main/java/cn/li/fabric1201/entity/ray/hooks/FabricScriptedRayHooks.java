package cn.li.fabric1201.entity.ray.hooks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricScriptedRayHooks {
    private static final FabricScriptedRayHook NOOP = (entity, level) -> {
    };
    private static final Map<String, FabricScriptedRayHook> HOOKS = new ConcurrentHashMap<>();

    private FabricScriptedRayHooks() {
    }

    public static void register(String hookId, FabricScriptedRayHook hook) {
        if (hookId == null || hookId.isEmpty() || hook == null) {
            return;
        }
        HOOKS.put(hookId, hook);
    }

    public static FabricScriptedRayHook resolve(String hookId) {
        if (hookId == null || hookId.isEmpty()) {
            return NOOP;
        }
        return HOOKS.getOrDefault(hookId, NOOP);
    }

    public static boolean registerByClassName(String hookId, String className) {
        if (hookId == null || hookId.isEmpty() || className == null || className.isEmpty()) {
            return false;
        }
        try {
            Class<?> rawClass = Class.forName(className);
            if (!FabricScriptedRayHook.class.isAssignableFrom(rawClass)) {
                return false;
            }
            @SuppressWarnings("unchecked")
            Class<? extends FabricScriptedRayHook> hookClass = (Class<? extends FabricScriptedRayHook>) rawClass;
            FabricScriptedRayHook hook = hookClass.getDeclaredConstructor().newInstance();
            register(hookId, hook);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
