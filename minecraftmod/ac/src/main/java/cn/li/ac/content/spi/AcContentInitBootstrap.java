package cn.li.ac.content.spi;

import cn.li.mcmod.content.spi.ClojureNamespaceBootstrapInvoker;
import cn.li.mcmod.content.spi.ContentInitBootstrap;

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
        ClojureNamespaceBootstrapInvoker.requireNamespace("cn.li.ac.core");
    }
}