package com.example.fabric1201.datagen;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;
import net.minecraft.data.DataGenerator;
import clojure.lang.RT;
import clojure.lang.Var;

/**
 * Fabric 1.20.1 DataGenerator Entry Point
 * 
 * Minimal wrapper implementing DataGeneratorEntrypoint interface.
 * All business logic delegated to Clojure (my-mod.fabric1201.datagen.setup).
 * 
 * Entry point: fabric-datagen (defined in fabric.mod.json)
 */
public class DataGeneratorSetup implements DataGeneratorEntrypoint {
    
    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // Get the main DataGenerator
        FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();
        DataGenerator generator = pack.getGenerator();
        
        // Delegate to Clojure implementation via Clojure runtime
        try {
            Var var = RT.var("my-mod.fabric1201.datagen.setup", "register-data-generators!");
            var.invoke(generator, null);  // null for exfile-helper (Fabric doesn't use it in same way)
        } catch (Exception e) {
            System.err.println("[my_mod] Error invoking Clojure DataGenerator setup: " + e);
            e.printStackTrace();
        }
    }
}
