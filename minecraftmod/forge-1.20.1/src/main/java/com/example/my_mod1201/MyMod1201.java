package com.example.my_mod1201;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.minecraftforge.fml.common.Mod;

/**
 * Minimal Java bridge for @Mod annotation.
 * All logic implemented in my-mod.forge1201.mod Clojure namespace.
 */
@Mod(MyMod1201.MODID)
public class MyMod1201 {
    public static final String MODID = "my_mod";

    public MyMod1201() {
        // Load and instantiate the Clojure mod class
        try {
            IFn require = Clojure.var("clojure.core", "require");
            require.invoke(Clojure.read("my-mod.forge1201.mod"));
            
            // The Clojure namespace handles all initialization
            IFn initFn = Clojure.var("my-mod.forge1201.mod", "mod-init");
            initFn.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to load Clojure mod implementation:");
            t.printStackTrace();
        }
    }
}
