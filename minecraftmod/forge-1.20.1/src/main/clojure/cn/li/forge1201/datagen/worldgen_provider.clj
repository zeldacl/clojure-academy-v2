(ns cn.li.forge1201.datagen.worldgen-provider
  "Forge worldgen DataGen provider. Bridges shared worldgen-provider-core
  to Forge's DataProvider interface."
  (:require [cn.li.mc1201.datagen.worldgen-provider-core :as core]
            [cn.li.mcmod.config :as modid]
            [cn.li.mcmod.util.log :as log])
  (:import [com.google.gson GsonBuilder Gson JsonElement]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput]))

(def ^:private ^Gson gson
  (.. (GsonBuilder.) (setPrettyPrinting) (disableHtmlEscaping) (create)))

(defn create
  "Returns a DataProvider that writes worldgen JSON files via saveStable.
  Called by provider-factory/add-provider! with pack-output and exfile-helper."
  [^PackOutput pack-output _existing-file-helper]
  (let [out-root (.getOutputFolder pack-output)]
    (reify DataProvider
      (getName [_] "WorldGen Provider")
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [file-defs (core/build-worldgen-file-defs :platform :forge)
              writes (atom [])]
          (doseq [{:keys [path data]} file-defs]
            (let [full-path (concat ["data" (str modid/mod-id)] path)
                  rel-path (reduce #(.resolve ^Path %1 ^String %2) out-root full-path)
                  json-tree ^JsonElement (.toJsonTree gson data)]
              (swap! writes conj (DataProvider/saveStable cached json-tree rel-path))))
          (log/info "Generated Forge worldgen DataGen files" {:file-count (count file-defs)})
          (CompletableFuture/allOf (into-array CompletableFuture @writes)))))))
