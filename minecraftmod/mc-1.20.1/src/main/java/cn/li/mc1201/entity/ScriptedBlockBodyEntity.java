package cn.li.mc1201.entity;

import cn.li.mc1201.entity.spec.ScriptedBlockBodySpec;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
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

    /** Matches original EntitySilbarn: zero gravity for the first 50 ticks, then settles to the configured value. */
    private static final String HOOK_SILBARN = "silbarn";
    private static final int SILBARN_GRAVITY_DELAY_TICKS = 50;
    private static final int SILBARN_DESPAWN_DELAY_TICKS = 10;
    private static final String SOUND_SILBARN_HEAVY = "my_mod:entity.silbarn_heavy";
    private static final String SOUND_SILBARN_LIGHT = "my_mod:entity.silbarn_light";

    private int silbarnDespawnCountdown = -1;

    private static final EntityDataAccessor<String> DATA_BLOCK_ID =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_GRAVITY =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_DAMAGE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_PLACE_WHEN_COLLIDE =
            SynchedEntityData.defineId(ScriptedBlockBodyEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DATA_SILBARN_HIT =
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
        this.entityData.define(DATA_SILBARN_HIT, false);
    }

    @Override
    public void tick() {
        ensureSyncedFields();
        super.tick();
        if (silbarnDespawnCountdown > 0) {
            silbarnDespawnCountdown--;
            if (silbarnDespawnCountdown == 0 && !this.level().isClientSide) {
                this.discard();
            }
        }
    }

    private boolean isSilbarn() {
        ScriptedBlockBodySpec spec = getBlockBodySpec();
        return spec != null && HOOK_SILBARN.equals(spec.getHookId());
    }

    private void markSilbarnHit(boolean heavy) {
        if (silbarnDespawnCountdown >= 0 || this.level().isClientSide) {
            return;
        }
        silbarnDespawnCountdown = SILBARN_DESPAWN_DELAY_TICKS;
        this.entityData.set(DATA_SILBARN_HIT, true);
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(
            new ResourceLocation(heavy ? SOUND_SILBARN_HEAVY : SOUND_SILBARN_LIGHT));
        if (sound != null) {
            this.playSound(sound, 0.5F, 1.0F);
        }
        spawnSilbarnFragParticles();
    }

    private void spawnSilbarnFragParticles() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        var fragTypeRaw = BuiltInRegistries.PARTICLE_TYPE.get(new ResourceLocation("my_mod", "silbarn_frag"));
        if (!(fragTypeRaw instanceof SimpleParticleType fragType)) {
            return;
        }
        // Matches original EntitySilbarn#spawnEffects: 18-27 fragments in random sphere distribution
        int n = 18 + this.random.nextInt(10);
        for (int i = 0; i < n; i++) {
            double vel = 0.08 + this.random.nextDouble() * 0.10;
            double vsq = vel * vel;
            double vx = this.random.nextDouble() * vel;
            double vxsq = vx * vx;
            double vy = this.random.nextDouble() * Math.sqrt(Math.max(0.0, vsq - vxsq));
            double vz = Math.sqrt(Math.max(0.0, vsq - vxsq - vy * vy));
            vx *= this.random.nextBoolean() ? 1 : -1;
            vy *= this.random.nextBoolean() ? 1 : -1;
            vz *= this.random.nextBoolean() ? 1 : -1;
            vy += 0.2; // upward bias matches original
            // count=0: single particle with exact velocity (dx/dy/dz used as vx/vy/vz)
            serverLevel.sendParticles(fragType, this.getX(), this.getY(), this.getZ(),
                    0, vx, vy, vz, 0.0);
        }
    }

    /** Mirrors original EntitySilbarn's client-synced hit flag, used by the client renderer to hide the model. */
    public boolean isSilbarnHit() {
        return this.entityData.get(DATA_SILBARN_HIT);
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
        return ScriptedEntitySpecAccess.getScriptedBlockBodySpec(this.getType());
    }

    @Override
    protected float getGravity() {
        if (isSilbarn() && this.tickCount < SILBARN_GRAVITY_DELAY_TICKS) {
            return 0.0F;
        }
        return getSyncedGravity();
    }

    @Override
    public boolean isPickable() {
        return isSilbarn() || super.isPickable();
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
        if (isSilbarn()) {
            boolean heavy = target instanceof ScriptedBlockBodyEntity other && other.isSilbarn();
            markSilbarnHit(heavy);
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
            if (isSilbarn()) {
                markSilbarnHit(false);
            } else if (isSyncedPlaceWhenCollide() && !this.level().isClientSide) {
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
        if (isSilbarn()) {
            // Matches original EntitySilbarn#readEntityFromNBT: never survives a save/reload.
            this.discard();
        }
    }
}
