package cn.li.fabric1201.client.render;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Explicit Java BER dispatcher for Fabric scripted block entities.
 */
public final class BlockEntityRendererImpl implements BlockEntityRenderer<BlockEntity> {

    private static volatile IFn renderTileEntityFn;

    private static IFn renderFn() {
        IFn fn = renderTileEntityFn;
        if (fn == null) {
            synchronized (BlockEntityRendererImpl.class) {
                fn = renderTileEntityFn;
                if (fn == null) {
                    IFn require = Clojure.var("clojure.core", "require");
                    require.invoke(Clojure.read("cn.li.mcmod.client.render.tesr-api"));
                    fn = Clojure.var("cn.li.mcmod.client.render.tesr-api", "render-tile-entity");
                    renderTileEntityFn = fn;
                }
            }
        }
        return fn;
    }

    @Override
    public void render(BlockEntity blockEntity,
                       float partialTick,
                       PoseStack poseStack,
                       MultiBufferSource bufferSource,
                       int packedLight,
                       int packedOverlay) {
        try {
            renderFn().invoke(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
        } catch (Throwable t) {
            System.err.println("[my_mod] Fabric BlockEntityRendererImpl.render failed:");
            t.printStackTrace();
        }
    }
}
