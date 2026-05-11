(ns cn.li.forge1201.datagen.lang-provider
  "Language file data generator - generates translation files from metadata"
  (:require [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.lang-provider-core :as lang-core]
            [cn.li.mc1201.datagen.gson-util :as gson-util])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [com.google.gson Gson]))

;; Gson instance for JSON serialization
(def ^:private ^Gson gson
  (gson-util/create-pretty-gson))

(defn create
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)
        ^Path base (.resolve ^Path out-root (str "assets/" modid/*mod-id* "/lang"))]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [results (atom [])]
          (doseq [[file-name data] (lang-core/merged-language-data)]
            (let [target-path (.resolve base ^String file-name)
                  json-tree   (.toJsonTree gson data)]
              (swap! results conj (DataProvider/saveStable cached json-tree target-path))))

          (CompletableFuture/allOf (into-array CompletableFuture @results))))

      (getName [_] (str modid/*mod-id* " Lang Provider")))))