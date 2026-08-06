package cn.li.mc1201.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * @deprecated Use {@link cn.li.mcbase.client.KeyMappingAccess}.
 */
@Deprecated
public final class KeyMappingAccess {
    private KeyMappingAccess() {
    }

    public static InputConstants.Key getKey(KeyMapping mapping) {
        return cn.li.mcbase.client.KeyMappingAccess.getKey(mapping);
    }

    public static int acKeyCode(KeyMapping mapping) {
        return cn.li.mcbase.client.KeyMappingAccess.acKeyCode(mapping);
    }

    public static String boundKeyDisplayString(KeyMapping mapping) {
        return cn.li.mcbase.client.KeyMappingAccess.boundKeyDisplayString(mapping);
    }

    public static int boundKeyValue(KeyMapping mapping) {
        return cn.li.mcbase.client.KeyMappingAccess.boundKeyValue(mapping);
    }

    public static Component boundKeyDisplayName(KeyMapping mapping) {
        return cn.li.mcbase.client.KeyMappingAccess.boundKeyDisplayName(mapping);
    }
}
