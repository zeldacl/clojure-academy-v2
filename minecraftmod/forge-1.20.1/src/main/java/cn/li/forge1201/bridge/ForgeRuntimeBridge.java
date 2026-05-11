package cn.li.forge1201.bridge;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.runtime.BlockRegistryShared;
import cn.li.mc1201.runtime.ItemInventoryShared;
import cn.li.mc1201.runtime.ItemPlayerOpsShared;
import cn.li.mc1201.runtime.ItemRegistryShared;
import cn.li.mc1201.runtime.ParticleEntityShared;
import cn.li.mc1201.runtime.RuntimeAccessShared;
import cn.li.mc1201.runtime.WorldEntityShared;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;

import java.util.List;

public final class ForgeRuntimeBridge {
    private ForgeRuntimeBridge() {
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

    public static boolean postEvent(Event event) {
        return MinecraftForge.EVENT_BUS.post(event);
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

    public static boolean spawnLightning(ServerLevel level, double x, double y, double z) {
        return WorldEntityShared.spawnLightning(level, x, y, z);
    }

    public static void createExplosion(ServerLevel level, double x, double y, double z, float radius, boolean fire) {
        WorldEntityShared.createExplosion(level, x, y, z, radius, fire);
    }

    // ---- Class accessors (allow Clojure to extend protocols without Class/forName strings) ----

    public static Class<?> getItemStackClass() { return RuntimeAccessShared.getItemStackClass(); }
    public static Class<?> getItemClass() { return RuntimeAccessShared.getItemClass(); }
    public static Class<?> getEntityClass() { return RuntimeAccessShared.getEntityClass(); }
    public static Class<?> getPlayerClass() { return RuntimeAccessShared.getPlayerClass(); }
    public static Class<?> getInventoryClass() { return RuntimeAccessShared.getInventoryClass(); }
    public static Class<?> getServerPlayerClass() { return RuntimeAccessShared.getServerPlayerClass(); }
    public static Class<?> getAbstractContainerMenuClass() { return RuntimeAccessShared.getAbstractContainerMenuClass(); }

    public static Class<?> getBlockStateClass() {
        return RuntimeAccessShared.getBlockStateClass();
    }

    // ---- BlockState helpers (Clojure extends IBlockStateOps at runtime after bootstrap) ----

    public static boolean blockStateIsAir(Object state) {
        return RuntimeAccessShared.blockStateIsAir(state);
    }

    public static Object blockStateGetBlock(Object state) {
        return RuntimeAccessShared.blockStateGetBlock(state);
    }

    public static Object blockStateGetStateDefinition(Object state) {
        return RuntimeAccessShared.blockStateGetStateDefinition(state);
    }

    public static Object blockStateGetProperty(Object stateDef, Object propName) {
        return RuntimeAccessShared.blockStateGetProperty(stateDef, propName);
    }

    public static Object blockStateSetProperty(Object state, Object prop, Object value) {
        return RuntimeAccessShared.blockStateSetProperty(state, prop, value);
    }

    // ---- Factory methods ----
    // ---- ItemStack instance helpers (avoids importing ItemStack which may trigger bootstrap) ----

    public static boolean isItemStackEmpty(Object stack) {
        return ItemInventoryShared.isItemStackEmpty(stack);
    }

    public static Object getItemFromStack(Object stack) {
        return ItemInventoryShared.getItemFromStack(stack);
    }

    public static int getItemStackCount(Object stack) {
        return ItemInventoryShared.getItemStackCount(stack);
    }

    public static String getItemKeyString(Object item) {
        return ItemInventoryShared.getItemKeyString(item);
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

    public static boolean spawnEntityByIdFromPlayer(Object playerObj, String entityId, float speed) {
        return ParticleEntityShared.spawnEntityByIdFromPlayer(playerObj, entityId, speed);
    }

    public static Object playerRaytraceBlock(Object playerObj, double reach, boolean sourceOnly) {
        return RuntimeAccessShared.playerRaytraceBlock(playerObj, reach, sourceOnly);
    }

    // ---- Factory methods ----

    public static Object itemStackOf(Object nbt) {
        return RuntimeAccessShared.itemStackOf(nbt);
    }

    // ---- Field / method accessors (typed, Loom-remappable) ----

    public static Object getEntityLevel(Object entity) {
        return RuntimeAccessShared.getEntityLevel(entity);
    }

    public static Object getPlayerContainerMenu(Object player) {
        return RuntimeAccessShared.getPlayerContainerMenu(player);
    }

    public static Object getInventoryPlayer(Object inventory) {
        return RuntimeAccessShared.getInventoryPlayer(inventory);
    }

    public static int getMenuContainerId(Object menu) {
        return RuntimeAccessShared.getMenuContainerId(menu);
    }

    public static boolean registerScriptedEffectHookClass(String hookId, String className) {
        return ScriptedEntitySpecAccess.registerScriptedEffectHookClass(hookId, className);
    }

    public static boolean registerScriptedRayHookClass(String hookId, String className) {
        return ScriptedEntitySpecAccess.registerScriptedRayHookClass(hookId, className);
    }

    public static boolean registerScriptedMarkerHookClass(String hookId, String className) {
        return ScriptedEntitySpecAccess.registerScriptedMarkerHookClass(hookId, className);
    }

    // ---- MobEffects lookup (avoids Class/forName on MobEffects registry class) ----

    public static MobEffect getMobEffect(String fieldName) {
        return RuntimeAccessShared.getMobEffect(fieldName);
    }
}