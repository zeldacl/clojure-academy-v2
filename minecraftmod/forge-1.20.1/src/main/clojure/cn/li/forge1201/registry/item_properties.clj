(ns cn.li.forge1201.registry.item-properties
  "Forge-specific item property assembly extracted from the mod entrypoint.

  Builds a per-item `clojure.lang.IFn` callback closure that ScriptedItem/NbtBarItem
  call from their native `Item.use()` override.  The callback captures the DSL item
  spec's `:on-use` and `:on-right-click` handlers and maps `:consume?` to
  `InteractionResultHolder.success` / `pass`.  Each item instance gets its own
  closure — the Java class stays generic."
  (:import [net.minecraft.world.food FoodProperties$Builder]
           [net.minecraft.world.item Item Item$Properties Rarity]
           [net.minecraft.world InteractionResultHolder]
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

(defn- build-use-callback
  "Build a per-item clojure.lang.IFn callback that bridges Minecraft's native
  Item.use() call into the DSL handler system.

  The callback captures the item-spec's :on-use and :on-right-click handlers
  via closure.  When Item.use() is called by the game, the callback:
    1. Derives :client/:server side from the Level
    2. Fires :on-use first (logging, state prep)
    3. Fires :on-right-click — its :consume? return drives SUCCESS vs PASS
    4. Returns InteractionResultHolder/success or /pass accordingly"
  [item-spec]
  (let [on-use-fn (:on-use item-spec)
        on-right-click-fn (:on-right-click item-spec)]
    (reify clojure.lang.IFn
      (invoke [_this level player hand]
        (let [side (if (.isClientSide ^net.minecraft.world.level.Level level)
                     :client :server)
              stack (.getItemInHand
                     ^net.minecraft.world.entity.player.Player player
                     ^net.minecraft.world.InteractionHand hand)
              event-data {:player player :item-stack stack :hand hand :side side}]
          ;; 1. Always fire :on-use first (logging, state prep, etc.)
          (when on-use-fn
            (on-use-fn event-data))
          ;; 2. Fire :on-right-click; :consume? drives SUCCESS vs PASS
          (if on-right-click-fn
            (let [ret (on-right-click-fn event-data)]
              (if (:consume? ret)
                (InteractionResultHolder/success stack)
                (InteractionResultHolder/pass stack)))
            (InteractionResultHolder/pass stack)))))))

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
        bar-color (int (or (get-in item-spec [:properties :energy-bar-color]) 0x00E5FF))
        ;; Build the per-item callback that bridges native Item.use() → DSL handlers.
        ;; Each item gets its own closure capturing its specific :on-use/:on-right-click.
        callback (build-use-callback item-spec)]
    (if energy-item?
      (NbtBarItem. props current-key max-key default-max bar-color callback)
      (ScriptedItem. props enchantability tooltip-lines callback))))
