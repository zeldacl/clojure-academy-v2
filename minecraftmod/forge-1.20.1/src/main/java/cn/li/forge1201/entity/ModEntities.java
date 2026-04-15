package cn.li.forge1201.entity;

import cn.li.forge1201.MyMod1201;
import cn.li.forge1201.entity.effect.IntensifyEffectEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    private ModEntities() {
    }

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MyMod1201.MODID);

    public static final RegistryObject<EntityType<IntensifyEffectEntity>> INTENSIFY_EFFECT =
            ENTITY_TYPES.register("intensify_effect",
                    () -> EntityType.Builder.<IntensifyEffectEntity>of(IntensifyEffectEntity::new, MobCategory.MISC)
                            .sized(0.1F, 0.1F)
                            .clientTrackingRange(64)
                            .updateInterval(1)
                            .build(MyMod1201.MODID + ":intensify_effect"));

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
