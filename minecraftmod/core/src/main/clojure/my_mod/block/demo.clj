(ns my-mod.block.demo
  "Demo blocks using the Block DSL"
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.gui.demo :as gui-demo]
            [my-mod.gui.container :as container]
            [my-mod.util.log :as log]))

;; Basic demo block with GUI
(bdsl/defblock demo-block
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 0
  :sounds :stone
  :on-right-click (fn [event-data]
                    (log/info "Demo block right-clicked!")
                    (let [{:keys [player world pos]} event-data]
                      (try
                        (container/open-gui-container player 
                                                       gui-demo/demo-gui 
                                                       world 
                                                       pos)
                        (catch Exception e
                          (log/info "GUI not yet implemented for this version"))))))

;; Ore blocks
(bdsl/defblock copper-ore
  :material :stone
  :hardness 3.0
  :resistance 3.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :stone
  :on-break (fn [event-data]
              (log/info "Copper ore mined!")))

(bdsl/defblock iron-ore-custom
  :material :stone
  :hardness 3.0
  :resistance 3.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 2
  :sounds :stone)

(bdsl/defblock diamond-ore-custom
  :material :stone
  :hardness 3.0
  :resistance 3.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 3
  :sounds :stone)

;; Metal blocks
(bdsl/defblock copper-block
  :material :metal
  :hardness 5.0
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 1
  :sounds :metal)

(bdsl/defblock steel-block
  :material :metal
  :hardness 5.0
  :resistance 6.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 2
  :sounds :metal)

;; Wood blocks
(bdsl/defblock custom-planks
  :material :wood
  :hardness 2.0
  :resistance 3.0
  :requires-tool false
  :harvest-tool :axe
  :sounds :wood)

;; Glass blocks
(bdsl/defblock reinforced-glass
  :material :glass
  :hardness 1.5
  :resistance 10.0
  :requires-tool false
  :sounds :glass)

;; Light-emitting blocks
(bdsl/defblock glowing-stone
  :material :stone
  :hardness 1.5
  :resistance 6.0
  :light-level 15
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone)

(bdsl/defblock dim-lamp
  :material :glass
  :hardness 1.0
  :resistance 1.0
  :light-level 10
  :sounds :glass)

;; Slippery block
(bdsl/defblock ice-block
  :material :glass
  :hardness 0.5
  :resistance 0.5
  :friction 0.98
  :slip-factor 0.98
  :sounds :glass)

;; Interactive blocks
(bdsl/defblock teleporter-block
  :material :metal
  :hardness 3.0
  :resistance 10.0
  :light-level 5
  :requires-tool true
  :on-right-click (fn [event-data]
                    (log/info "Teleporter activated!")
                    (let [{:keys [player]} event-data]
                      (log/info "Teleporting player:" player)
                      ;; TODO: Implement teleport logic
                      )))

(bdsl/defblock exploding-block
  :material :sand
  :hardness 0.5
  :resistance 0.0
  :on-break (fn [event-data]
              (log/info "BOOM! Exploding block destroyed!")
              (let [{:keys [world pos]} event-data]
                ;; TODO: Create explosion
                (log/info "Creating explosion at" pos))))

;; Crafting station using preset
(bdsl/defblock crafting-station
  :material :wood
  :hardness 2.5
  :resistance 2.5
  :requires-tool false
  :harvest-tool :axe
  :sounds :wood
  :on-right-click (fn [event-data]
                    (log/info "Opening crafting GUI...")
                    (let [{:keys [player world pos]} event-data]
                      (try
                        (container/open-gui-container player 
                                                       gui-demo/crafting-gui 
                                                       world 
                                                       pos)
                        (catch Exception e
                          (log/info "Crafting GUI not yet implemented"))))))

;; Furnace using preset
(bdsl/defblock custom-furnace
  :material :stone
  :hardness 3.5
  :resistance 3.5
  :light-level 13
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone
  :on-right-click (fn [event-data]
                    (log/info "Opening furnace GUI...")
                    (let [{:keys [player world pos]} event-data]
                      (try
                        (container/open-gui-container player 
                                                       gui-demo/furnace-gui 
                                                       world 
                                                       pos)
                        (catch Exception e
                          (log/info "Furnace GUI not yet implemented"))))))

;; Storage block
(bdsl/defblock storage-crate
  :material :wood
  :hardness 2.5
  :resistance 2.5
  :requires-tool false
  :harvest-tool :axe
  :sounds :wood
  :on-right-click (fn [event-data]
                    (log/info "Opening storage GUI...")
                    (let [{:keys [player world pos]} event-data]
                      (try
                        (container/open-gui-container player 
                                                       gui-demo/storage-gui 
                                                       world 
                                                       pos)
                        (catch Exception e
                          (log/info "Storage GUI not yet implemented"))))))

