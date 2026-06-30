package cn.li.forge1201.capability;

import cn.li.mc1201.block.IScriptedBlock;
import cn.li.mc1201.block.entity.AbstractScriptedBlockEntity;
import cn.li.mc1201.block.logic.ITileCapabilityLogic;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Orchestrates Forge capability query, resolution, caching, and lifecycle for scripted block entities.
 */
public final class ForgeCapabilityHandler {
    private final ForgeCapabilityCache cache = new ForgeCapabilityCache();

    @Nonnull
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap,
                                             @Nullable Direction side,
                                             AbstractScriptedBlockEntity be) {
        String key = ForgeCapabilityQuery.getKey(cap);
        if (key == null) {
            return LazyOptional.empty();
        }

        LazyOptional<Object> cached = cache.get(key);
        if (cached != null && cached.isPresent()) {
            return cached.cast();
        }

        Object handler = ForgeCapabilityResolver.resolve(be, key, side);
        if (handler != null) {
            LazyOptional<Object> lazyOptional = LazyOptional.of(() -> handler);
            cache.put(key, lazyOptional);
            return lazyOptional.cast();
        }
        return LazyOptional.empty();
    }

    public void invalidate() {
        cache.invalidate();
    }

    public void revive() {
        cache.revive();
    }
}
