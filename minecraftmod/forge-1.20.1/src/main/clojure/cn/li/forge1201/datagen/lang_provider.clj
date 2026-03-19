(ns cn.li.forge1201.datagen.lang-provider
  "Language file data generator - generates translation files from metadata"
  (:require [my-mod.config.modid :as modid]
            [my-mod.registry.metadata :as registry-metadata])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [java.nio.file Path]
           [java.util.concurrent CompletableFuture]
           [com.google.gson Gson GsonBuilder JsonElement]))

;; 1. 显式类型暗示的变量
(def ^:private ^Gson gson
  (-> (GsonBuilder.) (.setPrettyPrinting) (.disableHtmlEscaping) (.create)))

;; TODO: Load translation data from ac metadata system instead of hardcoding
;; For now, keep minimal translations for creative tab
(def ^:private lang-data
  {"en_us.json" {"itemGroup.my_mod.items" "My Mod Items"}
   "zh_cn.json" {"itemGroup.my_mod.items" "My Mod Items"}})

(defn create
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)
        ^Path base (.resolve ^Path out-root (str "assets/" modid/MOD-ID "/lang"))]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [results (atom [])]
          ;; 遍历数据，直接在这里调用，避免跨函数编译问题
          (doseq [[file-name data] lang-data]
            (let [target-path (.resolve base ^String file-name)
                  ;; 手动将 Map 转为 JsonElement，确保类型匹配
                  json-tree   (.toJsonTree gson data)]
              ;; 使用完全限定名确保静态调用
              (swap! results conj (DataProvider/saveStable cached json-tree target-path))))

          (CompletableFuture/allOf (into-array CompletableFuture @results))))

      (getName [_] (str modid/MOD-ID " Lang Provider")))))