package cn.li.mcbase.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * KeyMapping current-binding accessors. Mojang keeps the live key private;
 * Forge may expose {@code getKey()} via AT, Fabric does not. Reflective
 * lookup keeps Clojure AOT/checkClojure free of untyped interop.
 */
public final class KeyMappingAccess {
    private static final Method GET_KEY_METHOD;
    private static final Field KEY_FIELD;

    static {
        Method getKey = null;
        try {
            getKey = KeyMapping.class.getMethod("getKey");
        } catch (NoSuchMethodException ignored) {
            // fall through to field
        }
        GET_KEY_METHOD = getKey;

        Field keyField = null;
        if (getKey == null) {
            try {
                keyField = KeyMapping.class.getDeclaredField("key");
                keyField.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
        KEY_FIELD = keyField;
    }

    private KeyMappingAccess() {
    }

    public static InputConstants.Key getKey(KeyMapping mapping) {
        try {
            if (GET_KEY_METHOD != null) {
                return (InputConstants.Key) GET_KEY_METHOD.invoke(mapping);
            }
            return (InputConstants.Key) KEY_FIELD.get(mapping);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to read KeyMapping key", e);
        }
    }

    public static int acKeyCode(KeyMapping mapping) {
        InputConstants.Key key = getKey(mapping);
        int value = key.getValue();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return -100 + value;
        }
        return value;
    }

    public static String boundKeyDisplayString(KeyMapping mapping) {
        return getKey(mapping).getDisplayName().getString();
    }

    public static int boundKeyValue(KeyMapping mapping) {
        return getKey(mapping).getValue();
    }

    public static Component boundKeyDisplayName(KeyMapping mapping) {
        return getKey(mapping).getDisplayName();
    }
}
