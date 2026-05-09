package cn.li.fabric1201.entity;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.HashMap;
import java.util.Map;

/**
 * Fabric entity type registration.
 * 
 * Registers the five scripted entity types (projectile, effect, ray, marker, block-body)
 * using Fabric's Registry system.
 */
public final class FabricEntities {
    private static final String MOD_ID = "my_mod";
    private static final Map<String, EntityType<?>> ENTITY_TYPES = new HashMap<>();

    private FabricEntities() {
    }

    public static void registerEntities() {
        // Register projectile entity
        EntityType<FabricScriptedProjectileEntity> projectileType = EntityType.Builder.of(
                FabricScriptedProjectileEntity::new,
                MobCategory.MISC
        )
                .sized(0.5f, 0.5f)
                .build(MOD_ID + ":scripted-projectile");
        registerEntityType("scripted-projectile", projectileType);

        // Register effect entity
        EntityType<FabricScriptedEffectEntity> effectType = EntityType.Builder.of(
                FabricScriptedEffectEntity::new,
                MobCategory.MISC
        )
                .sized(0.5f, 0.5f)
                .noSummon()
                .noSave()
                .build(MOD_ID + ":scripted-effect");
        registerEntityType("scripted-effect", effectType);

        // Register ray entity
        EntityType<FabricScriptedRayEntity> rayType = EntityType.Builder.of(
                FabricScriptedRayEntity::new,
                MobCategory.MISC
        )
                .sized(0.5f, 0.5f)
                .noSummon()
                .noSave()
                .build(MOD_ID + ":scripted-ray");
        registerEntityType("scripted-ray", rayType);

        // Register marker entity
        EntityType<FabricScriptedMarkerEntity> markerType = EntityType.Builder.of(
                FabricScriptedMarkerEntity::new,
                MobCategory.MISC
        )
                .sized(0.1f, 0.1f)
                .noSummon()
                .noSave()
                .build(MOD_ID + ":scripted-marker");
        registerEntityType("scripted-marker", markerType);

        // Register block-body entity
        EntityType<FabricScriptedBlockBodyEntity> blockBodyType = EntityType.Builder.of(
                FabricScriptedBlockBodyEntity::new,
                MobCategory.MISC
        )
                .sized(1.0f, 1.0f)
                .build(MOD_ID + ":scripted-block-body");
        registerEntityType("scripted-block-body", blockBodyType);
    }

    private static <E extends net.minecraft.world.entity.Entity> void registerEntityType(
            String id, EntityType<E> entityType) {
        ResourceLocation location = new ResourceLocation(MOD_ID, id);
        @SuppressWarnings("unchecked")
        EntityType<E> registered = (EntityType<E>) Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                location,
                entityType
        );
        ENTITY_TYPES.put(id, registered);
        // Also register with the shared accessor
        FabricScriptedEntityAccess.registerEntityType(location.toString(), registered);
    }

    public static EntityType<?> getEntityType(String id) {
        return ENTITY_TYPES.get(id);
    }
}
