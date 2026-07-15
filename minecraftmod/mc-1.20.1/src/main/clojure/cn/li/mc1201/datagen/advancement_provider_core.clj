(ns cn.li.mc1201.datagen.advancement-provider-core
  "Shared advancement datagen JSON assembly helpers."
  (:require [clojure.string :as str]
            [cn.li.mcmod.config :as modid]
            [cn.li.mc1201.datagen.resource-location :as rl]
            [cn.li.mc1201.datagen.item-registry :as item-registry]))

(defn- item-predicate
  [item-id]
  {"items" [item-id]})

(defn criterion-json
  [entry]
  (case (:type entry)
    :inventory-changed
    {"trigger" "minecraft:inventory_changed"
     "conditions" {"items" (mapv item-predicate (:items entry))}}

    :custom
    {"trigger" (str modid/mod-id ":custom")
     "conditions" {"criterion_id" (:criterion-id entry)}}

    {"trigger" "minecraft:impossible"}))

(defn normalize-id
  [id]
  (str/replace (str id) "." "/"))

(defn ach-path
  [id]
  (str "data/" modid/mod-id "/advancements/achievements/" (normalize-id id) ".json"))

(defn root-path
  [tab-id]
  (str "data/" modid/mod-id "/advancements/achievements/" (name tab-id) "/root.json"))

(defn tab-root-id
  [tab-id]
  (str modid/mod-id ":achievements/" (name tab-id) "/root"))

(defn ach-id
  [id]
  (str modid/mod-id ":achievements/" (normalize-id id)))

(defn root-json
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

(defn ach-json
  [ach root-rl known]
  (let [id (:id ach)
        criteria (item-registry/with-safe-items (:criteria ach) known rl/parse-resource-location)
        criteria-map (into {}
                           (map-indexed
                             (fn [idx c]
                               [(str "c" idx) (criterion-json c)])
                             criteria))
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
