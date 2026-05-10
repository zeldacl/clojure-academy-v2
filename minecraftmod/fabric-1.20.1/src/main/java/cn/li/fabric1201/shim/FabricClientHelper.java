package cn.li.fabric1201.shim;

import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.level.block.entity.BlockEntityType;

public final class FabricClientHelper {

    @FunctionalInterface
    public interface RendererFactory {
        Object create();
    }

    private FabricClientHelper() {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerBlockEntityRenderer(BlockEntityType<?> blockEntityType, RendererFactory factory) {
        BlockEntityRendererRegistry.register((BlockEntityType) blockEntityType, context -> (BlockEntityRenderer) factory.create());
    }
}
