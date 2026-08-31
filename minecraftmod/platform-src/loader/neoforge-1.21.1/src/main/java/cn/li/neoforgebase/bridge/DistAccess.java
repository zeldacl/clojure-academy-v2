package cn.li.neoforgebase.bridge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Direct NeoForge 1.21.1 side lookup; no reflective compatibility branch. */
public final class DistAccess {
    private DistAccess() {}
    public static Dist current() { return FMLEnvironment.dist; }
    public static boolean isClient() { return current() == Dist.CLIENT; }
    public static boolean isDedicatedServer() { return current() == Dist.DEDICATED_SERVER; }
}
