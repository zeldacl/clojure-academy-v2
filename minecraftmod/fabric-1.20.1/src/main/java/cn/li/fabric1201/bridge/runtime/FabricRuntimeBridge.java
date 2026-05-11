package cn.li.fabric1201.bridge.runtime;

import cn.li.mc1201.runtime.BlockRegistryShared;
import cn.li.mc1201.runtime.ItemPlayerOpsShared;
import cn.li.mc1201.runtime.ItemRegistryShared;
import cn.li.mc1201.runtime.ParticleEntityShared;
import cn.li.mc1201.runtime.RuntimeAccessShared;
import cn.li.mc1201.runtime.WorldEntityShared;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Fabric thin runtime bridge backed by shared mc1201 helpers.
 */
public final class FabricRuntimeBridge {
    private FabricRuntimeBridge() {
    }

    public static String getItemRegistryPath(Item item) {
        return ItemRegistryShared.getItemRegistryPath(item);
    }

    public static Item getItemById(String itemId) {
        return ItemRegistryShared.getItemById(itemId);
    }

    public static ItemStack createItemStackById(String itemId, int count) {
        return ItemRegistryShared.createItemStackById(itemId, count);
    }

    public static Block getBlockById(String blockId) {
        return BlockRegistryShared.getBlockById(blockId);
    }

    public static String getBlockKey(Block block) {
        return BlockRegistryShared.getBlockKey(block);
    }

    public static ParticleOptions getParticleType(String particleType) {
        return ParticleEntityShared.getParticleType(particleType);
    }

    public static boolean isLivingEntity(Entity entity) {
        return WorldEntityShared.isLivingEntity(entity);
    }

    public static List<LivingEntity> getLivingEntitiesInAabb(ServerLevel level, AABB aabb) {
        return WorldEntityShared.getLivingEntitiesInAabb(level, aabb);
    }

    public static List<Entity> getEntitiesInAabb(ServerLevel level, AABB aabb) {
        return WorldEntityShared.getEntitiesInAabb(level, aabb);
    }

    public static String getEntityRegistryId(Entity entity) {
        return RuntimeAccessShared.getEntityRegistryId(entity);
    }

    public static int countPlayerItemById(Object playerObj, String itemId) {
        return ItemPlayerOpsShared.countPlayerItemById(playerObj, itemId);
    }

    public static boolean consumePlayerItemById(Object playerObj, String itemId, int amount) {
        return ItemPlayerOpsShared.consumePlayerItemById(playerObj, itemId, amount);
    }

    public static boolean givePlayerItemStack(Object playerObj, Object stackObj) {
        return ItemPlayerOpsShared.givePlayerItemStack(playerObj, stackObj);
    }
}
