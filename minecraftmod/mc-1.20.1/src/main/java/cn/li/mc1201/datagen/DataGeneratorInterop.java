package cn.li.mc1201.datagen;

import cn.li.mc1201.clj.ClojureInterop;

/**
 * Shared helper for delegating platform DataGenerator entry points to Clojure.
 */
public final class DataGeneratorInterop {

    private DataGeneratorInterop() {
    }

    public static void invoke(String errorPrefix, String ns, String fn, Object... args) {
        try {
            ClojureInterop.requireNamespace(ns);
            ClojureInterop.invoke(ns, fn, args);
        } catch (Exception e) {
            System.err.println(errorPrefix + e);
            e.printStackTrace();
        }
    }
}