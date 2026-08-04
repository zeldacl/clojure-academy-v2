package cn.li.forge1201.client.render.item;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Forge BakedModel implementation for GUI-vs-world item composites.
 *
 * Business rules (which items, which model ids, when to install) live in
 * {@code cn.li.mc1201.client.render.obj-model-baking}. This class only
 * implements Forge {@link BakedModel#applyTransform} semantics matching
 * upstream ItemDeveloper / BakedModelForTEISR:
 * <ul>
 *   <li>GUI — flat generated model (energy empty/half/full overrides)</li>
 *   <li>Hand / ground / item-frame — 3D world mesh</li>
 * </ul>
 */
public class ObjCompositeBakedModel implements BakedModel {

    private final BakedModel guiModel;
    private final BakedModel worldModel;
    private final ItemOverrides overrides;

    public ObjCompositeBakedModel(BakedModel guiModel, BakedModel worldModel) {
        this.guiModel = guiModel;
        this.worldModel = worldModel;
        this.overrides = new EnergyAwareOverrides(guiModel, worldModel);
    }

    private BakedModel selectModel(@Nullable ItemDisplayContext displayContext) {
        if (displayContext == ItemDisplayContext.GUI) {
            return guiModel;
        }
        return worldModel;
    }

    @Override
    public @NotNull List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side,
                                              @NotNull RandomSource rand) {
        return guiModel.getQuads(state, side, rand);
    }

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
        return guiModel.getTransforms();
    }

    @Override
    public @NotNull ItemOverrides getOverrides() {
        return overrides;
    }

    private static final class EnergyAwareOverrides extends ItemOverrides {
        private final BakedModel guiModel;
        private final BakedModel worldModel;

        EnergyAwareOverrides(BakedModel guiModel, BakedModel worldModel) {
            this.guiModel = guiModel;
            this.worldModel = worldModel;
        }

        @Override
        public @NotNull BakedModel resolve(@NotNull BakedModel model,
                                           @NotNull ItemStack stack,
                                           @Nullable ClientLevel level,
                                           @Nullable LivingEntity entity,
                                           int seed) {
            BakedModel resolvedGui = guiModel.getOverrides()
                    .resolve(guiModel, stack, level, entity, seed);
            if (resolvedGui == null) {
                resolvedGui = guiModel;
            }
            if (resolvedGui == guiModel && model instanceof ObjCompositeBakedModel composite
                    && composite.guiModel == guiModel
                    && composite.worldModel == worldModel) {
                return model;
            }
            return new ObjCompositeBakedModel(resolvedGui, worldModel);
        }
    }
}
