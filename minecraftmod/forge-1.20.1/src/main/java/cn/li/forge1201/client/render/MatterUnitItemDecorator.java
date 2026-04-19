package cn.li.forge1201.client.render;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.IItemDecorator;

public final class MatterUnitItemDecorator implements IItemDecorator {
    public static final MatterUnitItemDecorator INSTANCE = new MatterUnitItemDecorator();

    private static final Keyword KEY_ENABLED = Keyword.intern("enabled?");
    private static final Keyword KEY_PHASE_LIQUID = Keyword.intern("phase-liquid?");
    private static final Keyword KEY_ALPHA = Keyword.intern("alpha");
    private static final Keyword KEY_SCROLL_OFFSET = Keyword.intern("scroll-offset");
    private static final Keyword KEY_BASE_TEXTURE = Keyword.intern("base-texture");
    private static final Keyword KEY_LIQUID_TEXTURE = Keyword.intern("liquid-texture");
    private static final Keyword KEY_MASK_TEXTURE = Keyword.intern("mask-texture");

    private final IFn requireFn;
    private final IFn overlayDataFn;

    private MatterUnitItemDecorator() {
        this.requireFn = Clojure.var("clojure.core", "require");
        this.requireFn.invoke(Clojure.read("cn.li.ac.item.special-items"));
        this.overlayDataFn = Clojure.var("cn.li.ac.item.special-items", "matter-unit-overlay-data");
    }

    @Override
    public boolean render(GuiGraphics guiGraphics, Font font, ItemStack stack, int xOffset, int yOffset) {
        try {
            Object result = overlayDataFn.invoke(stack.getDamageValue(), System.currentTimeMillis());
            if (!(result instanceof IPersistentMap data)) {
                return false;
            }
            if (!asBool(data.valAt(KEY_ENABLED))) {
                return false;
            }

            ResourceLocation base = parseResource(data.valAt(KEY_BASE_TEXTURE));
            ResourceLocation liquid = parseResource(data.valAt(KEY_LIQUID_TEXTURE));
            ResourceLocation mask = parseResource(data.valAt(KEY_MASK_TEXTURE));
            boolean phaseLiquid = asBool(data.valAt(KEY_PHASE_LIQUID));
            float alpha = asFloat(data.valAt(KEY_ALPHA), 0.0F);
            float scroll = asFloat(data.valAt(KEY_SCROLL_OFFSET), 0.0F);

            if (base != null) {
                guiGraphics.blit(base, xOffset, yOffset, 0, 0, 16, 16, 16, 16);
            }

            if (phaseLiquid && liquid != null && alpha > 0.01F) {
                float u = scroll * 16.0F;
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, Math.max(0.0F, Math.min(1.0F, alpha)));
                guiGraphics.blit(liquid, xOffset, yOffset, u, 0.0F, 16, 16, 16, 16);
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }

            if (mask != null) {
                guiGraphics.blit(mask, xOffset, yOffset, 0, 0, 16, 16, 16, 16);
            }
        } catch (Throwable ignored) {
            // Keep inventory rendering robust.
        }
        return false;
    }

    private static boolean asBool(Object value) {
        return value instanceof Boolean b && b;
    }

    private static float asFloat(Object value, float fallback) {
        return value instanceof Number n ? n.floatValue() : fallback;
    }

    private static ResourceLocation parseResource(Object value) {
        if (value == null) {
            return null;
        }
        return ResourceLocation.tryParse(String.valueOf(value));
    }
}
