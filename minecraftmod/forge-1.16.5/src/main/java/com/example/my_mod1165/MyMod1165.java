package com.example.my_mod1165;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.minecraftforge.fml.common.Mod;

/**
 * Minimal Java bridge for @Mod annotation.
 * All logic implemented in my-mod.forge1165.mod Clojure namespace.
 */
@Mod(MyMod1165.MODID)
public class MyMod1165 {
    public static final String MODID = "my_mod";

    public MyMod1165() {
        // Load and instantiate the Clojure mod class
        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("my-mod.forge1165.mod"));
            
            // The Clojure namespace handles all initialization
            IFn initFn = Clojure.var("my-mod.forge1165.mod", "mod-init");
            initFn.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to load Clojure mod implementation:");
            t.printStackTrace();
        }
    }
}
