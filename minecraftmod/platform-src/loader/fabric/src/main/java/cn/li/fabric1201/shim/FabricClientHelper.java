package cn.li.fabric1201.shim;

import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerEntityRenderer(EntityType<?> entityType, EntityRendererProvider<?> provider) {
        EntityRendererRegistry.register((EntityType) entityType,
                (EntityRendererProvider) provider);
    }
}
