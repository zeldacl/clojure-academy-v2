package cn.li.mc1201.clj;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Shared lightweight helpers for Java↔Clojure interop in platform shells.
 */
public final class ClojureInterop {

    private ClojureInterop() {
    }

    public static void requireNamespace(String namespace) {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read(namespace));
    }

    public static Object invoke(String namespace, String functionName, Object... args) {
        IFn fn = Clojure.var(namespace, functionName);
        return fn.applyTo(clojure.lang.RT.seq(args));
    }
}
