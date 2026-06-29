(ns cn.li.fabric1201.datagen.worldgen-provider
  "Fabric worldgen DataGen provider. Bridges shared worldgen-provider-core
  to Fabric's DataProvider interface."
  (:require [cn.li.mc1201.datagen.worldgen-provider-core :as core]
            [cn.li.ac.config.gameplay :as gameplay-cfg]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson GsonBuilder Gson JsonElement]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput]))

(def ^:private ^Gson gson
  (.. (GsonBuilder.) (setPrettyPrinting) (disableHtmlEscaping) (create)))

(defn create-provider
  "Create a Fabric DataProvider that generates worldgen JSON files via saveStable."
  [^PackOutput output]
  (let [out-root (.getOutputFolder output)]
    (reify DataProvider
      (getName [_] "WorldGen Provider")
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [gen-ores? (try (gameplay-cfg/gen-ores-enabled?) (catch Exception _ true))
              gen-phase? (try (gameplay-cfg/gen-phase-liquid-enabled?) (catch Exception _ true))
              file-defs (core/build-worldgen-file-defs
                         :gen-ores? gen-ores?
                         :gen-phase-liquid? gen-phase?
                         :platform :fabric)
              writes (atom [])]
          (doseq [{:keys [path data]} file-defs]
            (let [full-path (concat ["data" "my_mod"] path)
                  rel-path (reduce #(.resolve ^Path %1 ^String %2) out-root full-path)
                  json-tree ^JsonElement (.toJsonTree gson data)]
              (swap! writes conj (DataProvider/saveStable cached json-tree rel-path))))
          (log/info "Generated Fabric worldgen DataGen files"
                    {:ores gen-ores? :phase-liquid gen-phase?
                     :file-count (count file-defs)})
          (CompletableFuture/allOf (into-array CompletableFuture @writes)))))))