;; Using presets - more concise
(def my-ore-spec
  (bdsl/merge-presets
    (bdsl/ore-preset 2)
    {:id "my-custom-ore"
     :on-break (fn [_] (log/info "Custom ore mined!"))}))

(def magical-wood-spec
  (bdsl/merge-presets
    (bdsl/wood-preset)
    {:id "magical-wood"
     :light-level 8
     :on-right-click (fn [_] (log/info "Magical wood glows!"))}))

;; ==================== Multi-Block Structures ====================

;; Large furnace (2x3x2 - width x height x depth)
(bdsl/defblock large-furnace
  :multi-block? true
  :multi-block-size {:width 2 :height 3 :depth 2}
  :material :metal
  :hardness 5.0
  :resistance 10.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :metal
  :light-level 8
  :on-right-click (fn [event-data]
                    (log/info "Large furnace clicked!")
                    (log/info "Position:" (:pos event-data)))
  :on-place (fn [event-data]
              (log/info "Placing large furnace structure...")
              (let [{:keys [world pos]} event-data]
                (log/info "Master block at:" pos)
                ;; Platform adapter should place all parts
                ))
  :on-multi-block-break (fn [event-data]
                          (log/info "Breaking entire large furnace structure!")
                          ;; Platform adapter should remove all parts
                          ))

;; Industrial tank (3x4x3)
(bdsl/defblock industrial-tank
  :multi-block? true
  :multi-block-size {:width 3 :height 4 :depth 3}
  :material :metal
  :hardness 5.0
  :resistance 15.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :metal
  :on-right-click (fn [event-data]
                    (log/info "Industrial tank clicked!")
                    ;; Could open tank GUI
                    ))

;; Reactor core (3x3x3)
(bdsl/defblock reactor-core
  :multi-block? true
  :multi-block-size {:width 3 :height 3 :depth 3}
  :material :metal
  :hardness 10.0
  :resistance 1200.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 3
  :sounds :metal
  :light-level 15
  :on-right-click (fn [event-data]
                    (log/info "Reactor core accessed!")
                    (log/info "WARNING: High radiation!"))
  :on-multi-block-break (fn [event-data]
                          (log/info "CRITICAL: Reactor core destroyed!")
                          ;; Could trigger explosion
                          ))

;; Telescope (2x5x2 - tall structure)
(bdsl/defblock telescope
  :multi-block? true
  :multi-block-size {:width 2 :height 5 :depth 2}
  :material :metal
  :hardness 3.0
  :resistance 5.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :metal
  :on-right-click (fn [event-data]
                    (log/info "Looking through telescope...")
                    ;; Could show sky view GUI
                    ))

;; Wind turbine (5x7x5 - large structure)
(bdsl/defblock wind-turbine
  :multi-block? true
  :multi-block-size {:width 5 :height 7 :depth 5}
  :material :metal
  :hardness 4.0
  :resistance 8.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :metal
  :on-place (fn [event-data]
              (log/info "Constructing wind turbine...")
              (log/info "Size: 5x7x5 blocks"))
  :on-right-click (fn [event-data]
                    (log/info "Wind turbine energy output: 100 FE/t")))

;; Using multi-block preset
(def custom-machine-spec
  (bdsl/merge-presets
    (bdsl/multi-block-preset {:width 3 :height 2 :depth 3})
    {:id "custom-machine"
     :light-level 10
     :on-right-click (fn [_] (log/info "Custom machine activated!"))}))

;; ==================== Irregular Multi-Block Structures ====================

;; Cross-shaped altar (+ shape on ground)
(bdsl/defblock cross-altar
  :multi-block? true
  :multi-block-positions [{:x 0 :y 0 :z 0}   ; center
                          {:x 1 :y 0 :z 0}   ; +X
                          {:x -1 :y 0 :z 0}  ; -X
                          {:x 0 :y 0 :z 1}   ; +Z
                          {:x 0 :y 0 :z -1}] ; -Z
  :material :stone
  :hardness 3.0
  :resistance 10.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone
  :light-level 10
  :on-right-click (fn [event-data]
                    (log/info "Cross altar activated!")
                    (log/info "Performing ritual...")))

;; L-shaped crafting table
(bdsl/defblock l-shaped-workbench
  :multi-block? true
  :multi-block-positions [{:x 0 :y 0 :z 0}
                          {:x 1 :y 0 :z 0}
                          {:x 2 :y 0 :z 0}
                          {:x 0 :y 0 :z 1}
                          {:x 0 :y 0 :z 2}]
  :material :wood
  :hardness 2.5
  :resistance 3.0
  :requires-tool false
  :harvest-tool :axe
  :sounds :wood
  :on-right-click (fn [event-data]
                    (log/info "Opening advanced crafting interface...")))

