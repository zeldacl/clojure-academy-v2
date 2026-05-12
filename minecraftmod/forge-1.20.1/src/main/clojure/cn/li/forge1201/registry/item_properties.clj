(ns cn.li.forge1201.registry.item-properties
  "Forge-specific item property assembly extracted from the mod entrypoint."
  (:import [net.minecraft.world.food FoodProperties$Builder]
           [net.minecraft.world.item Item Item$Properties Rarity]
           [cn.li.mc1201.item NbtBarItem ScriptedItem]))

(defn- rarity->forge-rarity
  ^Rarity
  [rarity-kw]
  (case rarity-kw
    :uncommon Rarity/UNCOMMON
    :rare Rarity/RARE
    :epic Rarity/EPIC
    Rarity/COMMON))

(defn apply-item-properties
  ^Item$Properties
  [^Item$Properties base item-spec]
  (let [max-stack-size (:max-stack-size item-spec)
        durability (:durability item-spec)
        rarity (:rarity item-spec)
        food-props (:food-properties item-spec)
        forge-food (when food-props
                     (let [^FoodProperties$Builder b (FoodProperties$Builder.)
                           _ (.nutrition b (int (or (:nutrition food-props) 0)))
                           _ (.saturationMod b (float (or (:saturation food-props) 0.0)))]
                       (when (:meat food-props)
                         (.meat b))
                       (when (:fast-to-eat food-props)
                         (.fast b))
                       (when (:always-edible food-props)
                         (.alwaysEat b))
                       (.build b)))]
    (cond-> base
      (some? max-stack-size) (.stacksTo (int max-stack-size))
      (some? durability) (.durability (int durability))
      (some? rarity) (.rarity (rarity->forge-rarity rarity))
      (some? forge-food) (.food forge-food))))

(defn create-standalone-item
  ^Item
  [item-spec]
  (let [props (apply-item-properties (Item$Properties.) item-spec)
        energy-item? (true? (get-in item-spec [:properties :energy-item?]))
        enchantability (int (or (:enchantability item-spec) 0))
        tooltip-lines (mapv str (or (get-in item-spec [:properties :tooltip]) []))
        current-key (str (or (get-in item-spec [:properties :bar-current-key]) "energy"))
        max-key (str (or (get-in item-spec [:properties :bar-max-key]) "maxEnergy"))
        default-max (double (or (get-in item-spec [:properties :energy-capacity]) 1.0))
        bar-color (int (or (get-in item-spec [:properties :energy-bar-color]) 0x00E5FF))]
    (if energy-item?
      (NbtBarItem. props current-key max-key default-max bar-color)
      (ScriptedItem. props enchantability tooltip-lines))))
