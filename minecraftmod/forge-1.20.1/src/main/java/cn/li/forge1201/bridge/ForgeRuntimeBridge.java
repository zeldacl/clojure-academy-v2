package cn.li.forge1201.bridge;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleOptions;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;
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
        return switch (particleType) {
            case "electric-spark" -> ParticleTypes.ELECTRIC_SPARK;
            case "portal" -> ParticleTypes.PORTAL;
            case "flame" -> ParticleTypes.FLAME;
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