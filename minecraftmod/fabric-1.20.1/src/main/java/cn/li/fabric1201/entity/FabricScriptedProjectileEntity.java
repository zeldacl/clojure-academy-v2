package cn.li.fabric1201.entity;

import cn.li.mc1201.entity.ScriptedEntitySpecAccess;
import cn.li.mc1201.entity.spec.ScriptedProjectileSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;

public class FabricScriptedProjectileEntity extends ThrowableItemProjectile {

    private boolean anchored = false;
    private BlockPos anchorPos = null;
    private Direction anchorFace = Direction.UP;

    public FabricScriptedProjectileEntity(EntityType<? extends FabricScriptedProjectileEntity> type, Level level) {
        super(type, level);
    }

    private ScriptedProjectileSpec getSpec() {
        return ScriptedEntitySpecAccess.getScriptedProjectileSpec(this.getType());
    }

    @Override
    public Item getDefaultItem() {
        ScriptedProjectileSpec spec = getSpec();
        if (spec == null || spec.getDefaultItemId() == null || spec.getDefaultItemId().isEmpty()) {
            return Items.AIR;
        }
        ResourceLocation itemId = new ResourceLocation(spec.getDefaultItemId());
        return BuiltInRegistries.ITEM.get(itemId);
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("anchored")) {
            anchored = tag.getBoolean("anchored");
        }
        if (tag.contains("anchorPos")) {
            long pos = tag.getLong("anchorPos");
            anchorPos = BlockPos.of(pos);
        }
        if (tag.contains("anchorFace")) {
            anchorFace = Direction.values()[tag.getInt("anchorFace")];
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("anchored", anchored);
        if (anchorPos != null) {
            tag.putLong("anchorPos", anchorPos.asLong());
            tag.putInt("anchorFace", anchorFace.ordinal());
        }
    }

    @Override
    public void tick() {
        super.tick();
        
        ScriptedProjectileSpec spec = getSpec();
        if (spec == null) {
            discard();
            return;
        }

        if (anchored && anchorPos != null) {
            this.setPos(anchorPos.getX() + 0.5, anchorPos.getY(), anchorPos.getZ() + 0.5);
        }
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        
        ScriptedProjectileSpec spec = getSpec();
        if (spec == null) {
            return;
        }

        String hookName = spec.getOnHitBlockHook();
        if (hookName != null && !hookName.isEmpty() && !this.level().isClientSide()) {
            anchored = true;
            anchorPos = result.getBlockPos();
            anchorFace = result.getDirection();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        
        ScriptedProjectileSpec spec = getSpec();
        if (spec == null) {
            discard();
            return;
        }

        String hookName = spec.getOnHitEntityHook();
        if (hookName != null && !hookName.isEmpty()) {
            Entity entity = result.getEntity();
            double damage = spec.getDamage();
            if (damage > 0.0) {
                entity.hurt(this.damageSources().generic(), (float) damage);
            }
        }

        if (spec.isDropItemOnDiscard()) {
            ItemStack item = new ItemStack(getDefaultItem());
            if (!item.isEmpty()) {
                ItemEntity itemEntity = new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), item);
                this.level().addFreshEntity(itemEntity);
            }
        }

        discard();
    }

    public boolean isAnchored() {
        return anchored;
    }

    public void setAnchored(boolean anchored) {
        this.anchored = anchored;
    }

    public BlockPos getAnchorPos() {
        return anchorPos;
    }

    public void setAnchorPos(BlockPos pos) {
        this.anchorPos = pos;
    }

    public Direction getAnchorFace() {
        return anchorFace;
    }

    public void setAnchorFace(Direction face) {
        this.anchorFace = face;
    }
}

