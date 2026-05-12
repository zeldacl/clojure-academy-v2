package cn.li.mcmod.content.spi;

/**
 * Runtime bootstrap provider for AC content registration.
 *
 * <p>This class intentionally lives in mcmod so Forge runs that only compile
 * mcmod Java sources can still discover the provider via ServiceLoader.
 * The AC namespace is loaded dynamically through Clojure runtime, so there is
 * no Java compile-time dependency from mcmod to ac.</p>
 */
public final class AcContentInitBootstrapBridge implements ContentInitBootstrap {

    @Override
    public String contentId() {
        return "ac";
    }

    @Override
    public void register() {
        ClojureNamespaceBootstrapInvoker.requireNamespace("cn.li.ac.core");
    }
}
