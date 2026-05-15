(ns cn.li.fabric1201.datagen.advancement-provider
  "Fabric 1.20.1 advancement datagen provider.

  Emits advancement JSON from shared achievement metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.gson-util :as gson-util]
            [cn.li.mc1201.datagen.item-registry :as item-registry]
            [cn.li.mcmod.datagen.metadata :as datagen-metadata])
  (:import [com.google.gson JsonElement]
           [java.util.concurrent CompletableFuture]
           [net.minecraft.data CachedOutput DataProvider PackOutput$PathProvider PackOutput$Target]
           [net.minecraft.resources ResourceLocation]))

(def ^:private gson
  (gson-util/create-pretty-gson))

(defn- normalize-id
  [id]
  (-> (cond
        (keyword? id) (name id)
        (nil? id) ""
        :else (str id))
      (str/replace #"^:+" "")
    (str/replace #"\s+" "-")
      (str/replace #"[^a-zA-Z0-9/._-]" "-")
      (str/lower-case)))

(defn- ach-id
  [id]
  (str modid/*mod-id* ":achievements/" (normalize-id id)))

(defn- metadata-fn
  [sym]
  (let [v (requiring-resolve sym)]
    (cond
      (and v (bound? v))
      (fn [& args] (apply v args))

      :else
      (do
        (require 'cn.li.mcmod.protocol.metadata :reload)
        (let [v2 (requiring-resolve sym)]
          (if (and v2 (bound? v2))
            (fn [& args] (apply v2 args))
            (fn [& _] nil)))))))

(defn- make-known-item-ids
  []
  (item-registry/known-item-ids
    (metadata-fn 'cn.li.mcmod.protocol.metadata/get-all-item-ids)
    (metadata-fn 'cn.li.mcmod.protocol.metadata/get-item-registry-name)
    (metadata-fn 'cn.li.mcmod.protocol.metadata/get-all-block-ids)
    (metadata-fn 'cn.li.mcmod.protocol.metadata/get-block-registry-name)
    "my_mod"))

(defn- item-predicate
  [item-id]
  {"trigger" "minecraft:inventory_changed"
   "conditions" {"items" [{"items" [item-id]}]}})

(defn- root-json
  [{:keys [id background]}]
  (let [nid (normalize-id id)]
    {"display" {"icon" {"item" "minecraft:book"}
                "title" {"translate" (str "advancement.my_mod." nid)}
                "description" {"translate" (str "advancement.my_mod." nid ".description")}
              "background" (str background)
              "frame" "task"
              "show_toast" false
              "announce_to_chat" false
              "hidden" false}
     "criteria" {"tick" {"trigger" "minecraft:tick"}}
     "requirements" [["tick"]]}))

(defn- ach-json
  [ach root-rl known]
  (let [id (:id ach)
  nid (normalize-id id)
        criteria (item-registry/with-safe-items (:criteria ach) known rl/parse-resource-location)
        criteria-map (into {}
                           (map-indexed
                             (fn [idx c]
                               (let [name (str "c" idx)
                                 first-item (item-registry/safe-item-id
                                        (or (first (:items c)) "minecraft:book")
                                        known
                                        rl/parse-resource-location)]
                               [name (item-predicate first-item)]))
                             criteria))
        reqs (mapv vector (keys criteria-map))
        parent (:parent ach)
        parent-rl (if parent
                    (ach-id parent)
                    root-rl)]
    {"parent" parent-rl
     "display" {"icon" {"item" (item-registry/safe-item-id (:icon ach) known rl/parse-resource-location)}
                "title" {"translate" (str "advancement.my_mod." nid)}
                "description" {"translate" (str "advancement.my_mod." nid ".description")}
                "frame" (name (or (:frame ach) :task))
                "show_toast" true
                "announce_to_chat" false
                "hidden" false}
     "criteria" criteria-map
     "requirements" reqs}))

(defn create-provider
  [output]
  (let [^String mod-id (str modid/*mod-id*)
  path-provider (.createPathProvider output PackOutput$Target/DATA_PACK "advancements")]
    (reify DataProvider
      (^CompletableFuture run [_ ^CachedOutput cached]
        (let [known (make-known-item-ids)
              tabs (datagen-metadata/get-achievement-tabs)
              all-achievements (datagen-metadata/get-achievements)
              writes (atom [])]
          (doseq [tab tabs]
            (let [root-tree (.toJsonTree gson (root-json tab))
                  root-id (ResourceLocation. mod-id (str "achievements/" (normalize-id (:id tab)) "_root"))
                  root-path (.json ^PackOutput$PathProvider path-provider root-id)]
              (swap! writes conj (DataProvider/saveStable cached ^JsonElement root-tree ^java.nio.file.Path root-path))))

          (doseq [ach all-achievements]
            (let [root-tab (or (:tab ach)
                               (:category ach)
                               (some-> (first tabs) :id))
                  root-rl (if root-tab
                            (ach-id (str (normalize-id root-tab) "_root"))
                            "my_mod:achievements/root")
                  json-tree (.toJsonTree gson (ach-json ach root-rl known))
                    target-id (ResourceLocation. mod-id (str "achievements/" (normalize-id (:id ach))))
                    target-path (.json ^PackOutput$PathProvider path-provider target-id)]
                  (swap! writes conj (DataProvider/saveStable cached ^JsonElement json-tree ^java.nio.file.Path target-path))))

          (CompletableFuture/allOf (into-array CompletableFuture @writes))))
      (getName [_] (str modid/*mod-id* " Advancement Provider")))))
