(ns cn.li.forge1201.datagen.advancement-provider
  "Generate advancement JSON from AC achievement metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.forge1201.datagen.resource-location :as rl]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [com.google.gson Gson GsonBuilder JsonElement]
           [java.nio.file Path]
           [java.util HashSet]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.data CachedOutput DataProvider PackOutput]
           [net.minecraft.resources ResourceLocation]))

(def ^:private ^Gson gson
  (-> (GsonBuilder.) (.setPrettyPrinting) (.disableHtmlEscaping) (.create)))

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

(defn- known-item-ids
  []
  (let [known (HashSet.)]
    (doseq [item-id ((requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-item-ids))]
      (let [registry-name ((requiring-resolve 'cn.li.mcmod.registry.metadata/get-item-registry-name) item-id)]
        (.add known (str "my_mod:" registry-name))))
    (doseq [block-id ((requiring-resolve 'cn.li.mcmod.registry.metadata/get-all-block-ids))]
      (let [registry-name ((requiring-resolve 'cn.li.mcmod.registry.metadata/get-block-registry-name) block-id)]
        (.add known (str "my_mod:" registry-name))))
    known))

(defn- item-exists?
  [^String id known]
  (or (.contains ^HashSet known id)
      (let [^ResourceLocation rl* (rl/parse-resource-location id)
            item (.get BuiltInRegistries/ITEM rl*)]
        (not= "minecraft:air" (str (.getKey BuiltInRegistries/ITEM item))))))

(defn- safe-item-id
  [id known]
  (let [sid (str id)]
    (if (item-exists? sid known)
      sid
      "minecraft:book")))

(defn- with-safe-items
  [criteria known]
  (mapv
    (fn [entry]
      (if (= :inventory-changed (:type entry))
        (update entry :items (fn [xs] (mapv #(safe-item-id % known) xs)))
        entry))
    criteria))

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
        criteria (with-safe-items (:criteria ach) known)
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
     "display" {"icon" {"item" (safe-item-id (:icon ach) known)}
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
        (let [known (known-item-ids)
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

