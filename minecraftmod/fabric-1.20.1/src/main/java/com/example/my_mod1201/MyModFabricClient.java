package com.example.my_mod1201;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entry point - bridges to Clojure client initialization.
 *
 * This is referenced from fabric.mod.json entrypoints.client.
 */
public class MyModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        try {
            IFn require = Clojure.var("clojure.core", "require");

            // GUI client init (screen registration + client packets)
            require.invoke(Clojure.read("cn.li.fabric1201.gui.init"));
            IFn guiClientInit = Clojure.var("cn.li.fabric1201.gui.init", "init-client!");
            guiClientInit.invoke();

            // Renderers / client-only systems
            require.invoke(Clojure.read("cn.li.fabric1201.client.init"));
            IFn clientInit = Clojure.var("cn.li.fabric1201.client.init", "init-client");
            clientInit.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to run Fabric client initialization (Clojure):");
            t.printStackTrace();
        }
    }
}

