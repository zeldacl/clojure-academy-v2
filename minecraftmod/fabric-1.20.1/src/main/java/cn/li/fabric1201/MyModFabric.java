package cn.li.fabric1201;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import cn.li.fabric1201.entity.FabricEntities;
import cn.li.fabric1201.entity.FabricScriptedEntityAccess;
import net.fabricmc.api.ModInitializer;

/**
 * Fabric mod entry point - bridges to Clojure implementation.
 */
public class MyModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        // Register entity types first
        FabricEntities.registerEntities();
        
        // Install shared accessor
        FabricScriptedEntityAccess.install();

        // Initialize Clojure module
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.fabric1201.mod"));

        IFn modInit = Clojure.var("cn.li.fabric1201.mod", "mod-init");
        modInit.invoke();
    }
}
