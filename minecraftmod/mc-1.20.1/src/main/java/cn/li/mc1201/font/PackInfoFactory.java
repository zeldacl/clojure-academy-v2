package cn.li.mc1201.font;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.flag.FeatureFlagSet;

/**
 * Constructs {@link Pack.Info} with the dev-mapping constructor signature (Loom-remapped).
 */
public final class PackInfoFactory {
    private PackInfoFactory() {
    }

    public static Pack.Info createDefaultClientPackInfo() {
        return new Pack.Info(
                Component.literal("Injected system TrueType font for smooth text rendering."),
                15,
                FeatureFlagSet.of());
    }
}
