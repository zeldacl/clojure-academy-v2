package cn.li.forge1201.capability;

import clojure.lang.RT;
import clojure.lang.Var;
import net.minecraft.core.Direction;

import javax.annotation.Nullable;

/**
 * Dynamic bridge from Forge capability requests into shared Clojure tile logic.
 */
public final class ForgeCapabilityResolver {

    private ForgeCapabilityResolver() {
    }

    @Nullable
    public static Object resolve(String tileId, String key, Object owner, @Nullable Direction side) {
        try {
            Var getCapFn = RT.var("cn.li.mcmod.block.tile-logic", "get-capability");
            return getCapFn.invoke(tileId, key, owner, side);
        } catch (Exception ignored) {
            return null;
        }
    }
}