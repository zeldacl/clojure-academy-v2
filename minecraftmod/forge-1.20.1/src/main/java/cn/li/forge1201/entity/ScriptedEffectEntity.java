package cn.li.forge1201.entity;

import cn.li.forge1201.entity.effect.hooks.ScriptedEffectHooks;
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
    private UUID ownerUuid;
    private int age;
    private final List<ArcData> activeArcs = new ArrayList<>();

    public ScriptedEffectEntity(EntityType<? extends ScriptedEffectEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    public static ScriptedEffectEntity create(Level level, Player owner, String entityRegistryName) {
        ScriptedEffectEntity entity = new ScriptedEffectEntity(
                ModEntities.requireEntityType(entityRegistryName, ScriptedEffectEntity.class),
                level
        );
        entity.ownerUuid = owner.getUUID();
        entity.setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        return entity;
    }

    private ScriptedEffectSpec getSpec() {
        return ModEntities.getScriptedEffectSpec(this.getType());
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
        activeArcs.clear();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) {
            tag.putUUID("owner", ownerUuid);
        }
        tag.putInt("age", age);
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

        if (level().isClientSide() && level() instanceof ClientLevel clientLevel) {
            ScriptedEffectHooks.resolve(effectHook).onClientTick(this, clientLevel);
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