;; T-shaped beacon
(bdsl/defblock t-beacon
  :multi-block? true
  :multi-block-positions [{:x -1 :y 0 :z 0}  ; horizontal bar
                          {:x 0 :y 0 :z 0}
                          {:x 1 :y 0 :z 0}
                          {:x 0 :y 0 :z 1}   ; vertical stem
                          {:x 0 :y 0 :z 2}
                          {:x 0 :y 1 :z 1}   ; top piece
                          {:x 0 :y 2 :z 1}]
  :material :metal
  :hardness 5.0
  :resistance 15.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :metal
  :light-level 15
  :on-right-click (fn [event-data]
                    (log/info "T-beacon emitting signal...")))

;; Pyramid shrine (4-layer pyramid)
(bdsl/defblock pyramid-shrine
  :multi-block? true
  :multi-block-positions (bdsl/create-pyramid-shape 5 4)
  :material :stone
  :hardness 4.0
  :resistance 20.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 2
  :sounds :stone
  :light-level 12
  :on-right-click (fn [event-data]
                    (log/info "Ancient pyramid shrine activated!")
                    (log/info "Granting ancient power...")))

;; Hollow energy chamber (hollow 5x5x5 cube)
(bdsl/defblock hollow-energy-chamber
  :multi-block? true
  :multi-block-positions (bdsl/create-hollow-cube 5)
  :material :metal
  :hardness 8.0
  :resistance 50.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 3
  :sounds :metal
  :light-level 15
  :on-right-click (fn [event-data]
                    (log/info "Energy chamber accessed!")
                    (log/info "Hollow structure allows energy flow"))
  :on-multi-block-break (fn [event-data]
                          (log/info "WARNING: Energy containment breach!")
                          (log/info "Releasing stored energy...")))

;; Star-shaped portal (using cross with diagonal extensions)
(bdsl/defblock star-portal
  :multi-block? true
  :multi-block-positions [{:x 0 :y 0 :z 0}    ; center
                          ;; Cardinal directions
                          {:x 1 :y 0 :z 0}
                          {:x 2 :y 0 :z 0}
                          {:x -1 :y 0 :z 0}
                          {:x -2 :y 0 :z 0}
                          {:x 0 :y 0 :z 1}
                          {:x 0 :y 0 :z 2}
                          {:x 0 :y 0 :z -1}
                          {:x 0 :y 0 :z -2}
                          ;; Diagonal corners
                          {:x 1 :y 0 :z 1}
                          {:x 1 :y 0 :z -1}
                          {:x -1 :y 0 :z 1}
                          {:x -1 :y 0 :z -1}]
  :material :metal
  :hardness 10.0
  :resistance 100.0
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 3
  :sounds :metal
  :light-level 15
  :on-right-click (fn [event-data]
                    (log/info "Star portal activating...")
                    (log/info "Dimensional gateway opening!"))
  :on-place (fn [event-data]
              (log/info "Constructing star portal...")
              (log/info "Pattern: 13 blocks in star formation")))

;; Spiral staircase (ascending spiral)
(bdsl/defblock spiral-staircase
  :multi-block? true
  :multi-block-positions [{:x 0 :y 0 :z 0}
                          {:x 1 :y 1 :z 0}
                          {:x 1 :y 2 :z 1}
                          {:x 0 :y 3 :z 1}
                          {:x -1 :y 4 :z 1}
                          {:x -1 :y 5 :z 0}
                          {:x -1 :y 6 :z -1}
                          {:x 0 :y 7 :z -1}]
  :material :stone
  :hardness 3.0
  :resistance 5.0
  :requires-tool true
  :harvest-tool :pickaxe
  :sounds :stone
  :on-place (fn [event-data]
              (log/info "Building spiral staircase...")
              (log/info "Height: 8 blocks in spiral pattern")))

;; Using shape helper functions
(def cross-shaped-platform
  (bdsl/merge-presets
    (bdsl/irregular-multi-block-preset 
      (flatten (bdsl/create-cross-shape 3)))
    {:id "cross-platform"
     :material :metal
     :light-level 8}))

;; Helper: Get all demo blocks
(defn get-all-demo-blocks []
  [demo-block
   copper-ore
   iron-ore-custom
   diamond-ore-custom
   copper-block
   steel-block
   custom-planks
   reinforced-glass
   glowing-stone
   dim-lamp
   ice-block
   teleporter-block
   exploding-block
   crafting-station
   custom-furnace
   storage-crate
   ;; Regular multi-block structures
   large-furnace
   industrial-tank
   reactor-core
   telescope
   wind-turbine
   ;; Irregular multi-block structures
   cross-altar
   l-shaped-workbench
   t-beacon
   pyramid-shrine
   hollow-energy-chamber
   star-portal
   spiral-staircase])

;; Initialize all demo blocks
(defn init-demo-blocks! []
  (log/info "Initialized demo blocks:")
  (doseq [block-id (bdsl/list-blocks)]
    (log/info "  -" block-id)))
