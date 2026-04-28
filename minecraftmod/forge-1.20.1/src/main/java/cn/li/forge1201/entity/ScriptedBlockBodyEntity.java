package cn.li.forge1201.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class ScriptedBlockBodyEntity extends ScriptedProjectileEntity {
    private static final String NBT_BLOCK_ID = "BlockId";
    private static final String NBT_GRAVITY = "BlockBodyGravity";
    private static final String NBT_DAMAGE = "BlockBodyDamage";
    private static final String NBT_PLACE_WHEN_COLLIDE = "BlockBodyPlaceWhenCollide";

    private static final EntityDataAccessor<String> DATA_BLOCK_ID =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_GRAVITY =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_PLACE_WHEN_COLLIDE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.BOOLEAN);

    private boolean syncedFieldsInitialized;

    public ScriptedBlockBodyEntity(EntityType<? extends ScriptedProjectileEntity> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_BLOCK_ID, "minecraft:stone");
        this.entityData.define(DATA_GRAVITY, 0.05F);
        this.entityData.define(DATA_DAMAGE, 0.0F);
        this.entityData.define(DATA_PLACE_WHEN_COLLIDE, false);
    }

    @Override
    public void tick() {
        ensureSyncedFields();
        super.tick();
    }

    private void ensureSyncedFields() {
        if (syncedFieldsInitialized) {
            return;
        }
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        if (spec != null) {
            this.entityData.set(DATA_BLOCK_ID, normalizeBlockId(spec.getDefaultBlockId()));
            this.entityData.set(DATA_GRAVITY, (float) Math.max(0.0D, spec.getGravity()));
            this.entityData.set(DATA_DAMAGE, (float) Math.max(0.0D, spec.getDamage()));
            this.entityData.set(DATA_PLACE_WHEN_COLLIDE, spec.isPlaceWhenCollide());
        }
        syncedFieldsInitialized = true;
    }

    private static String normalizeBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "minecraft:stone";
        }
        return blockId;
    }

    public String getSyncedBlockId() {
        ensureSyncedFields();
        return normalizeBlockId(this.entityData.get(DATA_BLOCK_ID));
    }

    private float getSyncedGravity() {
        ensureSyncedFields();
        return Math.max(0.0F, this.entityData.get(DATA_GRAVITY));
    }

    private float getSyncedDamage() {
        ensureSyncedFields();
        return Math.max(0.0F, this.entityData.get(DATA_DAMAGE));
    }

    private boolean isSyncedPlaceWhenCollide() {
        ensureSyncedFields();
        return this.entityData.get(DATA_PLACE_WHEN_COLLIDE);
    }

    public ScriptedBlockBodySpec getBlockBodySpec() {
        return ModEntities.getScriptedBlockBodySpec(this.getType());
    }

    @Override
    protected float getGravity() {
        return getSyncedGravity();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (this.level().isClientSide) {
            return;
        }
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        if (target == owner) {
            return;
        }
        float damage = getSyncedDamage();
        if (damage > 0.0F) {
            DamageSource source = this.damageSources().thrown(this, owner == null ? this : owner);
            target.hurt(source, damage);
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (result.getType() == HitResult.Type.BLOCK) {
            if (isSyncedPlaceWhenCollide() && !this.level().isClientSide) {
                this.discard();
            }
        }
    }

    @Override
    protected Item getDefaultItem() {
        try {
            ResourceLocation blockId = new ResourceLocation(getSyncedBlockId());
            Item item = BuiltInRegistries.ITEM.get(blockId);
            return item == null ? Items.AIR : item;
        } catch (Exception ignored) {
            return Items.AIR;
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ensureSyncedFields();
        tag.putString(NBT_BLOCK_ID, getSyncedBlockId());
        tag.putFloat(NBT_GRAVITY, getSyncedGravity());
        tag.putFloat(NBT_DAMAGE, getSyncedDamage());
        tag.putBoolean(NBT_PLACE_WHEN_COLLIDE, isSyncedPlaceWhenCollide());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(NBT_BLOCK_ID)) {
            this.entityData.set(DATA_BLOCK_ID, normalizeBlockId(tag.getString(NBT_BLOCK_ID)));
        }
        if (tag.contains(NBT_GRAVITY)) {
            this.entityData.set(DATA_GRAVITY, Math.max(0.0F, tag.getFloat(NBT_GRAVITY)));
        }
        if (tag.contains(NBT_DAMAGE)) {
            this.entityData.set(DATA_DAMAGE, Math.max(0.0F, tag.getFloat(NBT_DAMAGE)));
        }
        if (tag.contains(NBT_PLACE_WHEN_COLLIDE)) {
            this.entityData.set(DATA_PLACE_WHEN_COLLIDE, tag.getBoolean(NBT_PLACE_WHEN_COLLIDE));
        }
        syncedFieldsInitialized = true;
    }
}
