package cn.li.forge1201.platform.spi;

import cn.li.mc1201.platform.spi.Platform1201BootstrapBase;

/**
 * Forge 1.20.1 provider for platform bootstrap.
 */
public final class Forge1201PlatformBootstrap extends Platform1201BootstrapBase {

    private static final String PLATFORM_ID = "forge-1.20.1";
    private static final String BOOTSTRAP_NS = "cn.li.forge1201.platform.spi-bootstrap";

    public Forge1201PlatformBootstrap() {
        super(PLATFORM_ID, BOOTSTRAP_NS);
    }
}
