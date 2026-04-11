package cn.li.forge1201.client.render;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.forge1201.block.entity.ScriptedBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;

/**
 * Explicit Java {@link BlockEntityRenderer} for scripted tiles.
 * <p>
 * Clojure {@code reify} on {@code BlockEntityRenderer} can fail to match the
 * generic interface method at runtime (no crash, {@code render} simply never
 * runs). This class forwards to {@code cn.li.mcmod.client.render.tesr-api/render-tile-entity}.
 */
public final class ScriptedBlockEntityBer implements BlockEntityRenderer<ScriptedBlockEntity> {

    private static volatile IFn renderTileEntityFn;

    private static IFn renderFn() {
        IFn f = renderTileEntityFn;
        if (f == null) {
            synchronized (ScriptedBlockEntityBer.class) {
                f = renderTileEntityFn;
                if (f == null) {
                    IFn require = Clojure.var("clojure.core", "require");
                    require.invoke(Clojure.read("cn.li.mcmod.client.render.tesr-api"));
                    f = Clojure.var("cn.li.mcmod.client.render.tesr-api", "render-tile-entity");
                    renderTileEntityFn = f;
                }
            }
        }
        return f;
    }

    @Override
    public void render(ScriptedBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        try {
            renderFn().invoke(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        } catch (Throwable t) {
            System.err.println("[my_mod] ScriptedBlockEntityBer.render failed:");
            t.printStackTrace();
        }
    }
}
