(ns cn.li.forge1201.datagen.worldgen-provider
  "Forge worldgen DataGen provider. Bridges shared worldgen-provider-core
  to Forge's DataProvider interface."
  (:require [cn.li.mc1201.datagen.worldgen-provider-core :as core]
            [cn.li.ac.config.gameplay :as gameplay-cfg])
  (:import [java.util.concurrent CompletableFuture]
           [net.minecraft.data PackOutput DataProvider]
           [net.minecraftforge.common.data ExistingFileHelper]))

(defn create
  "Returns a DataProvider that writes worldgen JSON files.
  Called by provider-factory/add-provider! with pack-output and exfile-helper."
  [^PackOutput pack-output ^ExistingFileHelper _existing-file-helper]
  (let [output-dir (.toFile (.getOutputFolder pack-output))]
    (proxy [DataProvider] []
      (getName [] "WorldGen Provider")
      (run [_cache]
        (core/generate-forge-worldgen!
         output-dir
         {:gen-ores? (try (gameplay-cfg/gen-ores-enabled?) (catch Exception _ true))
          :gen-phase-liquid? (try (gameplay-cfg/gen-phase-liquid-enabled?) (catch Exception _ true))})
        (CompletableFuture/completedFuture nil)))))
