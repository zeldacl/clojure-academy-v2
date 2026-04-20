package cn.li.ac.content.spi;

import cn.li.mcmod.content.spi.ContentInitBootstrap;
import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * AC content bootstrap provider for mcmod content SPI.
 */
public final class AcContentInitBootstrap implements ContentInitBootstrap {

    @Override
    public String contentId() {
        return "ac";
    }

    @Override
    public void register() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.ac.core"));
    }
}