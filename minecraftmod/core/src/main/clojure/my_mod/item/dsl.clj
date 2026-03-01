(ns my-mod.item.dsl
  "Item DSL - Declarative item definition using Clojure macros"
  (:require [my-mod.util.log :as log]))

;; Item Registry - stores all defined items
(defonce item-registry (atom {}))

;; Item specifications
(defrecord ItemSpec [id max-stack-size durability creative-tab rarity
                     food-properties tool-properties armor-properties
                     on-use on-right-click on-finish-using
                     enchantability properties])

;; Creative tabs (version-agnostic)
(def creative-tabs
  {:building-blocks :building-blocks
   :decorations :decorations
   :redstone :redstone
   :transportation :transportation
   :misc :misc
   :food :food
   :tools :tools
   :combat :combat
   :brewing :brewing
   :materials :materials})

;; Rarity levels
(def rarity-levels
  {:common :common
   :uncommon :uncommon
   :rare :rare
   :epic :epic})

;; Tool tiers
(def tool-tiers
  {:wood 0
   :stone 1
   :iron 2
   :diamond 3
   :netherite 4
   :gold 0})

;; Armor materials
(def armor-materials
  {:leather :leather
   :chainmail :chainmail
   :iron :iron
   :gold :gold
   :diamond :diamond
   :netherite :netherite})

;; Default values
(def default-max-stack-size 64)
(def default-creative-tab :misc)

;; Create item specification
(defn create-item-spec
  "Create an item specification from options"
  [item-id options]
  (map->ItemSpec
    {:id item-id
     :max-stack-size (or (:max-stack-size options) default-max-stack-size)
     :durability (:durability options)
     :creative-tab (or (:creative-tab options) default-creative-tab)
     :rarity (or (:rarity options) :common)
     :food-properties (:food-properties options)
     :tool-properties (:tool-properties options)
     :armor-properties (:armor-properties options)
     :on-use (or (:on-use options) (fn [_] nil))
     :on-right-click (or (:on-right-click options) (fn [_] nil))
     :on-finish-using (or (:on-finish-using options) (fn [_] nil))
     :enchantability (or (:enchantability options) 0)
     :properties (or (:properties options) {})}))

;; Validate item specification
(defn validate-item-spec [item-spec]
  (when-not (:id item-spec)
    (throw (ex-info "Item must have an :id" {:spec item-spec})))
  (when-not (string? (:id item-spec))
    (throw (ex-info "Item :id must be a string" {:id (:id item-spec)})))
  (when (and (:durability item-spec) (> (:max-stack-size item-spec) 1))
    (throw (ex-info "Durable items must have max-stack-size of 1"
                    {:id (:id item-spec)
                     :durability (:durability item-spec)
                     :max-stack-size (:max-stack-size item-spec)})))
  true)

;; Register item in registry
(defn register-item! [item-spec]
  (validate-item-spec item-spec)
  (log/info "Registering item:" (:id item-spec))
  (swap! item-registry assoc (:id item-spec) item-spec)
  item-spec)

;; Get item from registry
(defn get-item [item-id]
  (get @item-registry item-id))

;; List all registered items
(defn list-items []
  (keys @item-registry))

;; Main macro: defitem
(defmacro defitem
  "Define an item with declarative syntax
  
  Example:
  (defitem my-item
    :max-stack-size 16
    :creative-tab :tools
    :durability 500
    :on-use (fn [data] (println \"Used!\")))"
  [item-name & options]
  (if (map? item-name)
    (let [options-map item-name
          item-id (:id options-map)]
      (when-not (string? item-id)
        (throw (ex-info "Map-form defitem requires string :id"
                        {:form options-map})))
      `(register-item!
         (create-item-spec ~item-id ~(dissoc options-map :id))))
    (let [item-id (name item-name)
          options-map (if (and (= 1 (count options)) (map? (first options)))
                        (first options)
                        (apply hash-map options))]
      `(def ~item-name
         (register-item!
           (create-item-spec ~item-id ~options-map))))))

;; Helper: food properties
(defn food-properties
  "Create food properties map"
  [nutrition saturation & {:keys [meat fast-to-eat always-edible]
                           :or {meat false fast-to-eat false always-edible false}}]
  {:nutrition nutrition
   :saturation saturation
   :meat meat
   :fast-to-eat fast-to-eat
   :always-edible always-edible})

;; Helper: tool properties
(defn tool-properties
  "Create tool properties map"
  [tier attack-damage attack-speed]
  {:tier tier
   :attack-damage attack-damage
   :attack-speed attack-speed})

;; Helper: armor properties
(defn armor-properties
  "Create armor properties map"
  [material slot protection toughness]
  {:material material
   :slot slot
   :protection protection
   :toughness toughness})

;; Preset: basic item
(defn basic-item-preset
  "Create a basic item preset"
  []
  {:max-stack-size 64
   :creative-tab :misc
   :rarity :common})

;; Preset: tool item
(defn tool-preset
  "Create a tool item preset"
  [tier durability attack-damage attack-speed]
  {:max-stack-size 1
   :creative-tab :tools
   :durability durability
   :tool-properties (tool-properties tier attack-damage attack-speed)
   :enchantability (case tier
                     :wood 15
                     :stone 5
                     :iron 14
                     :diamond 10
                     :netherite 15
                     :gold 22
                     10)})

;; Preset: food item
(defn food-preset
  "Create a food item preset"
  [nutrition saturation]
  {:max-stack-size 64
   :creative-tab :food
   :food-properties (food-properties nutrition saturation)})

;; Preset: rare item
(defn rare-item-preset
  "Create a rare item preset"
  [rarity]
  {:max-stack-size 1
   :creative-tab :misc
   :rarity rarity})

;; Helper: merge presets
(defn merge-presets
  "Merge multiple presets with options"
  [& preset-and-options]
  (apply merge preset-and-options))

;; Multimethod for version-specific item creation
(def ^:dynamic *forge-version* nil)

(defmulti create-platform-item
  "Create a version-specific item instance"
  (fn [_item-spec] *forge-version*))

(defmethod create-platform-item :default [item-spec]
  (throw (ex-info "No item implementation for version"
                  {:version *forge-version*
                   :item-id (:id item-spec)})))

;; Item interaction handlers
(defn handle-use
  "Handle item use"
  [item-spec event-data]
  (when-let [handler (:on-use item-spec)]
    (handler event-data)))

(defn handle-right-click
  "Handle right-click with item"
  [item-spec event-data]
  (when-let [handler (:on-right-click item-spec)]
    (handler event-data)))

(defn handle-finish-using
  "Handle finishing using item (e.g., eating food)"
  [item-spec event-data]
  (when-let [handler (:on-finish-using item-spec)]
    (handler event-data)))

;; Get item properties for platform creation
(defn get-item-properties
  "Get item properties map for platform-specific creation"
  [item-spec]
  {:max-stack-size (:max-stack-size item-spec)
   :durability (:durability item-spec)
   :creative-tab (:creative-tab item-spec)
   :rarity (:rarity item-spec)
   :food-properties (:food-properties item-spec)
   :tool-properties (:tool-properties item-spec)
   :armor-properties (:armor-properties item-spec)
   :enchantability (:enchantability item-spec)})
