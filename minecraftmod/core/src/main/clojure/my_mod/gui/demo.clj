(ns my-mod.gui.demo
  "Demo GUI using the DSL"
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.gui.core :as gui-core]
            [my-mod.util.log :as log]))

;; Create a shared slots atom for the demo GUI
(defonce demo-slots (atom {}))

;; Define the demo GUI using the DSL
(dsl/defgui demo-gui
  :title "Demo Container"
  :width 176
  :height 166
  :slots [{:index 0 
           :x 80 
           :y 35
           :filter dsl/any-item-filter
           :on-change (dsl/slot-change-handler demo-slots 0)}]
  :buttons [{:id 0 
             :x 100 
             :y 60 
             :width 60
             :height 20
             :text "Destroy"
             :on-click (dsl/clear-slot-handler demo-slots 0)}]
  :labels [{:x 8 
            :y 6 
            :text "Demo GUI"}
           {:x 8 
            :y 72 
            :text "Inventory"}])

;; More complex example: Crafting-like GUI
(dsl/defgui crafting-gui
  :title "Crafting Table"
  :width 176
  :height 166
  :slots [;; Input slots (3x3 grid)
          {:index 0 :x 30 :y 17}
          {:index 1 :x 48 :y 17}
          {:index 2 :x 66 :y 17}
          {:index 3 :x 30 :y 35}
          {:index 4 :x 48 :y 35}
          {:index 5 :x 66 :y 35}
          {:index 6 :x 30 :y 53}
          {:index 7 :x 48 :y 53}
          {:index 8 :x 66 :y 53}
          ;; Output slot
          {:index 9 :x 124 :y 35}]
  :buttons [{:id 0 
             :x 95 
             :y 33 
             :width 20
             :height 20
             :text "→"
             :on-click #(log/info "Craft button clicked")}
            {:id 1 
             :x 8 
             :y 60 
             :width 50
             :height 15
             :text "Clear"
             :on-click #(doseq [i (range 9)]
                          (swap! demo-slots dissoc i))}]
  :labels [{:x 8 :y 6 :text "Crafting"}])

;; Furnace-like GUI
(defonce furnace-slots (atom {}))

(defn can-smelt? [item]
  ;; TODO: Check if item is smeltable
  true)

(defn start-smelting []
  (log/info "Starting smelting process...")
  (let [input (get @furnace-slots 0)
        fuel (get @furnace-slots 1)]
    (when (and input fuel)
      (log/info "Smelting" input "with" fuel)
      ;; TODO: Actual smelting logic
      (swap! furnace-slots assoc 2 input) ; Mock output
      (swap! furnace-slots dissoc 0 1))))

(dsl/defgui furnace-gui
  :title "Furnace"
  :width 176
  :height 166
  :slots [;; Input slot
          {:index 0 
           :x 56 
           :y 17
           :filter can-smelt?
           :on-change (dsl/slot-change-handler furnace-slots 0)}
          ;; Fuel slot
          {:index 1 
           :x 56 
           :y 53
           :on-change (dsl/slot-change-handler furnace-slots 1)}
          ;; Output slot
          {:index 2 
           :x 116 
           :y 35
           :filter (constantly false) ; Output only, no input
           :on-change (dsl/slot-change-handler furnace-slots 2)}]
  :buttons [{:id 0 
             :x 80 
             :y 34 
             :width 24
             :height 17
             :text "🔥"
             :on-click start-smelting}]
  :labels [{:x 8 :y 6 :text "Furnace"}
           {:x 8 :y 72 :text "Inventory"}])

;; Storage GUI (chest-like)
(defonce storage-slots (atom {}))

(dsl/defgui storage-gui
  :title "Storage"
  :width 176
  :height 222
  :slots (vec
          (for [row (range 6)
                col (range 9)]
            (let [index (+ (* row 9) col)]
              {:index index
               :x (+ 8 (* col 18))
               :y (+ 18 (* row 18))
               :on-change (dsl/slot-change-handler storage-slots index)})))
  :buttons [{:id 0 
             :x 8 
             :y 140
             :width 40
             :height 15
             :text "Sort"
             :on-click #(log/info "Sorting inventory...")}
            {:id 1 
             :x 130
             :y 140
             :width 40
             :height 15
             :text "Clear"
             :on-click #(reset! storage-slots {})}]
  :labels [{:x 8 :y 6 :text "Storage"}
           {:x 8 :y 146 :text "Inventory"}])

;; Helper: Get GUI by name
(defn get-demo-gui []
  demo-gui)

(defn get-crafting-gui []
  crafting-gui)

(defn get-furnace-gui []
  furnace-gui)

(defn get-storage-gui []
  storage-gui)

;; Initialize all GUIs
(defn init-demo-guis! []
  (log/info "Initialized demo GUIs:")
  (doseq [gui-id (dsl/list-guis)]
    (log/info "  -" gui-id)))
