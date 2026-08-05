(ns cn.li.mc262.datagen.item-registry
  "Shared item registry safety helpers for datagen (26.2 Identifier/getValue)."
  (:import [java.util HashSet]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.resources Identifier]
           [net.minecraft.world.item Items]))

(defn known-item-ids
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
  [^String id known parse-rl-fn]
  (or (.contains ^HashSet known id)
      (let [^Identifier rl (parse-rl-fn id)
            item (.getValue BuiltInRegistries/ITEM rl)]
        (and (some? item)
             (not= item Items/AIR)
             (not= "minecraft:air" (str (.getKey BuiltInRegistries/ITEM item)))))))

(defn safe-item-id
  [id known parse-rl-fn]
  (let [sid (str id)]
    (if (item-exists? sid known parse-rl-fn)
      sid
      "minecraft:book")))

(defn with-safe-items
  [criteria known parse-rl-fn]
  (mapv
   (fn [entry]
     (if (= :inventory-changed (:type entry))
       (update entry :items (fn [xs] (mapv #(safe-item-id % known parse-rl-fn) xs)))
       entry))
   criteria))
