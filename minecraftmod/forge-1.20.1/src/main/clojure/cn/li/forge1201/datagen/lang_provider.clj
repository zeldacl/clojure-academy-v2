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
        (lang-core/save-language-files!
         (lang-core/merged-language-entries)
         (fn [file-name data]
           (let [target-path (.resolve base ^String file-name)
                 json-tree (.toJsonTree gson data)]
             (DataProvider/saveStable cached json-tree target-path)))))

      (getName [_] (str modid/*mod-id* " Lang Provider")))))