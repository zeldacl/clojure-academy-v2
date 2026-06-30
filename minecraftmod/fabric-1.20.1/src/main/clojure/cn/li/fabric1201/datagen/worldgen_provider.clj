(ns cn.li.fabric1201.datagen.worldgen-provider
  "Fabric worldgen DataGen provider. Bridges shared worldgen-provider-core
  to Fabric's DataProvider interface."
  (:require [cn.li.mc1201.datagen.worldgen-provider-core :as core]
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
        (let [file-defs (core/build-worldgen-file-defs :platform :fabric)
              writes (atom [])]
          (doseq [{:keys [path data]} file-defs]
            (let [full-path (concat ["data" "my_mod"] path)
                  rel-path (reduce #(.resolve ^Path %1 ^String %2) out-root full-path)
                  json-tree ^JsonElement (.toJsonTree gson data)]
              (swap! writes conj (DataProvider/saveStable cached json-tree rel-path))))
          (log/info "Generated Fabric worldgen DataGen files" {:file-count (count file-defs)})
          (CompletableFuture/allOf (into-array CompletableFuture @writes)))))))
