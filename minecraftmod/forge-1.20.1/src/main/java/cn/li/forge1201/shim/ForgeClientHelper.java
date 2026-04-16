package cn.li.forge1201.shim;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderHandEvent;

@OnlyIn(Dist.CLIENT)
public final class ForgeClientHelper {
    private ForgeClientHelper() {
    }

    public interface RendererFactory {
        Object create();
    }

    public interface ScreenFactory {
        Object create(Object menu, Object playerInventory, Object title);
    }

    public static void bindTextureForSetup(ResourceLocation texture) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureManager textureManager = minecraft.getTextureManager();
        textureManager.bindForSetup(texture);
    }

    public static boolean renderTransformedMainHand(RenderHandEvent event,
                                                    float translateX,
                                                    float translateY,
                                                    float translateZ,
                                                    float rotateX,
                                                    float rotateY,
                                                    float rotateZ) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || event.getHand() != InteractionHand.MAIN_HAND) {
            return false;
        }

        if (!(event.getMultiBufferSource() instanceof MultiBufferSource.BufferSource bufferSource)) {
            return false;
        }

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(translateX, translateY, translateZ);
        if (rotateX != 0.0f) {
            poseStack.mulPose(Axis.XP.rotationDegrees(rotateX));
        }
        if (rotateY != 0.0f) {
            poseStack.mulPose(Axis.YP.rotationDegrees(rotateY));
        }
        if (rotateZ != 0.0f) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(rotateZ));
        }

        minecraft.gameRenderer.itemInHandRenderer.renderHandsWithItems(
            event.getPartialTick(),
            poseStack,
            bufferSource,
            player,
            event.getPackedLight());
        poseStack.popPose();
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerBlockEntityRenderer(BlockEntityType<?> blockEntityType, RendererFactory factory) {
        BlockEntityRenderers.register((BlockEntityType) blockEntityType, context -> (BlockEntityRenderer) factory.create());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void registerMenuScreen(MenuType<?> menuType, ScreenFactory factory) {
        MenuScreens.register((MenuType) menuType, new MenuScreens.ScreenConstructor() {
            @Override
            public Screen create(AbstractContainerMenu menu, Inventory playerInventory, Component title) {
                return (Screen) factory.create(menu, playerInventory, title);
            }
        });
    }
}