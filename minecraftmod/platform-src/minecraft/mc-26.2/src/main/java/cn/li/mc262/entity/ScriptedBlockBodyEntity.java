package cn.li.mc262.entity;

import cn.li.mcbase.entity.spec.ScriptedBlockBodySpec;
import cn.li.mcver.ResourceLocations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class ScriptedBlockBodyEntity extends ScriptedProjectileEntity {
    private static final EntityDataAccessor<String> DATA_BLOCK_ID =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_GRAVITY =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_PLACE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_BEHAVIOR_HIT =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.BOOLEAN);

    private boolean initialized;
    private int despawnCountdown = -1;

    public ScriptedBlockBodyEntity(EntityType<? extends ScriptedProjectileEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_BLOCK_ID, "minecraft:stone");
        builder.define(DATA_GRAVITY, 0.05F);
        builder.define(DATA_DAMAGE, 0.0F);
        builder.define(DATA_PLACE, false);
        builder.define(DATA_BEHAVIOR_HIT, false);
    }

    public ScriptedBlockBodySpec getBlockBodySpec() {
        return ScriptedEntitySpecAccess.getScriptedBlockBodySpec(getType());
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null) {
            entityData.set(DATA_BLOCK_ID, normalizeBlockId(spec.getDefaultBlockId()));
            entityData.set(DATA_GRAVITY, (float) Math.max(0.0D, spec.getGravity()));
            entityData.set(DATA_DAMAGE, (float) Math.max(0.0D, spec.getDamage()));
            entityData.set(DATA_PLACE, spec.isPlaceWhenCollide());
        }
        initialized = true;
    }

    private static String normalizeBlockId(String blockId) {
        return blockId == null || blockId.isBlank() ? "minecraft:stone" : blockId;
    }

    public String getSyncedBlockId() {
        ensureInitialized();
        return normalizeBlockId(entityData.get(DATA_BLOCK_ID));
    }

    public void setSyncedBlockId(String blockId) {
        ensureInitialized();
        entityData.set(DATA_BLOCK_ID, normalizeBlockId(blockId));
    }

    public void setPlaceWhenCollide(boolean placeWhenCollide) {
        ensureInitialized();
        entityData.set(DATA_PLACE, placeWhenCollide);
    }

    public boolean isBehaviorHit() {
        return entityData.get(DATA_BEHAVIOR_HIT);
    }

    public void forceBehaviorHit() {
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null && "impact-detonation".equals(spec.getBehaviorId())) {
            markBehaviorHit();
        }
    }

    private void markBehaviorHit() {
        if (!level().isClientSide() && despawnCountdown < 0) {
            entityData.set(DATA_BEHAVIOR_HIT, true);
            despawnCountdown = 10;
        }
    }

    @Override
    public void tick() {
        ensureInitialized();
        super.tick();
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null && spec.getDrag() < 1.0D) {
            setDeltaMovement(getDeltaMovement().scale(spec.getDrag()));
        }
        if (despawnCountdown > 0 && --despawnCountdown == 0 && !level().isClientSide()) {
            discard();
        }
    }

    @Override
    protected double getDefaultGravity() {
        ensureInitialized();
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null && "impact-detonation".equals(spec.getBehaviorId()) && tickCount < 50) {
            return 0.0D;
        }
        return entityData.get(DATA_GRAVITY);
    }

    @Override
    public boolean isPickable() {
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        return (spec != null && "impact-detonation".equals(spec.getBehaviorId())) || super.isPickable();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null && "impact-detonation".equals(spec.getBehaviorId())
                && result.getEntity() != getOwner()) {
            markBehaviorHit();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (result.getType() != HitResult.Type.BLOCK || level().isClientSide()) {
            return;
        }
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null && "impact-detonation".equals(spec.getBehaviorId())) {
            markBehaviorHit();
        } else if (entityData.get(DATA_PLACE)) {
            placeBlock((BlockHitResult) result);
        }
    }

    private void placeBlock(BlockHitResult hit) {
        BlockState state = resolveBlockState();
        if (state == null) {
            discard();
            return;
        }
        BlockPos primary = hit.getBlockPos();
        BlockPos adjacent = primary.relative(hit.getDirection());
        if (level().getBlockState(primary).canBeReplaced()) {
            level().setBlock(primary, state, 3);
        } else if (level().getBlockState(adjacent).canBeReplaced()) {
            level().setBlock(adjacent, state, 3);
        }
        discard();
    }

    private BlockState resolveBlockState() {
        try {
            Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocations.parse(getSyncedBlockId()));
            return block == null ? null : block.defaultBlockState();
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    protected Item getDefaultItem() {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(ResourceLocations.parse(getSyncedBlockId()));
            return item == null ? Items.AIR : item;
        } catch (IllegalArgumentException ignored) {
            return Items.AIR;
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        ensureInitialized();
        output.putString("BlockId", getSyncedBlockId());
        output.putFloat("BlockBodyGravity", entityData.get(DATA_GRAVITY));
        output.putFloat("BlockBodyDamage", entityData.get(DATA_DAMAGE));
        output.putBoolean("BlockBodyPlaceWhenCollide", entityData.get(DATA_PLACE));
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        entityData.set(DATA_BLOCK_ID, normalizeBlockId(input.getStringOr("BlockId", "minecraft:stone")));
        entityData.set(DATA_GRAVITY, Math.max(0.0F, input.getFloatOr("BlockBodyGravity", 0.05F)));
        entityData.set(DATA_DAMAGE, Math.max(0.0F, input.getFloatOr("BlockBodyDamage", 0.0F)));
        entityData.set(DATA_PLACE, input.getBooleanOr("BlockBodyPlaceWhenCollide", false));
        initialized = true;
    }
}
