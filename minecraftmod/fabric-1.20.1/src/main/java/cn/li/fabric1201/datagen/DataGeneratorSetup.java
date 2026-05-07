package cn.li.fabric1201.datagen;

import clojure.lang.RT;
import clojure.lang.Var;
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
            Var var = RT.var("cn.li.fabric1201.datagen.setup", "register-data-generators!");
            var.invoke(fabricDataGenerator, null);
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator setup: " + e);
            e.printStackTrace();
        }
    }
}
