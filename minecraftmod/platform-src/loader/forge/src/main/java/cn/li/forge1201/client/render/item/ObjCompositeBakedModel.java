package cn.li.forge1201.client.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Generic composite BakedModel for items that need separate 2D (GUI) and 3D
 * (world/hand) rendering.  The 2D flat model is used for GUI/ground/fixed
 * display contexts and the 3D OBJ model for first/third-person handheld.
 *
 * This mirrors the upstream pattern where BakedModelForTEISR
 * maps TransformType.GUI to the original 2D model and delegates 3D rendering
 * to a TEISRModel for all other contexts.
 *
 * Driven by item DSL {@code :item-model-3d-obj} metadata — no per-item
 * subclass needed.
 *
 * Critical: {@link #applyTransform} must return the selected sub-model.
 * ItemRenderer resolves energy overrides on this composite, then calls
 * applyTransform; if we keep returning {@code this} and {@link #getQuads}
 * always pulls the OBJ mesh, empty-energy GUI stacks (no override hit)
 * render as an invisible/wrong 3D model while charged stacks look fine
 * because overrides swap in a flat generated model.
 */
public class ObjCompositeBakedModel implements BakedModel {

    private final BakedModel guiModel;   // 2D item/generated with energy predicates
    private final BakedModel worldModel; // 3D forge:obj model

    public ObjCompositeBakedModel(BakedModel guiModel, BakedModel worldModel) {
        this.guiModel = guiModel;
        this.worldModel = worldModel;
    }

    private BakedModel selectModel(@Nullable ItemDisplayContext displayContext) {
        if (displayContext == null) {
            return worldModel;
        }
        return switch (displayContext) {
            case GUI, GROUND, FIXED, NONE -> guiModel;
            default -> worldModel;
        };
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                              @NotNull RandomSource rand) {
        // Fallback when no display context is available (breaking overlay, etc.).
        // Prefer the 2D GUI mesh — inventory/creative empty stacks must stay visible.
        return guiModel.getQuads(state, side, rand);
    }

    /**
     * Forge path: pick GUI vs handheld model, apply that model's transforms,
     * and return it so subsequent getQuads/render use the correct mesh.
     */
    @Override
    public @NotNull BakedModel applyTransform(@NotNull ItemDisplayContext transformType,
                                              @NotNull PoseStack poseStack,
                                              boolean applyLeftHandTransform) {
        BakedModel selected = selectModel(transformType);
        return selected.applyTransform(transformType, poseStack, applyLeftHandTransform);
    }

    @Override
    public boolean useAmbientOcclusion() {
        return worldModel.useAmbientOcclusion();
    }

    @Override
    public boolean isGui3d() {
        return false;
    }

    @Override
    public boolean usesBlockLight() {
        return false;
    }

    @Override
    public boolean isCustomRenderer() {
        return false;
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return guiModel.getParticleIcon();
    }

    @Override
    public @NotNull ItemTransforms getTransforms() {
        // applyTransform selects the real model; this is only a fallback.
        return guiModel.getTransforms();
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return guiModel.getOverrides();
    }
}
