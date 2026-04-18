package cn.li.forge1201.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class ScriptedProjectileEntity extends ThrowableItemProjectile {

    private static final String HOOK_ANCHOR = "anchor";
    private static final String HOOK_DAMAGE_AND_DISCARD = "damage-and-discard";
    private static final String HOOK_DROP_WHEN_INVALID = "drop-when-invalid";
    private static final String HOOK_DISCARD_WHEN_HURT = "discard-when-hurt";

    private boolean anchored = false;
    private BlockPos anchorPos = null;
    private Direction anchorFace = Direction.UP;

    public ScriptedProjectileEntity(EntityType<? extends ScriptedProjectileEntity> type, Level level) {
        super(type, level);
    }

    private ScriptedProjectileSpec getSpec() {
        return ModEntities.getScriptedProjectileSpec(this.getType());
    }

    private static String normalizeHook(String hookName) {
        return hookName == null ? "" : hookName;
    }

    private Item resolveDefaultItem() {
        ScriptedProjectileSpec spec = getSpec();
        if (spec == null || spec.getDefaultItemId() == null || spec.getDefaultItemId().isEmpty()) {
            return Items.AIR;
        }
        ResourceLocation itemId;
        try {
            itemId = new ResourceLocation(spec.getDefaultItemId());
        } catch (Exception ignored) {
            return Items.AIR;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        return item == Items.AIR ? Items.AIR : item;
    }

    private void dropConfiguredItemAndDiscard() {
        if (!this.level().isClientSide) {
            ScriptedProjectileSpec spec = getSpec();
            boolean dropItem = spec == null || spec.isDropItemOnDiscard();
            if (dropItem) {
                Item item = resolveDefaultItem();
                if (item != Items.AIR) {
                    ItemEntity dropped = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), new ItemStack(item));
                    this.level().addFreshEntity(dropped);
                }
            }
        }
        this.discard();
    }

    private void anchorAt(HitResult result) {
        this.anchored = true;
        this.anchorPos = BlockPos.containing(result.getLocation());
        this.setPos(result.getLocation());
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        this.noPhysics = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.anchored) {
            return;
        }
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        this.noPhysics = true;

        if (this.level().isClientSide) {
            return;
        }
        ScriptedProjectileSpec spec = getSpec();
        if (!HOOK_DROP_WHEN_INVALID.equals(normalizeHook(spec == null ? null : spec.getOnAnchoredTickHook()))) {
            return;
        }
        if (this.anchorPos == null) {
            dropConfiguredItemAndDiscard();
            return;
        }
        BlockState state = this.level().getBlockState(this.anchorPos);
        if (state.isAir()) {
            dropConfiguredItemAndDiscard();
            return;
        }
        double pickupDistanceSqr = spec == null ? 2.25D : Math.max(0.0D, spec.getPickupDistanceSqr());
        Entity owner = this.getOwner();
        if (owner != null && owner.distanceToSqr(this) <= pickupDistanceSqr) {
            dropConfiguredItemAndDiscard();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        ScriptedProjectileSpec spec = getSpec();
        if (result.getType() == HitResult.Type.BLOCK
                && !this.anchored
                && HOOK_ANCHOR.equals(normalizeHook(spec == null ? null : spec.getOnHitBlockHook()))) {
            anchorAt(result);
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        ScriptedProjectileSpec spec = getSpec();
        if (!HOOK_DAMAGE_AND_DISCARD.equals(normalizeHook(spec == null ? null : spec.getOnHitEntityHook()))) {
            return;
        }
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        if (!this.level().isClientSide && target != owner) {
            float damage = (float) (spec == null ? 0.0D : Math.max(0.0D, spec.getDamage()));
            if (damage > 0.0F) {
                DamageSource source = this.damageSources().thrown(this, owner == null ? this : owner);
                target.hurt(source, damage);
            }
            dropConfiguredItemAndDiscard();
        }
    }

    @Override
    protected Item getDefaultItem() {
        return resolveDefaultItem();
    }

    @Override
    protected float getGravity() {
        if (this.anchored) {
            return 0.0F;
        }
        ScriptedProjectileSpec spec = getSpec();
        return (float) (spec == null ? 0.05D : Math.max(0.0D, spec.getGravity()));
    }

    @Override
    public boolean canBeCollidedWith() {
        return this.anchored;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        ScriptedProjectileSpec spec = getSpec();
        if (this.anchored
                && source.getEntity() != null
                && HOOK_DISCARD_WHEN_HURT.equals(normalizeHook(spec == null ? null : spec.getOnAnchoredHurtHook()))) {
            dropConfiguredItemAndDiscard();
            return true;
        }
        return super.hurt(source, amount);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Anchored", anchored);
        if (anchorPos != null) {
            tag.putLong("AnchorPos", anchorPos.asLong());
            tag.putString("AnchorFace", anchorFace.getName());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        anchored = tag.getBoolean("Anchored");
        if (tag.contains("AnchorPos")) {
            anchorPos = BlockPos.of(tag.getLong("AnchorPos"));
        }
        if (tag.contains("AnchorFace")) {
            anchorFace = Direction.byName(tag.getString("AnchorFace"));
            if (anchorFace == null) {
                anchorFace = Direction.UP;
            }
        }
    }
}
