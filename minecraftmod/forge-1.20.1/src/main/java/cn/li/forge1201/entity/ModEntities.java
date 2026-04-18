package cn.li.forge1201.entity;

import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.entity.effect.hooks.ScriptedEffectHooks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class ModEntities {
    private ModEntities() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MyMod1201.MODID);

    private static final Map<String, RegistryObject<EntityType<?>>> REGISTERED_ENTITY_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedProjectileSpec> SCRIPTED_PROJECTILE_SPECS = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedEffectSpec> SCRIPTED_EFFECT_SPECS = new ConcurrentHashMap<>();

    public static RegistryObject<EntityType<?>> register(String registryName, Supplier<? extends EntityType<?>> supplier) {
        RegistryObject<EntityType<?>> entry = ENTITY_TYPES.register(registryName, () -> (EntityType<?>) supplier.get());
        REGISTERED_ENTITY_TYPES.put(registryName, entry);
        return entry;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Entity> EntityType<T> getEntityType(String registryName, Class<T> expectedClass) {
        RegistryObject<EntityType<?>> entry = REGISTERED_ENTITY_TYPES.get(registryName);
        if (entry == null) {
            return null;
        }
        return (EntityType<T>) entry.get();
    }

    public static <T extends Entity> EntityType<T> requireEntityType(String registryName, Class<T> expectedClass) {
        EntityType<T> type = getEntityType(registryName, expectedClass);
        if (type == null) {
            throw new IllegalStateException("Entity type is not registered: " + registryName);
        }
        return type;
    }

    public static void registerScriptedProjectileSpec(String registryName,
                                                      String defaultItemId,
                                                      double gravity,
                                                      double damage,
                                                      double pickupDistanceSqr,
                                                      boolean dropItemOnDiscard,
                                                      String onHitBlockHook,
                                                      String onHitEntityHook,
                                                      String onAnchoredTickHook,
                                                      String onAnchoredHurtHook) {
        SCRIPTED_PROJECTILE_SPECS.put(
                registryName,
                new ScriptedProjectileSpec(
                        defaultItemId,
                        gravity,
                        damage,
                        pickupDistanceSqr,
                        dropItemOnDiscard,
                        onHitBlockHook,
                        onHitEntityHook,
                        onAnchoredTickHook,
                        onAnchoredHurtHook
                )
        );
    }

    public static ScriptedProjectileSpec getScriptedProjectileSpec(String registryName) {
        return SCRIPTED_PROJECTILE_SPECS.get(registryName);
    }

    public static ScriptedProjectileSpec getScriptedProjectileSpec(EntityType<?> entityType) {
        if (entityType == null) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        if (key == null) {
            return null;
        }
        return SCRIPTED_PROJECTILE_SPECS.get(key.getPath());
    }

    public static void registerScriptedEffectSpec(String registryName,
                                                  int lifeTicks,
                                                  boolean followOwner,
                                                  String effectHook) {
        SCRIPTED_EFFECT_SPECS.put(
                registryName,
                new ScriptedEffectSpec(lifeTicks, followOwner, effectHook)
        );
    }

    public static ScriptedEffectSpec getScriptedEffectSpec(String registryName) {
        return SCRIPTED_EFFECT_SPECS.get(registryName);
    }

    public static ScriptedEffectSpec getScriptedEffectSpec(EntityType<?> entityType) {
        if (entityType == null) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        if (key == null) {
            return null;
        }
        return SCRIPTED_EFFECT_SPECS.get(key.getPath());
    }

    public static boolean registerScriptedEffectHookClass(String hookId, String className) {
        return ScriptedEffectHooks.registerByClassName(hookId, className);
    }

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
