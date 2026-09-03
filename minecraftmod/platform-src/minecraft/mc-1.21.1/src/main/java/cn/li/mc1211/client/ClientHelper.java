package cn.li.mc1211.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;

/**
 * Vanilla client registration helpers shared by Forge/Fabric adapters.
 * Fluid render-layer registration is loader-specific (Forge ItemBlockRenderTypes
 * vs Fabric BlockRenderLayerMap).
 */
public final class ClientHelper {
    private ClientHelper() {
    }

    public interface RendererFactory {
        Object create();
    }

    public static void bindTextureForSetup(ResourceLocation texture) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureManager textureManager = minecraft.getTextureManager();
        textureManager.bindForSetup(texture);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerBlockEntityRenderer(BlockEntityType<?> blockEntityType, RendererFactory factory) {
        BlockEntityRenderers.register((BlockEntityType) blockEntityType, context -> (BlockEntityRenderer) factory.create());
    }

}
