package cn.li.mc1211.client.render.effect;

import cn.li.mc1211.entity.ScriptedBlockBodyEntity;
import cn.li.mc1211.entity.ScriptedEffectEntity;
import cn.li.mcbase.entity.ScriptedEntitySpecAccess;
import cn.li.mcbase.entity.spec.ScriptedEffectSpec;
import cn.li.mcbase.entity.spec.ScriptedMarkerSpec;
import cn.li.mcbase.entity.spec.ScriptedRaySpec;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed render access; all calls are direct so Loom can remap them safely. */
final class ScriptedRenderAccess {
    private ScriptedRenderAccess() {}
    static ScriptedEffectSpec getEffectSpec(Entity entity) { return ScriptedEntitySpecAccess.getScriptedEffectSpec(entity.getType()); }
    static ScriptedMarkerSpec getMarkerSpec(Entity entity) { return ScriptedEntitySpecAccess.getScriptedMarkerSpec(entity.getType()); }
    static ScriptedRaySpec getRaySpec(Entity entity) { return ScriptedEntitySpecAccess.getScriptedRaySpec(entity.getType()); }
    static int getAgeTicks(Entity entity) { return entity instanceof ScriptedEffectEntity scripted ? scripted.getAgeTicks() : 0; }
    static int getEffectiveLifeTicks(Entity entity, int defaultValue) { return entity instanceof ScriptedEffectEntity scripted ? scripted.getEffectiveLifeTicks() : defaultValue; }
    static String getSyncedBlockId(Entity entity) { return entity instanceof ScriptedBlockBodyEntity scripted ? scripted.getSyncedBlockId() : "minecraft:stone"; }
    static boolean isBehaviorHit(Entity entity) { return entity instanceof ScriptedBlockBodyEntity scripted && scripted.isBehaviorHit(); }
    static List<ArcDataView> getActiveArcs(Entity entity) {
        if (!(entity instanceof ScriptedEffectEntity scripted)) return Collections.emptyList();
        List<ScriptedEffectEntity.ArcData> arcs = scripted.getActiveArcs();
        if (arcs.isEmpty()) return Collections.emptyList();
        List<ArcDataView> out = new ArrayList<>(arcs.size());
        for (ScriptedEffectEntity.ArcData arc : arcs) out.add(new ArcDataView(arc.strands, arc.lifeTicks, arc.phase, arc.flickerSeed));
        return out;
    }
    static final class ArcDataView {
        final float[][][] strands; final int lifeTicks; final float phase; final float flickerSeed;
        private ArcDataView(float[][][] strands, int lifeTicks, float phase, float flickerSeed) { this.strands = strands; this.lifeTicks = lifeTicks; this.phase = phase; this.flickerSeed = flickerSeed; }
    }
}
