package cn.li.fabric1201;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.fabric1201.client.FabricClientRenderSetup;
import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric client entry point - bridges to Clojure client initialization.
 */
public class MyModFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        FabricClientRenderSetup.registerClientHooks();
        try {
            IFn require = Clojure.var("clojure.core", "require");

            require.invoke(Clojure.read("cn.li.fabric1201.gui.init"));
            IFn guiClientInit = Clojure.var("cn.li.fabric1201.gui.init", "init-client!");
            guiClientInit.invoke();

            require.invoke(Clojure.read("cn.li.fabric1201.client.init"));
            IFn clientInit = Clojure.var("cn.li.fabric1201.client.init", "init-client");
            clientInit.invoke();
        } catch (Throwable t) {
            System.err.println("Failed to run Fabric client initialization (Clojure):");
            t.printStackTrace();
        }
    }
}
