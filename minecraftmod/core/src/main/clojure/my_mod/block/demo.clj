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
   storage-crate])

;; Initialize all demo blocks
(defn init-demo-blocks! []
  (log/info "Initialized demo blocks:")
  (doseq [block-id (bdsl/list-blocks)]
    (log/info "  -" block-id)))
