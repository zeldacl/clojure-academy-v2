(ns cn.li.forge1201.datagen.advancement-provider
  "Generate advancement JSON from AC achievement metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.item-registry :as item-registry]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [com.google.gson Gson JsonElement]
           [java.nio.file Path]
           [java.util HashSet]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.data CachedOutput DataProvider PackOutput]
           [net.minecraft.resources ResourceLocation]))

(def ^:private ^Gson gson
  (gson-util/create-pretty-gson))

(defn- item-predicate
  [item-id]
  {"items" [item-id]})

(defn- criterion-json
  [entry]
  (case (:type entry)
    :inventory-changed
    {"trigger" "minecraft:inventory_changed"
     "conditions" {"items" (mapv item-predicate (:items entry))}}

    :custom
    {"trigger" "my_mod:custom"
     "conditions" {"criterion_id" (:criterion-id entry)}}

    {"trigger" "minecraft:impossible"}))

(defn- normalize-id
  [id]
  (str/replace (str id) "." "/"))

(defn- ach-path
  [id]
  (str "data/" modid/*mod-id* "/advancements/achievements/" (normalize-id id) ".json"))

(defn- root-path
  [tab-id]
  (str "data/" modid/*mod-id* "/advancements/achievements/" (name tab-id) "/root.json"))

(defn- tab-root-id
  [tab-id]
  (str "my_mod:achievements/" (name tab-id) "/root"))

(defn- ach-id
  [id]
  (str "my_mod:achievements/" (normalize-id id)))

(defn- make-known-item-ids
  "Build known items set using shared helper"
  []
  (item-registry/known-item-ids
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-item-ids)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-item-registry-name)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-block-ids)
    (requiring-resolve 'cn.li.mcmod.registry.metadata/get-block-registry-name)
    "my_mod"))

(defn- root-json
  [{:keys [id background]}]
  {"display" {"icon" {"item" "minecraft:nether_star"}
              "title" {"translate" (str "advancement.my_mod.tab." (name id))}
              "description" {"translate" (str "advancement.my_mod.tab." (name id) ".description")}
              "background" background
              "frame" "task"
              "show_toast" false
              "announce_to_chat" false
              "hidden" false}
   "criteria" {"root" {"trigger" "minecraft:tick"}}
   "requirements" [["root"]]})

(defn- ach-json
  [ach root-rl known]
  (let [id (:id ach)
        criteria (item-registry/with-safe-items (:criteria ach) known rl/parse-resource-location)
        criteria-map (into {}
                           (map-indexed
                             (fn [idx c]
                               [(str "c" idx) (criterion-json c)]))
                           criteria)
        criteria-keys (vec (keys criteria-map))
        parent-rl (if-let [parent (:parent ach)]
                    (ach-id parent)
                    root-rl)]
    {"parent" parent-rl
     "display" {"icon" {"item" (item-registry/safe-item-id (:icon ach) known rl/parse-resource-location)}
                "title" {"translate" (str "advancement.my_mod." id)}
                "description" {"translate" (str "advancement.my_mod." id ".description")}
                "frame" (name (or (:frame ach) :task))
                "show_toast" true
                "announce_to_chat" false
                "hidden" (boolean (:hidden? ach))}
     "criteria" criteria-map
     "requirements" (mapv vector criteria-keys)}))

(defn create
  [^PackOutput pack-output _exfile-helper]
  (let [out-root (.getOutputFolder pack-output)]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [known (make-known-item-ids)
              tabs (datagen-metadata/get-achievement-tabs)
              all-achievements (datagen-metadata/get-achievements)
              writes (atom [])]
          (doseq [tab tabs]
            (let [root-rel (root-path (:id tab))
                  root-json* (root-json tab)
                  root-tree (.toJsonTree gson root-json*)
                  root-path* (.resolve ^Path out-root ^String root-rel)]
              (swap! writes conj (DataProvider/saveStable cached ^JsonElement root-tree ^Path root-path*))))
          (doseq [ach all-achievements]
            (let [root-rl (tab-root-id (:tab ach))
                  rel-path (ach-path (:id ach))
                  json-map (ach-json ach root-rl known)
                  json-tree (.toJsonTree gson json-map)
                  target-path (.resolve ^Path out-root ^String rel-path)]
              (swap! writes conj (DataProvider/saveStable cached ^JsonElement json-tree ^Path target-path))))
          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str modid/*mod-id* " Advancement Provider")))))

