package cn.li.mc1201.platform.spi;

import cn.li.mc1201.clj.ClojureInterop;
import cn.li.mcmod.platform.spi.PlatformBootstrap;

/**
 * Shared bootstrap template for 1.20.1 platforms.
 *
 * Concrete loader providers only need to provide platform id and bootstrap
 * namespace, while initialization flow remains identical.
 */
public abstract class Platform1201BootstrapBase implements PlatformBootstrap {

    private static final String INIT_FN = "init-platform!";

    private final String platformId;
    private final String bootstrapNamespace;

    protected Platform1201BootstrapBase(String platformId, String bootstrapNamespace) {
        this.platformId = platformId;
        this.bootstrapNamespace = bootstrapNamespace;
    }

    @Override
    public final String platformId() {
        return platformId;
    }

    @Override
    public final void initialize() {
        ClojureInterop.requireNamespace(bootstrapNamespace);
        ClojureInterop.invoke(bootstrapNamespace, INIT_FN);
    }
}
