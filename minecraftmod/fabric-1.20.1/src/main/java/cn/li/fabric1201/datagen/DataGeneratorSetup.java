package cn.li.fabric1201.datagen;

import cn.li.mc1201.clj.ClojureInterop;
import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Fabric 1.20.1 DataGenerator entry point.
 */
public class DataGeneratorSetup implements DataGeneratorEntrypoint {
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        fabricDataGenerator.createPack();

        try {
            String ns = "cn.li.fabric1201.datagen.setup";
            ClojureInterop.requireNamespace(ns);
            ClojureInterop.invoke(ns, "register-data-generators!", fabricDataGenerator, null);
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator setup: " + e);
            e.printStackTrace();
        }
    }
}
