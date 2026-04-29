package cn.li.forge1201.bridge;

import cn.li.forge1201.entity.ModEntities;
import cn.li.forge1201.entity.ScriptedEffectEntity;
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
import net.minecraft.world.item.Items;
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
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

public final class ForgeRuntimeBridge {
    private ForgeRuntimeBridge() {
    }

    public static String getItemRegistryPath(Item item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null ? key.getPath() : null;
    }

    public static Item getItemById(String itemId) {
        try {
            if (itemId == null || itemId.isEmpty()) {
                return null;
            }
            ResourceLocation id = new ResourceLocation(itemId);
            Item item = BuiltInRegistries.ITEM.get(id);
            return item == Items.AIR ? null : item;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ItemStack createItemStackById(String itemId, int count) {
        Item item = getItemById(itemId);
        if (item == null || count <= 0) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item, count);
    }

    public static Block getBlockById(String blockId) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(blockId));
    }

    public static String getBlockKey(Block block) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(block);
        return key != null ? key.toString() : null;
    }

    public static boolean postEvent(Event event) {
        return MinecraftForge.EVENT_BUS.post(event);
    }

    public static ParticleOptions getParticleType(String particleType) {
        if (particleType != null && !particleType.isEmpty()) {
            try {
                ResourceLocation id = particleType.contains(":")
                    ? new ResourceLocation(particleType)
                    : new ResourceLocation("my_mod", particleType.replace('-', '_'));
                ParticleType<?> dynamicType = BuiltInRegistries.PARTICLE_TYPE.get(id);
                if (dynamicType instanceof ParticleOptions options) {
                    return options;
                }
            } catch (Exception ignored) {
                // Fall back to predefined aliases below.
            }
        }
        return switch (particleType) {
            case "electric-spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "portal" -> ParticleTypes.PORTAL;
            case "flame" -> ParticleTypes.FLAME;
            case "smoke" -> ParticleTypes.SMOKE;
            case "end-rod" -> ParticleTypes.END_ROD;
            case "enchant" -> ParticleTypes.ENCHANT;
            case "angry-villager" -> ParticleTypes.ANGRY_VILLAGER;
            case "totem-of-undying" -> ParticleTypes.TOTEM_OF_UNDYING;
            case "generic" -> ParticleTypes.GLOW;
            default -> ParticleTypes.GLOW;
        };
    }

    public static boolean isLivingEntity(Entity entity) {
        return entity instanceof LivingEntity;
    }

    public static List<LivingEntity> getLivingEntitiesInAabb(ServerLevel level, AABB aabb) {
        return level.getEntitiesOfClass(LivingEntity.class, aabb);
    }

    public static List<Entity> getEntitiesInAabb(ServerLevel level, AABB aabb) {
        return level.getEntitiesOfClass(Entity.class, aabb);
    }

    public static String getEntityRegistryId(Entity entity) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key != null ? key.toString() : null;
    }

    public static boolean spawnLightning(ServerLevel level, double x, double y, double z) {
        Entity lightning = EntityType.LIGHTNING_BOLT.create(level);
        if (lightning == null) {
            return false;
        }
        lightning.moveTo(x, y, z);
        return level.addFreshEntity(lightning);
    }

    public static void createExplosion(ServerLevel level, double x, double y, double z, float radius, boolean fire) {
        Level.ExplosionInteraction interaction = fire
            ? Level.ExplosionInteraction.MOB
            : Level.ExplosionInteraction.NONE;
        level.explode(null, x, y, z, radius, interaction);
    }

    // ---- Class accessors (allow Clojure to extend protocols without Class/forName strings) ----

    public static Class<?> getItemStackClass() { return ItemStack.class; }
    public static Class<?> getItemClass() { return Item.class; }
    public static Class<?> getEntityClass() { return Entity.class; }
    public static Class<?> getPlayerClass() { return Player.class; }
    public static Class<?> getInventoryClass() { return Inventory.class; }
    public static Class<?> getServerPlayerClass() { return ServerPlayer.class; }
    public static Class<?> getAbstractContainerMenuClass() { return AbstractContainerMenu.class; }

    public static Class<?> getBlockStateClass() {
        return BlockState.class;
    }

    // ---- BlockState helpers (Clojure extends IBlockStateOps at runtime after bootstrap) ----

    public static boolean blockStateIsAir(Object state) {
        return ((BlockState) state).isAir();
    }

    public static Object blockStateGetBlock(Object state) {
        return ((BlockState) state).getBlock();
    }

    public static Object blockStateGetStateDefinition(Object state) {
        return ((BlockState) state).getBlock().getStateDefinition();
    }

    @SuppressWarnings("unchecked")
    public static Object blockStateGetProperty(Object stateDef, Object propName) {
        String name = propName instanceof String s ? s : String.valueOf(propName);
        return ((StateDefinition<?, ?>) stateDef).getProperty(name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object blockStateSetProperty(Object state, Object prop, Object value) {
        return ((BlockState) state).setValue((Property) prop, (Comparable) value);
    }

    // ---- Factory methods ----
    // ---- ItemStack instance helpers (avoids importing ItemStack which may trigger bootstrap) ----

    public static boolean isItemStackEmpty(Object stack) {
        return ((ItemStack) stack).isEmpty();
    }

    public static Object getItemFromStack(Object stack) {
        return ((ItemStack) stack).getItem();
    }

    public static int getItemStackCount(Object stack) {
        return ((ItemStack) stack).getCount();
    }

    public static String getItemKeyString(Object item) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey((Item) item);
        return key != null ? key.getNamespace() + ":" + key.getPath() : null;
    }

    public static int countPlayerItemById(Object playerObj, String itemId) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty()) {
            return 0;
        }
        Item item = getItemById(itemId);
        if (item == null) {
            return 0;
        }
        int total = 0;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean consumePlayerItemById(Object playerObj, String itemId, int amount) {
        if (!(playerObj instanceof Player player) || itemId == null || itemId.isEmpty() || amount <= 0) {
            return false;
        }
        Item item = getItemById(itemId);
        if (item == null) {
            return false;
        }
        int remaining = amount;
        Inventory inventory = player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (remaining <= 0) {
                break;
            }
            if (!stack.isEmpty() && stack.getItem() == item) {
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    public static boolean givePlayerItemStack(Object playerObj, Object stackObj) {
        if (!(playerObj instanceof Player player) || !(stackObj instanceof ItemStack stack) || stack.isEmpty()) {
            return false;
        }
        ItemStack copy = stack.copy();
        if (!player.getInventory().add(copy)) {
            player.drop(copy, false);
        }
        return true;
    }

    public static boolean spawnEntityByIdFromPlayer(Object playerObj, String entityId, float speed) {
        if (!(playerObj instanceof Player player) || entityId == null || entityId.isEmpty()) {
            return false;
        }
        Level level = player.level();
        if (level.isClientSide) {
            return true;
        }
        EntityType<?> type;
        try {
            type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entityId));
        } catch (Exception ignored) {
            return false;
        }
        if (type == null) {
            return false;
        }
        Entity entity = type.create(level);
        if (entity == null) {
            return false;
        }
        entity.moveTo(player.getX(), player.getEyeY() - 0.1D, player.getZ(), player.getYRot(), player.getXRot());
        Vec3 look = player.getLookAngle().normalize().scale(speed);
        entity.setDeltaMovement(look);
        if (entity instanceof Projectile projectile) {
            projectile.setOwner(player);
        }
        if (entity instanceof ScriptedEffectEntity scriptedEffect) {
            scriptedEffect.setOwnerPlayer(player);
            scriptedEffect.setPos(player.getX(), player.getY() + 1.0D, player.getZ());
        }
        return level.addFreshEntity(entity);
    }

    public static Object playerRaytraceBlock(Object playerObj, double reach, boolean sourceOnly) {
        if (!(playerObj instanceof Player player)) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        ClipContext.Fluid fluid = sourceOnly ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        HitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return (BlockHitResult) hit;
    }

    // ---- Factory methods ----

    public static Object itemStackOf(Object nbt) {
        return ItemStack.of((CompoundTag) nbt);
    }

    // ---- Field / method accessors (typed, Loom-remappable) ----

    public static Object getEntityLevel(Object entity) {
        return ((Entity) entity).level();
    }

    public static Object getPlayerContainerMenu(Object player) {
        return ((Player) player).containerMenu;
    }

    public static Object getInventoryPlayer(Object inventory) {
        return ((Inventory) inventory).player;
    }

    public static int getMenuContainerId(Object menu) {
        return ((AbstractContainerMenu) menu).containerId;
    }

    public static boolean registerScriptedEffectHookClass(String hookId, String className) {
        return ModEntities.registerScriptedEffectHookClass(hookId, className);
    }

    public static boolean registerScriptedRayHookClass(String hookId, String className) {
        return ModEntities.registerScriptedRayHookClass(hookId, className);
    }

    public static boolean registerScriptedMarkerHookClass(String hookId, String className) {
        return ModEntities.registerScriptedMarkerHookClass(hookId, className);
    }

    // ---- MobEffects lookup (avoids Class/forName on MobEffects registry class) ----

    public static MobEffect getMobEffect(String fieldName) {
        return switch (fieldName) {
            case "MOVEMENT_SPEED"      -> MobEffects.MOVEMENT_SPEED;
            case "MOVEMENT_SLOWDOWN"   -> MobEffects.MOVEMENT_SLOWDOWN;
            case "JUMP"                -> MobEffects.JUMP;
            case "REGENERATION"        -> MobEffects.REGENERATION;
            case "DAMAGE_BOOST"        -> MobEffects.DAMAGE_BOOST;
            case "DAMAGE_RESISTANCE"   -> MobEffects.DAMAGE_RESISTANCE;
            case "HUNGER"              -> MobEffects.HUNGER;
            case "BLINDNESS"           -> MobEffects.BLINDNESS;
            case "DIG_SPEED"           -> MobEffects.DIG_SPEED;
            case "DIG_SLOWDOWN"        -> MobEffects.DIG_SLOWDOWN;
            case "CONFUSION"           -> MobEffects.CONFUSION;
            case "INVISIBILITY"        -> MobEffects.INVISIBILITY;
            case "NIGHT_VISION"        -> MobEffects.NIGHT_VISION;
            case "WEAKNESS"            -> MobEffects.WEAKNESS;
            case "POISON"              -> MobEffects.POISON;
            case "WITHER"              -> MobEffects.WITHER;
            case "HEALTH_BOOST"        -> MobEffects.HEALTH_BOOST;
            case "ABSORPTION"          -> MobEffects.ABSORPTION;
            case "SATURATION"          -> MobEffects.SATURATION;
            case "GLOWING"             -> MobEffects.GLOWING;
            case "LEVITATION"          -> MobEffects.LEVITATION;
            case "LUCK"                -> MobEffects.LUCK;
            case "UNLUCK"              -> MobEffects.UNLUCK;
            case "SLOW_FALLING"        -> MobEffects.SLOW_FALLING;
            case "CONDUIT_POWER"       -> MobEffects.CONDUIT_POWER;
            case "DOLPHINS_GRACE"      -> MobEffects.DOLPHINS_GRACE;
            case "BAD_OMEN"            -> MobEffects.BAD_OMEN;
            case "HERO_OF_THE_VILLAGE" -> MobEffects.HERO_OF_THE_VILLAGE;
            case "DARKNESS"            -> MobEffects.DARKNESS;
            default -> null;
        };
    }
}