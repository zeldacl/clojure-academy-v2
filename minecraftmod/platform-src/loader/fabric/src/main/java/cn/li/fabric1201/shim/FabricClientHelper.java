package cn.li.fabric1201.shim;

import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.material.Fluid;

/**
 * Fabric-only client helpers (entity renderer registration + fluid layers).
 * Shared BER / texture helpers live in {@link cn.li.mc1201.client.ClientHelper}.
 */
public final class FabricClientHelper {

    private FabricClientHelper() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerEntityRenderer(EntityType<?> entityType, EntityRendererProvider<?> provider) {
        EntityRendererRegistry.register((EntityType) entityType, (EntityRendererProvider) provider);
    }

    public static void setFluidRenderLayerTranslucent(Fluid sourceFluid, Fluid flowingFluid) {
        BlockRenderLayerMap.INSTANCE.putFluids(RenderType.translucent(), sourceFluid, flowingFluid);
    }
}
