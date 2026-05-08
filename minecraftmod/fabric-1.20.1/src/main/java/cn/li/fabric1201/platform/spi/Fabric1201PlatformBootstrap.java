package cn.li.fabric1201.platform.spi;

import cn.li.mc1201.platform.spi.Platform1201BootstrapBase;

/**
 * Fabric 1.20.1 provider for platform bootstrap.
 */
public final class Fabric1201PlatformBootstrap extends Platform1201BootstrapBase {

    private static final String PLATFORM_ID = "fabric-1.20.1";
    private static final String BOOTSTRAP_NS = "cn.li.fabric1201.platform.spi-bootstrap";

    public Fabric1201PlatformBootstrap() {
        super(PLATFORM_ID, BOOTSTRAP_NS);
    }
}
