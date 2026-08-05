package cn.li.mc262.client;

import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** 26.2 client registration helpers (thin stubs until client registry APIs are wired). */
@OnlyIn(Dist.CLIENT)
public final class ClientHelper {
    private ClientHelper() {}

    @FunctionalInterface
    public interface RendererFactory {
        Object create(Object context);
    }

    @FunctionalInterface
    public interface ScreenFactory {
        Object create(Object menu, Object inventory, Object title);
    }

    public static void bindTextureForSetup(Identifier texture) {
        // TODO 26.2 texture bind
    }

    public static void registerBlockEntityRenderer(BlockEntityType<?> blockEntityType, RendererFactory factory) {
        // TODO 26.2 BER registration
    }

    public static void registerMenuScreen(MenuType<?> menuType, ScreenFactory factory) {
        // TODO 26.2 menu screen registration
    }
}
