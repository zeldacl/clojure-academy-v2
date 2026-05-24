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
    private static final int COIN_MAX_LIFE = 120;
    private UUID ownerUuid;
    private int age;
    private final List<ArcData> activeArcs = new ArrayList<>();
    private boolean coinStateInitialized;
    private double coinCurrentY;
    private double coinVelY;
    private double coinStartY;
    private double coinMaxY;
    private double coinInitVel = 0.92D;

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
        coinStateInitialized = tag.getBoolean("coinStateInitialized");
        coinCurrentY = tag.getDouble("coinCurrentY");
        coinVelY = tag.getDouble("coinVelY");
        coinStartY = tag.getDouble("coinStartY");
        coinMaxY = tag.getDouble("coinMaxY");
        coinInitVel = tag.contains("coinInitVel") ? tag.getDouble("coinInitVel") : 0.92D;
        activeArcs.clear();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) {
            tag.putUUID("owner", ownerUuid);
        }
        tag.putInt("age", age);
        tag.putBoolean("coinStateInitialized", coinStateInitialized);
        tag.putDouble("coinCurrentY", coinCurrentY);
        tag.putDouble("coinVelY", coinVelY);
        tag.putDouble("coinStartY", coinStartY);
        tag.putDouble("coinMaxY", coinMaxY);
        tag.putDouble("coinInitVel", coinInitVel);
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

    private boolean tickCoinThrowing(ScriptedEffectSpec spec, Player owner) {
        if (owner == null) {
            this.coinStateInitialized = false;
            return false;
        }

        double gravity = spec.getDoubleParam("gravity", 0.06D);
        double initVel = spec.getDoubleParam("init-vel", 0.92D);

        if (!this.coinStateInitialized) {
            this.coinStateInitialized = true;
            this.coinStartY = owner.getY();
            this.coinCurrentY = this.coinStartY;
            this.coinInitVel = initVel;
            this.coinVelY = owner.getDeltaMovement().y + initVel;
            this.coinMaxY = this.coinCurrentY;
        }

        this.coinVelY -= gravity;
        this.coinCurrentY += this.coinVelY;
        this.coinMaxY = Math.max(this.coinMaxY, this.coinCurrentY);
        this.setPos(owner.getX(), this.coinCurrentY, owner.getZ());

        if ((this.coinCurrentY < owner.getY() && this.coinVelY < 0.0D) || this.tickCount > COIN_MAX_LIFE) {
            this.coinStateInitialized = false;
            this.discard();
            return true;
        }

        return false;
    }

    public boolean hasCoinProgress() {
        ScriptedEffectSpec spec = getSpec();
        if (spec == null) {
            return false;
        }
        return "coin-throwing".equals(normalizeHook(spec.getEffectHook())) && this.coinStateInitialized;
    }

    public double getCoinProgress() {
        if (!hasCoinProgress()) {
            return 0.0D;
        }

        Player owner = getOwnerPlayer();
        if (owner == null) {
            return 0.0D;
        }

        if (this.coinVelY > 0.0D) {
            return ((this.coinInitVel - this.coinVelY) / this.coinInitVel) * 0.5D;
        }

        return Math.min(1.0D, 0.5D + ((this.coinMaxY - this.coinCurrentY) / (this.coinMaxY - this.coinStartY)) * 0.5D);
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

        boolean discardedByCoinPhysics = false;
        if (spec != null && "coin-throwing".equals(effectHook)) {
            discardedByCoinPhysics = tickCoinThrowing(spec, owner);
        }

        if (!discardedByCoinPhysics && level().isClientSide() && level() instanceof ClientLevel clientLevel) {
            ScriptedEffectHooks.resolve(effectHook).onClientTick(this, clientLevel);
        }

        if (discardedByCoinPhysics) {
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
