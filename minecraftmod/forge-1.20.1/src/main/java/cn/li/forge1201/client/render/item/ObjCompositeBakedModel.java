package cn.li.forge1201.client.render.item;

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
        return worldModel.getQuads(state, side, rand);
    }

    @SuppressWarnings("deprecation")
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                              @NotNull RandomSource rand,
                                              @NotNull ItemDisplayContext displayContext) {
        return selectModel(displayContext).getQuads(state, side, rand);
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
        return worldModel.usesBlockLight();
    }

    @Override
    public boolean isCustomRenderer() {
        return worldModel.isCustomRenderer();
    }

    @Override
    public @NotNull TextureAtlasSprite getParticleIcon() {
        return worldModel.getParticleIcon();
    }

    @Override
    public @NotNull ItemTransforms getTransforms() {
        return worldModel.getTransforms();
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return guiModel.getOverrides();
    }
}
