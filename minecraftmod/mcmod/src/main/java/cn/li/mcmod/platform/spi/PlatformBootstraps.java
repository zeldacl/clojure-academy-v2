package cn.li.mcmod.platform.spi;

import java.util.ServiceLoader;

/**
 * Entry point for platform bootstrap discovery.
 */
public final class PlatformBootstraps {

    private PlatformBootstraps() {
    }

    public static boolean initialize(String platformId) {
        ServiceLoader<PlatformBootstrap> loader = ServiceLoader.load(PlatformBootstrap.class);
        for (PlatformBootstrap bootstrap : loader) {
            if (bootstrap.platformId().equals(platformId)) {
                bootstrap.initialize();
                return true;
            }
        }
        return false;
    }
}