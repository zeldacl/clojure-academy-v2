package cn.li.ac.content.spi;

import cn.li.mcmod.content.spi.ClojureNamespaceBootstrapInvoker;
import cn.li.mcmod.content.spi.ContentInitBootstrap;

/**
 * Runtime bootstrap provider for AC content registration.
 *
 * <p>The provider lives in the AC module so mcmod remains content-agnostic.
 * It loads the AC Clojure entry namespace, which registers lifecycle hooks into
 * mcmod when required.</p>
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
