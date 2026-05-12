package cn.li.mcmod.content.spi;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Encapsulates Clojure namespace activation for content/bootstrap providers.
 */
public final class ClojureNamespaceBootstrapInvoker {

    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");

    private ClojureNamespaceBootstrapInvoker() {
    }

    public static void requireNamespace(String namespaceName) {
        REQUIRE.invoke(Clojure.read(namespaceName));
    }
}