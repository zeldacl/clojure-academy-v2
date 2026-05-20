package cn.li.ac.content.spi;

import cn.li.mcmod.content.spi.ClojureNamespaceBootstrapInvoker;
import cn.li.mcmod.content.spi.ContentInitBootstrap;

/**
 * Runtime bootstrap provider for AC content registration.
 *
 * <p>The provider lives in the AC module so mcmod remains content-agnostic.
 * It loads the AC Clojure entry namespace, then explicitly invokes the
 * lifecycle hook registration entrypoint.</p>
 */
public final class AcContentInitBootstrap implements ContentInitBootstrap {

    @Override
    public String contentId() {
        return "ac";
    }

    @Override
    public void register() {
        ClojureNamespaceBootstrapInvoker.requireAndInvoke("cn.li.ac.core", "register-lifecycle-hooks!");
    }
}
