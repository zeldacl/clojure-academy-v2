(ns my-mod.forge1201.datagen.lang-provider
  "Forge 1.20.1 language (lang JSON) datagen.

  Generates:
  - assets/my_mod/lang/en_us.json
  - assets/my_mod/lang/zh_cn.json"
  (:require [my-mod.config.modid :as modid])
  (:import [net.minecraft.data DataProvider CachedOutput PackOutput]
           [java.nio.file Files Path]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent CompletableFuture]
           [com.google.gson GsonBuilder]))

(def ^:private gson
  (-> (GsonBuilder.)
      (.setPrettyPrinting)
      (.disableHtmlEscaping)
      (.create)))

(def ^:private en-us
  {"itemGroup.my_mod.items" "My Mod Items"
   ;; AcademyCraft TechUI WirelessPage (pg_wireless)
   "ac.gui.common.pg_wireless.not_connected" "Not Connected"})

(def ^:private zh-cn
  {"itemGroup.my_mod.items" "My Mod Items"
   "ac.gui.common.pg_wireless.not_connected" "未连接"})

(defn- write-json!
  [^Path path m]
  (let [parent (.getParent path)]
    (when parent
      (Files/createDirectories parent))
    (Files/writeString path (.toJson gson m) StandardCharsets/UTF_8)))

(defn create
  "Create Language DataProvider instance (factory signature: PackOutput -> DataProvider)."
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)
        base (.resolve ^Path out-root (str "assets/" modid/MOD-ID "/lang"))]
    (reify DataProvider
      (run [_ ^CachedOutput _cached]
        (CompletableFuture/runAsync
          (fn []
            (write-json! (.resolve base "en_us.json") en-us)
            (write-json! (.resolve base "zh_cn.json") zh-cn))))
      (getName [_]
        (str modid/MOD-ID " Lang Provider")))))

