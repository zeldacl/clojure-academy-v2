package cn.li.fabric1201.platform.spi;

import cn.li.mcmod.platform.spi.PlatformBootstrap;
import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Fabric 1.20.1 provider for platform bootstrap.
 */
public final class Fabric1201PlatformBootstrap implements PlatformBootstrap {

    private static final String PLATFORM_ID = "fabric-1.20.1";

    @Override
    public String platformId() {
        return PLATFORM_ID;
    }

    @Override
    public void initialize() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.fabric1201.platform.spi-bootstrap"));

        IFn init = Clojure.var("cn.li.fabric1201.platform.spi-bootstrap", "init-platform!");
        init.invoke();
    }
}
