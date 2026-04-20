package cn.li.forge1201.platform.spi;

import cn.li.mcmod.platform.spi.PlatformBootstrap;
import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Forge 1.20.1 provider for platform bootstrap.
 */
public final class Forge1201PlatformBootstrap implements PlatformBootstrap {

    private static final String PLATFORM_ID = "forge-1.20.1";

    @Override
    public String platformId() {
        return PLATFORM_ID;
    }

    @Override
    public void initialize() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.forge1201.platform-impl-impl"));

        IFn init = Clojure.var("cn.li.forge1201.platform-impl-impl", "init-platform!");
        init.invoke();
    }
}
