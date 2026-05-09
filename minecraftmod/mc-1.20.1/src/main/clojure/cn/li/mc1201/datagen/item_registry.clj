(ns cn.li.mc1201.datagen.item-registry
  "Shared item registry safety helpers for datagen.
  
  Provides utilities for checking item existence and resolving item references safely,
  particularly useful for advancement and loot table generation where invalid items
  cause data generation failures."
  (:import [java.util HashSet]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources ResourceLocation]
           [net.minecraft.world.item Items]))

(defn known-item-ids
  "Build set of known item IDs from mod metadata + Minecraft registry.
  
  Args:
    get-item-ids-fn: function returning collection of mod item IDs
    get-item-name-fn: function returning registry name for item ID
    get-block-ids-fn: function returning collection of mod block IDs
    get-block-name-fn: function returning registry name for block ID
    mod-id: mod namespace prefix (e.g. 'my_mod')
  
  Returns: java.util.HashSet of known item references
  
  Notes:
    - Caches both mod-defined and vanilla Minecraft items
    - Block registry names are also included for completeness"
  [get-item-ids-fn get-item-name-fn get-block-ids-fn get-block-name-fn mod-id]
  (let [known (HashSet.)]
    (doseq [item-id (get-item-ids-fn)]
      (let [registry-name (get-item-name-fn item-id)]
        (.add known (str mod-id ":" registry-name))))
    (doseq [block-id (get-block-ids-fn)]
      (let [registry-name (get-block-name-fn block-id)]
        (.add known (str mod-id ":" registry-name))))
    known))

(defn item-exists?
  "Check if item reference exists in registry or mod metadata.
  
  Args:
    id: item reference string (e.g. 'modid:item_name')
    known: HashSet from known-item-ids()
    parse-rl-fn: function to parse string to ResourceLocation
  
  Returns: boolean - true if item exists in either cache or registry"
  [^String id known parse-rl-fn]
  (or (.contains ^HashSet known id)
      (let [^ResourceLocation rl (parse-rl-fn id)
            item (.get BuiltInRegistries/ITEM rl)]
        (not= "minecraft:air" (str (.getKey BuiltInRegistries/ITEM item))))))

(defn safe-item-id
  "Resolve item ID, defaulting to minecraft:book if not found.
  
  Args:
    id: item reference string
    known: HashSet from known-item-ids()
    parse-rl-fn: function to parse string to ResourceLocation
  
  Returns: string - either the validated id or 'minecraft:book' as fallback"
  [id known parse-rl-fn]
  (let [sid (str id)]
    (if (item-exists? sid known parse-rl-fn)
      sid
      "minecraft:book")))

(defn with-safe-items
  "Apply safe-item-id to all items in criteria collection.
  
  Args:
    criteria: vector of criteria maps with optional :type and :items
    known: HashSet from known-item-ids()
    parse-rl-fn: function to parse strings to ResourceLocation
  
  Returns: vector with all item references validated
  
  Notes:
    - Only processes entries with :type :inventory-changed
    - Other entry types pass through unchanged"
  [criteria known parse-rl-fn]
  (mapv
    (fn [entry]
      (if (= :inventory-changed (:type entry))
        (update entry :items (fn [xs] (mapv #(safe-item-id % known parse-rl-fn) xs)))
        entry))
    criteria))
