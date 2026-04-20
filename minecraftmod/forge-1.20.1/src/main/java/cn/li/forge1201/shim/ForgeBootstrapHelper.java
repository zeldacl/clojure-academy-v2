package cn.li.forge1201.shim;

import cn.li.forge1201.block.DynamicStateBlock;
import cn.li.forge1201.block.ScriptedBlock;
import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.forge1201.entity.ScriptedProjectileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.core.particles.ParticleType;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.lang.reflect.Constructor;
import java.util.function.Function;
import java.util.List;
import java.util.function.Supplier;

public final class ForgeBootstrapHelper {
    private ForgeBootstrapHelper() {
    }

    public static DeferredRegister<Block> createBlocksRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.BLOCKS, modId);
    }

    public static DeferredRegister<Item> createItemsRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.ITEMS, modId);
    }

    public static DeferredRegister<BlockEntityType<?>> createBlockEntityTypesRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, modId);
    }

    public static DeferredRegister<CreativeModeTab> createCreativeTabsRegister(String modId) {
        return DeferredRegister.create(Registries.CREATIVE_MODE_TAB, modId);
    }

    public static DeferredRegister<MenuType<?>> createMenusRegister(String modId) {
        return DeferredRegister.create(Registries.MENU, modId);
    }

    public static DeferredRegister<FluidType> createFluidTypesRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.Keys.FLUID_TYPES, modId);
    }

    public static DeferredRegister<Fluid> createFluidsRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.FLUIDS, modId);
    }

    public static DeferredRegister<SoundEvent> createSoundsRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, modId);
    }

    public static DeferredRegister<MobEffect> createEffectsRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, modId);
    }

    public static DeferredRegister<ParticleType<?>> createParticleTypesRegister(String modId) {
        return DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, modId);
    }

    public static BlockBehaviour.Properties createStoneProperties() {
        return BlockBehaviour.Properties.copy(Blocks.STONE);
    }

    public static BlockBehaviour.Properties carrierBlockProperties(BlockBehaviour.Properties base) {
        BlockBehaviour.StatePredicate alwaysFalse = new BlockBehaviour.StatePredicate() {
            @Override
            public boolean test(BlockState state, BlockGetter level, BlockPos pos) {
                return false;
            }
        };
        return base.noOcclusion()
            .forceSolidOff()
            .isViewBlocking(alwaysFalse)
            .isSuffocating(alwaysFalse)
            .isRedstoneConductor(alwaysFalse);
    }

    public static Block createCarrierScriptedDynamicBlock(String blockId, String tileId, List<Property<?>> properties, BlockBehaviour.Properties blockProperties) {
        return ScriptedBlock.create(blockId, tileId, new ArrayList<>(properties), blockProperties);
    }

    public static Block createDynamicStateBlock(String blockId, List<Property<?>> properties, BlockBehaviour.Properties blockProperties) {
        return DynamicStateBlock.create(blockId, new ArrayList<>(properties), blockProperties);
    }

    public static Block createCarrierScriptedBlock(String blockId, String tileId, BlockBehaviour.Properties blockProperties) {
        return new ScriptedBlock(blockId, tileId, blockProperties);
    }

    public static Block createPlainBlock(BlockBehaviour.Properties blockProperties) {
        return new Block(blockProperties);
    }

    public static FluidType createFluidType(int luminosity,
                                            int density,
                                            int viscosity,
                                            int temperature,
                                            boolean canHydrate,
                                            boolean supportsBoating,
                                            String stillTexture,
                                            String flowingTexture,
                                            String overlayTexture,
                                            int tintColor) {
        FluidType.Properties props = FluidType.Properties.create()
            .lightLevel(luminosity)
            .density(density)
            .viscosity(viscosity)
            .temperature(temperature)
            .canHydrate(canHydrate)
            .supportsBoating(supportsBoating);
        return new ForgeFlowingFluidType(
            props,
            new ResourceLocation(stillTexture),
            new ResourceLocation(flowingTexture),
            overlayTexture == null ? null : new ResourceLocation(overlayTexture),
            tintColor
        );
    }

    public static ForgeFlowingFluid.Properties createFlowingFluidProperties(
        Supplier<FluidType> fluidTypeSupplier,
        Supplier<? extends FlowingFluid> sourceSupplier,
        Supplier<? extends FlowingFluid> flowingSupplier,
        Supplier<? extends Item> bucketSupplier,
        Supplier<? extends LiquidBlock> blockSupplier,
        int slopeFindDistance,
        int levelDecreasePerBlock,
        int tickRate,
        float explosionResistance,
        boolean canConvertToSource
    ) {
        ForgeFlowingFluid.Properties properties =
            new ForgeFlowingFluid.Properties(fluidTypeSupplier, sourceSupplier, flowingSupplier)
                .slopeFindDistance(slopeFindDistance)
                .levelDecreasePerBlock(levelDecreasePerBlock)
                .tickRate(tickRate)
                .explosionResistance(explosionResistance);
        if (bucketSupplier != null) {
            properties = properties.bucket(bucketSupplier);
        }
        if (blockSupplier != null) {
            properties = properties.block(blockSupplier);
        }
        // Forge 1.20.1 API does not expose a configurable canConvertToSource setter.
        // Keep parameter for cross-platform metadata parity; behavior follows default.
        return properties;
    }

    public static Fluid createSourceFluid(ForgeFlowingFluid.Properties properties) {
        return new ForgeFlowingFluid.Source(properties);
    }

    public static Fluid createFlowingFluid(ForgeFlowingFluid.Properties properties) {
        return new ForgeFlowingFluid.Flowing(properties);
    }

    public static Block createLiquidBlock(Supplier<? extends FlowingFluid> fluidSupplier) {
        return new LiquidBlock(fluidSupplier, BlockBehaviour.Properties.copy(Blocks.WATER));
    }

    public static Item createFluidBucket(Supplier<? extends Fluid> fluidSupplier) {
        return new net.minecraft.world.item.BucketItem(
            fluidSupplier,
            new Item.Properties()
                .stacksTo(1)
                .craftRemainder(Items.BUCKET)
        );
    }

    @SuppressWarnings("unchecked")
    public static EntityType<?> createEntityType(String fullId,
                                                 Class<?> entityClass,
                                                 String category,
                                                 float width,
                                                 float height,
                                                 int clientTrackingRange,
                                                 int updateInterval,
                                                 boolean fireImmune) {
        Class<? extends Entity> typedClass = (Class<? extends Entity>) entityClass;
        Constructor<? extends Entity> ctor;
        try {
            ctor = typedClass.getConstructor(EntityType.class, net.minecraft.world.level.Level.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Entity class must have constructor (EntityType, Level): " + typedClass.getName(), e);
        }
        EntityType.EntityFactory<Entity> factory = (type, level) -> {
            try {
                return ctor.newInstance(type, level);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to instantiate entity: " + typedClass.getName(), e);
            }
        };
        MobCategory mobCategory = switch (String.valueOf(category)) {
            case "monster" -> MobCategory.MONSTER;
            case "creature" -> MobCategory.CREATURE;
            case "ambient" -> MobCategory.AMBIENT;
            case "water_creature" -> MobCategory.WATER_CREATURE;
            case "water_ambient" -> MobCategory.WATER_AMBIENT;
            case "misc" -> MobCategory.MISC;
            default -> MobCategory.MISC;
        };
        EntityType.Builder<Entity> builder = EntityType.Builder.of(factory, mobCategory)
            .sized(width, height)
            .clientTrackingRange(clientTrackingRange)
            .updateInterval(updateInterval);
        if (fireImmune) {
            builder = builder.fireImmune();
        }
        return builder.build(fullId);
    }

    public static EntityType<?> createEntityTypeByKind(String fullId,
                                                       String entityKind,
                                                       String category,
                                                       float width,
                                                       float height,
                                                       int clientTrackingRange,
                                                       int updateInterval,
                                                       boolean fireImmune) {
        Class<?> entityClass = switch (String.valueOf(entityKind)) {
            case "scripted-projectile" -> ScriptedProjectileEntity.class;
            case "scripted-effect" -> ScriptedEffectEntity.class;
            default -> null;
        };
        if (entityClass == null) {
            throw new IllegalStateException("Unknown entity kind: " + entityKind);
        }
        return createEntityType(
            fullId,
            entityClass,
            category,
            width,
            height,
            clientTrackingRange,
            updateInterval,
            fireImmune
        );
    }

    @SuppressWarnings("unchecked")
    public static BlockEntityType<?> createScriptedBlockEntityType(String tileId,
                                                                   List<Block> blocks,
                                                                   Function<Block, String> blockIdResolver) {
        Block[] blockArray = blocks.toArray(new Block[0]);
        BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity>[] typeHolder =
            (BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity>[]) new BlockEntityType<?>[1];
        BlockEntityType<cn.li.forge1201.block.entity.ScriptedBlockEntity> beType =
            BlockEntityType.Builder.of(
                (pos, state) -> {
                    Block blockInst = state.getBlock();
                    String blockId = blockIdResolver.apply(blockInst);
                    if (blockId == null) {
                        blockId = tileId;
                    }
                    return new cn.li.forge1201.block.entity.ScriptedBlockEntity(typeHolder[0], pos, state, tileId, blockId);
                },
                blockArray
            ).build(null);
        typeHolder[0] = beType;
        cn.li.forge1201.block.entity.ScriptedBlockEntity.registerType(tileId, beType);
        return beType;
    }

    public static boolean isAirBlock(Block block, Block airBlock) {
        return block == null || block == airBlock;
    }

    public static Block getAirBlock() {
        return Blocks.AIR;
    }

    public static Block findBlock(String namespace, String path) {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
    }
}