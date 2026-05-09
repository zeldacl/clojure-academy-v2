package cn.li.fabric1201.entity;

import cn.li.fabric1201.entity.effect.hooks.FabricScriptedEffectHooks;
import cn.li.fabric1201.entity.marker.hooks.FabricScriptedMarkerHooks;
import cn.li.fabric1201.entity.ray.hooks.FabricScriptedRayHooks;
import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedProjectileSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-side installer for shared scripted entity access.
 *
 * <p>This is a minimal bridge for migration: it provides shared spec lookup
 * and entity type lookup storage without introducing Forge-specific classes.</p>
 */
public final class FabricScriptedEntityAccess {
    private static final Map<String, EntityType<?>> REGISTERED_ENTITY_TYPES = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedProjectileSpec> SCRIPTED_PROJECTILE_SPECS = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedEffectSpec> SCRIPTED_EFFECT_SPECS = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedRaySpec> SCRIPTED_RAY_SPECS = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedMarkerSpec> SCRIPTED_MARKER_SPECS = new ConcurrentHashMap<>();
    private static final Map<String, ScriptedBlockBodySpec> SCRIPTED_BLOCK_BODY_SPECS = new ConcurrentHashMap<>();

    private FabricScriptedEntityAccess() {
    }

    public static void install() {
        ScriptedEntitySpecAccess.install(new ScriptedEntitySpecAccess.Accessor() {
            @Override
            @SuppressWarnings("unchecked")
            public <T extends Entity> EntityType<T> requireEntityType(String registryName, Class<T> expectedClass) {
                EntityType<?> type = REGISTERED_ENTITY_TYPES.get(registryName);
                if (type == null) {
                    throw new IllegalStateException("Fabric entity type is not registered: " + registryName);
                }
                return (EntityType<T>) type;
            }

            @Override
            public Class<? extends Entity> resolveEntityClassByKind(String entityKind) {
                if (entityKind == null || entityKind.isEmpty()) {
                    return null;
                }
                return switch (entityKind) {
                    case "scripted-projectile" -> FabricScriptedProjectileEntity.class;
                    case "scripted-effect" -> FabricScriptedEffectEntity.class;
                    case "scripted-ray" -> FabricScriptedRayEntity.class;
                    case "scripted-marker" -> FabricScriptedMarkerEntity.class;
                    case "scripted-block-body" -> FabricScriptedBlockBodyEntity.class;
                    default -> null;
                };
            }

            @Override
            public ScriptedEffectSpec getScriptedEffectSpec(EntityType<?> entityType) {
                return SCRIPTED_EFFECT_SPECS.get(registryName(entityType));
            }

            @Override
            public ScriptedRaySpec getScriptedRaySpec(EntityType<?> entityType) {
                return SCRIPTED_RAY_SPECS.get(registryName(entityType));
            }

            @Override
            public ScriptedMarkerSpec getScriptedMarkerSpec(EntityType<?> entityType) {
                return SCRIPTED_MARKER_SPECS.get(registryName(entityType));
            }

            @Override
            public ScriptedProjectileSpec getScriptedProjectileSpec(EntityType<?> entityType) {
                return SCRIPTED_PROJECTILE_SPECS.get(registryName(entityType));
            }

            @Override
            public ScriptedBlockBodySpec getScriptedBlockBodySpec(EntityType<?> entityType) {
                return SCRIPTED_BLOCK_BODY_SPECS.get(registryName(entityType));
            }

            @Override
            public boolean registerScriptedEffectHookClass(String hookId, String className) {
                return FabricScriptedEffectHooks.registerByClassName(hookId, className);
            }

            @Override
            public boolean registerScriptedRayHookClass(String hookId, String className) {
                return FabricScriptedRayHooks.registerByClassName(hookId, className);
            }

            @Override
            public boolean registerScriptedMarkerHookClass(String hookId, String className) {
                return FabricScriptedMarkerHooks.registerByClassName(hookId, className);
            }
        });
    }

    public static void registerEntityType(String registryName, EntityType<?> entityType) {
        if (registryName == null || registryName.isEmpty() || entityType == null) {
            return;
        }
        REGISTERED_ENTITY_TYPES.put(registryName, entityType);
    }

    public static void registerScriptedProjectileSpec(String registryName, ScriptedProjectileSpec spec) {
        if (registryName != null && !registryName.isEmpty() && spec != null) {
            SCRIPTED_PROJECTILE_SPECS.put(registryName, spec);
        }
    }

    public static void registerScriptedEffectSpec(String registryName, ScriptedEffectSpec spec) {
        if (registryName != null && !registryName.isEmpty() && spec != null) {
            SCRIPTED_EFFECT_SPECS.put(registryName, spec);
        }
    }

    public static void registerScriptedRaySpec(String registryName, ScriptedRaySpec spec) {
        if (registryName != null && !registryName.isEmpty() && spec != null) {
            SCRIPTED_RAY_SPECS.put(registryName, spec);
        }
    }

    public static void registerScriptedMarkerSpec(String registryName, ScriptedMarkerSpec spec) {
        if (registryName != null && !registryName.isEmpty() && spec != null) {
            SCRIPTED_MARKER_SPECS.put(registryName, spec);
        }
    }

    public static void registerScriptedBlockBodySpec(String registryName, ScriptedBlockBodySpec spec) {
        if (registryName != null && !registryName.isEmpty() && spec != null) {
            SCRIPTED_BLOCK_BODY_SPECS.put(registryName, spec);
        }
    }

    private static String registryName(EntityType<?> entityType) {
        if (entityType == null) {
            return null;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
        return key == null ? null : key.getPath();
    }
}
