(ns cn.li.fabric1201.datagen.worldgen-provider
  "Fabric worldgen DataGen provider. Bridges shared worldgen-provider-core
  to Fabric's DataProvider interface."
  (:require [cn.li.mc1201.datagen.worldgen-provider-core :as core]
            [cn.li.ac.config.gameplay :as gameplay-cfg])
  (:import [java.util.concurrent CompletableFuture]))

(defn create-provider
  "Create a Fabric DataProvider that generates worldgen JSON files."
  [output]
  (let [output-dir (.toFile (.resolvePath output))]
    (proxy [net.minecraft.data.DataProvider] []
      (getName [] "WorldGen Provider")
      (run [_cache]
        (core/generate-fabric-worldgen!
         output-dir
         {:gen-ores? (try (gameplay-cfg/gen-ores-enabled?) (catch Exception _ true))
          :gen-phase-liquid? (try (gameplay-cfg/gen-phase-liquid-enabled?) (catch Exception _ true))})
        (CompletableFuture/completedFuture nil)))))
