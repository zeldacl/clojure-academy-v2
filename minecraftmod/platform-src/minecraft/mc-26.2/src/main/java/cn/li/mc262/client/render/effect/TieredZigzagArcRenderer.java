package cn.li.mc262.client.render.effect;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.2 stub for zigzag arc effect rendering. */
@OnlyIn(Dist.CLIENT)
public final class TieredZigzagArcRenderer {
    private TieredZigzagArcRenderer() {}

    public static <T extends Entity> void render(
            T entity,
            PoseStack poseStack,
            SubmitNodeCollector collector,
            int packedLight,
            float partialTick) {
        // TODO 26.2: port via SubmitNodeCollector
    }
}
