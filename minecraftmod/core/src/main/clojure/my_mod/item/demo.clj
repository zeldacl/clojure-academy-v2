(ns my-mod.item.demo
  "Demo items using the Item DSL"
  (:require [my-mod.item.dsl :as idsl]
            [my-mod.util.log :as log]))

;; Basic demo item
(idsl/defitem demo-item
  :max-stack-size 64
  :creative-tab :misc
  :on-use (fn [event-data]
            (log/info "Demo item used!")
            (let [{:keys [player world]} event-data]
              (log/info "Player" player "used demo item in" world))))

;; Materials
(idsl/defitem copper-ingot
  :max-stack-size 64
  :creative-tab :materials
  :rarity :common)

(idsl/defitem steel-ingot
  :max-stack-size 64
  :creative-tab :materials
  :rarity :uncommon)

(idsl/defitem mythril-shard
  :max-stack-size 16
  :creative-tab :materials
  :rarity :rare)

(idsl/defitem dragon-scale
  :max-stack-size 1
  :creative-tab :materials
  :rarity :epic)

;; Tools
(idsl/defitem copper-pickaxe
  :max-stack-size 1
  :creative-tab :tools
  :durability 200
  :tool-properties (idsl/tool-properties :stone 3.0 -2.8)
  :enchantability 12
  :on-use (fn [event-data]
            (log/info "Mining with copper pickaxe...")))

(idsl/defitem steel-sword
  :max-stack-size 1
  :creative-tab :combat
  :durability 1500
  :tool-properties (idsl/tool-properties :diamond 7.0 -2.4)
  :enchantability 14)

(idsl/defitem diamond-hammer
  :max-stack-size 1
  :creative-tab :tools
  :durability 1561
  :tool-properties (idsl/tool-properties :diamond 5.0 -3.0)
  :enchantability 10
  :on-use (fn [event-data]
            (log/info "Smashing with hammer!")
            ;; Could break blocks in 3x3 area
            ))

;; Food items
(idsl/defitem golden-apple-custom
  :max-stack-size 64
  :creative-tab :food
  :rarity :rare
  :food-properties (idsl/food-properties 4 9.6 :always-edible true)
  :on-finish-using (fn [event-data]
                     (log/info "Golden apple eaten - applying effects...")
                     ;; Apply regeneration and absorption effects
                     ))

(idsl/defitem magic-bread
  :max-stack-size 16
  :creative-tab :food
  :food-properties (idsl/food-properties 5 6.0)
  :on-finish-using (fn [event-data]
                     (log/info "Magic bread consumed!")
                     (let [{:keys [player]} event-data]
                       (log/info "Restoring mana for" player))))

(idsl/defitem healing-potion
  :max-stack-size 16
  :creative-tab :brewing
  :on-use (fn [event-data]
            (log/info "Drinking healing potion...")
            ;; Instant heal
            ))

;; Special items
(idsl/defitem teleport-staff
  :max-stack-size 1
  :creative-tab :tools
  :durability 100
  :rarity :epic
  :on-use (fn [event-data]
            (log/info "Activating teleport staff...")
            (let [{:keys [player world]} event-data]
              (log/info "Teleporting player" player)
              ;; Teleport to spawn or saved location
              )))

(idsl/defitem wand-of-fire
  :max-stack-size 1
  :creative-tab :combat
  :durability 500
  :rarity :rare
  :enchantability 20
  :on-use (fn [event-data]
            (log/info "Casting fireball!")
            ;; Spawn fireball projectile
            ))

(idsl/defitem debug-stick
  :max-stack-size 1
  :creative-tab :tools
  :rarity :epic
  :on-right-click (fn [event-data]
                    (log/info "Debug stick right-clicked on block")
                    (let [{:keys [world pos block]} event-data]
                      (log/info "Block at" pos ":" block)
                      ;; Cycle through block states
                      )))

;; Currency/tokens
(idsl/defitem gold-coin
  :max-stack-size 64
  :creative-tab :misc
  :rarity :uncommon)

(idsl/defitem platinum-coin
  :max-stack-size 64
  :creative-tab :misc
  :rarity :rare)

;; Quest items
(idsl/defitem mysterious-artifact
  :max-stack-size 1
  :creative-tab :misc
  :rarity :epic
  :on-use (fn [event-data]
            (log/info "Mysterious artifact activated!")
            ;; Trigger quest event
            ))

;; Baubles/accessories
(idsl/defitem speed-ring
  :max-stack-size 1
  :creative-tab :combat
  :rarity :rare
  :on-use (fn [event-data]
            (log/info "Speed boost activated!")))

(idsl/defitem regeneration-amulet
  :max-stack-size 1
  :creative-tab :combat
  :rarity :epic)

;; Using presets - more concise
(def copper-nugget-spec
  (idsl/merge-presets
    (idsl/basic-item-preset)
    {:id "copper-nugget"
     :creative-tab :materials}))

(def magic-sword-spec
  (idsl/merge-presets
    (idsl/tool-preset :diamond 1561 8.0 -2.4)
    {:id "magic-sword"
     :rarity :epic
     :on-use (fn [_] (log/info "Magic sword unleashes power!"))}))

(def enchanted-bread-spec
  (idsl/merge-presets
    (idsl/food-preset 6 8.0)
    {:id "enchanted-bread"
     :rarity :uncommon
     :on-finish-using (fn [_] (log/info "Enchanted bread grants buff!"))}))

;; Helper: Get all demo items
(defn get-all-demo-items []
  [demo-item
   copper-ingot
   steel-ingot
   mythril-shard
   dragon-scale
   copper-pickaxe
   steel-sword
   diamond-hammer
   golden-apple-custom
   magic-bread
   healing-potion
   teleport-staff
   wand-of-fire
   debug-stick
   gold-coin
   platinum-coin
   mysterious-artifact
   speed-ring
   regeneration-amulet])

;; Initialize all demo items
(defn init-demo-items! []
  (log/info "Initialized demo items:")
  (doseq [item-id (idsl/list-items)]
    (log/info "  -" item-id)))
