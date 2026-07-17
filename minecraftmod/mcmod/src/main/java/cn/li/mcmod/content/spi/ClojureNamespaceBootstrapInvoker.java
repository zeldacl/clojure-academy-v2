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
        // Clojure 1.12's plain `require` takes no lock (only the private
        // serialized-require/requiring-resolve path does). Forge/Fabric can
        // dispatch mod lifecycle events on parallel workers, so concurrent
        // requires of overlapping namespace graphs can observe a partially
        // initialized namespace. Lock on the same monitor serialized-require
        // uses so all Java entry points cooperate with it.
        synchronized (clojure.lang.RT.REQUIRE_LOCK) {
            REQUIRE.invoke(Clojure.read(namespaceName));
        }
    }

    public static Object requireAndInvoke(String namespaceName, String functionName) {
        requireNamespace(namespaceName);
        return Clojure.var(namespaceName, functionName).invoke();
    }
}