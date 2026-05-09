package cn.li.mc1201.entity.hook;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/**
 * Shared client-side per-tick hook contract for scripted entities.
 *
 * @param <E> entity type handled by this hook
 */
public interface ClientEntityHook<E extends Entity> {
    void onClientTick(E entity, ClientLevel level);
}
