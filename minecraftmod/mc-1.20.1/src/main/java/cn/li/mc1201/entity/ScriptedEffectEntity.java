package cn.li.mc1201.entity;

import cn.li.mc1201.entity.hook.effect.ScriptedEffectHooks;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import cn.li.mc1201.entity.spec.ScriptedMarkerSpec;
import cn.li.mc1201.entity.spec.ScriptedRaySpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class ScriptedEffectEntity extends Entity {
    private static final int BALLISTIC_MAX_LIFE = 120;
    private UUID ownerUuid;
    private int age;
    private final List<ArcData> activeArcs = new ArrayList<>();
    private boolean ballisticStateInitialized;
    private double ballisticCurrentY;
    private double ballisticVelY;
    private double ballisticStartY;
    private double ballisticMaxY;
    private double ballisticInitVel = 0.92D;

    public ScriptedEffectEntity(EntityType<? extends ScriptedEffectEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    public static ScriptedEffectEntity create(Level level, Player owner, String entityRegistryName) {
        ScriptedEffectEntity entity = new ScriptedEffectEntity(
            ScriptedEntitySpecAccess.requireEntityType(entityRegistryName, ScriptedEffectEntity.class),
                level
        );
        entity.ownerUuid = owner.getUUID();
        entity.setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        return entity;
    }

    private ScriptedEffectSpec getSpec() {
        return ScriptedEntitySpecAccess.getScriptedEffectSpec(this.getType());
    }

    public ScriptedEffectSpec getEffectSpec() {
        return getSpec();
    }

    private static String normalizeHook(String hookName) {
        return hookName == null ? "" : hookName;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("owner")) {
            ownerUuid = tag.getUUID("owner");
        }
        age = tag.getInt("age");
        ballisticStateInitialized = tag.getBoolean("motionStateInitialized");
        ballisticCurrentY = tag.getDouble("motionCurrentY");
        ballisticVelY = tag.getDouble("motionVelY");
        ballisticStartY = tag.getDouble("motionStartY");
        ballisticMaxY = tag.getDouble("motionMaxY");
        ballisticInitVel = tag.contains("motionInitVel") ? tag.getDouble("motionInitVel") : 0.92D;
        activeArcs.clear();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) {
            tag.putUUID("owner", ownerUuid);
        }
        tag.putInt("age", age);
        tag.putBoolean("motionStateInitialized", ballisticStateInitialized);
        tag.putDouble("motionCurrentY", ballisticCurrentY);
        tag.putDouble("motionVelY", ballisticVelY);
        tag.putDouble("motionStartY", ballisticStartY);
        tag.putDouble("motionMaxY", ballisticMaxY);
        tag.putDouble("motionInitVel", ballisticInitVel);
    }

    private static double clamp01(double v) {
        if (v < 0.0D) {
            return 0.0D;
        }
        if (v > 1.0D) {
            return 1.0D;
        }
        return v;
    }

    private boolean tickVerticalBallisticMotion(ScriptedEffectSpec spec, Player owner) {
        if (owner == null) {
            this.ballisticStateInitialized = false;
            return false;
        }

        double gravity = spec.getDoubleParam("gravity", 0.06D);
        double initVel = spec.getDoubleParam("init-vel", 0.92D);

        if (!this.ballisticStateInitialized) {
            this.ballisticStateInitialized = true;
            this.ballisticStartY = owner.getY();
            this.ballisticCurrentY = this.ballisticStartY;
            this.ballisticInitVel = initVel;
            this.ballisticVelY = owner.getDeltaMovement().y + initVel;
            this.ballisticMaxY = this.ballisticCurrentY;
        }

        this.ballisticVelY -= gravity;
        this.ballisticCurrentY += this.ballisticVelY;
        this.ballisticMaxY = Math.max(this.ballisticMaxY, this.ballisticCurrentY);
        this.setPos(owner.getX(), this.ballisticCurrentY, owner.getZ());

        if ((this.ballisticCurrentY < owner.getY() && this.ballisticVelY < 0.0D) || this.tickCount > BALLISTIC_MAX_LIFE) {
            this.ballisticStateInitialized = false;
            this.discard();
            return true;
        }

        return false;
    }

    public boolean hasMotionProgress() {
        ScriptedEffectSpec spec = getSpec();
        if (spec == null) {
            return false;
        }
        return "vertical-ballistic".equals(normalizeHook(spec.getEffectHook())) && this.ballisticStateInitialized;
    }

    public double getMotionProgress() {
        if (!hasMotionProgress()) {
            return 0.0D;
        }

        Player owner = getOwnerPlayer();
        if (owner == null) {
            return 0.0D;
        }

        if (this.ballisticVelY > 0.0D) {
            return ((this.ballisticInitVel - this.ballisticVelY) / this.ballisticInitVel) * 0.5D;
        }

        return Math.min(1.0D, 0.5D + ((this.ballisticMaxY - this.ballisticCurrentY) / (this.ballisticMaxY - this.ballisticStartY)) * 0.5D);
    }

    @Override
    public void tick() {
        super.tick();
        ScriptedEffectSpec spec = getSpec();
        String effectHook = normalizeHook(spec == null ? null : spec.getEffectHook());

        Player owner = ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid);
        if ((spec == null || spec.isFollowOwner()) && owner != null) {
            setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        }

        boolean discardedByMotionProfile = false;
        if (spec != null && "vertical-ballistic".equals(effectHook)) {
            discardedByMotionProfile = tickVerticalBallisticMotion(spec, owner);
        }

        if (!discardedByMotionProfile && level().isClientSide() && level() instanceof ClientLevel clientLevel) {
            ScriptedEffectHooks.resolve(effectHook).onClientTick(this, clientLevel);
        }

        if (discardedByMotionProfile) {
            return;
        }

        age++;
        int lifeTicks;
        if (spec != null) {
            lifeTicks = spec.getLifeTicks();
        } else if (this instanceof ScriptedRayEntity rayEntity) {
            ScriptedRaySpec raySpec = rayEntity.getRaySpec();
            lifeTicks = raySpec == null ? 15 : raySpec.getLifeTicks();
        } else if (this instanceof ScriptedMarkerEntity markerEntity) {
            ScriptedMarkerSpec markerSpec = markerEntity.getMarkerSpec();
            lifeTicks = markerSpec == null ? 15 : markerSpec.getLifeTicks();
        } else {
            lifeTicks = 15;
        }
        if (age >= lifeTicks) {
            discard();
        }
    }

    public int getAgeTicks() {
        return age;
    }

    public List<ArcData> getActiveArcs() {
        return Collections.unmodifiableList(activeArcs);
    }

    public List<ArcData> mutableActiveArcs() {
        return activeArcs;
    }

    public RandomSource getEffectRandom() {
        return this.random;
    }

    public Player getOwnerPlayer() {
        return ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid);
    }

    public void setOwnerPlayer(Player owner) {
        ownerUuid = owner == null ? null : owner.getUUID();
    }

    public static final class ArcData {
        public final float[][][] strands;
        public int lifeTicks;
        public final float phase;
        public final float flickerSeed;

        public ArcData(float[][][] strands, int lifeTicks, float phase, float flickerSeed) {
            this.strands = strands;
            this.lifeTicks = lifeTicks;
            this.phase = phase;
            this.flickerSeed = flickerSeed;
        }
    }
}
