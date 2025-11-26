(ns my-mod.wireless.gui.matrix-gui
  "Wireless Matrix GUI - CGui-based multiblock interface"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.wireless.gui.matrix-container :as matrix-container]))

;; ============================================================================
;; GUI Dimensions
;; ============================================================================

(def gui-width 176)
(def gui-height 200)

;; ============================================================================
;; Widget Factory Functions
;; ============================================================================

(defn create-background-panel
  "Create background texture panel"
  []
  (let [widget (cgui/create-widget :pos [0 0] :size [gui-width gui-height])]
    (comp/add-component! widget 
      (comp/texture "my_mod:textures/gui/wireless_matrix.png" 0 0 gui-width gui-height))
    widget))

(defn create-status-panel
  "Create status panel with working indicator and info
  
  Args:
  - container: MatrixContainer
  
  Returns: Widget with status display"
  [container]
  (let [panel (cgui/create-container :pos [8 20] :size [160 40])
        
        ;; Working indicator icon
        indicator-widget (cgui/create-widget :pos [8 12] :size [32 32])
        indicator-tint (comp/tint 0xFF808080) ; Gray by default
        
        ;; Core level display
        core-level-widget (cgui/create-widget :pos [50 8] :size [100 10])
        core-level-text (comp/text-box :text "Core: None" :color 0xFFFFFF :scale 0.9)
        
        ;; Plate count display
        plate-count-widget (cgui/create-widget :pos [50 22] :size [100 10])
        plate-count-text (comp/text-box :text "Plates: 0 / 3" :color 0xFFFFFF :scale 0.9)]
    
    ;; Setup indicator
    (comp/add-component! indicator-widget indicator-tint)
    (comp/add-component! indicator-widget (comp/outline :color 0x000000 :width 2.0))
    
    ;; Setup text
    (comp/add-component! core-level-widget core-level-text)
    (comp/add-component! plate-count-widget plate-count-text)
    
    ;; Update on frame
    (events/on-frame panel
      (fn [_]
        (let [working? @(:is-working container)
              core-lvl @(:core-level container)
              plates @(:plate-count container)]
          ;; Update indicator color
          (comp/set-tint! indicator-tint 
                         (if working? 0xFF00FF00 0xFFFF0000))
          ;; Update core level
          (comp/set-text! core-level-text 
                         (if (> core-lvl 0)
                           (str "Core: Tier " core-lvl)
                           "Core: None"))
          ;; Update plate count
          (comp/set-text! plate-count-text 
                         (str "Plates: " plates " / 3")))))
    
    ;; Add to panel
    (cgui/add-widget! panel indicator-widget)
    (cgui/add-widget! panel core-level-widget)
    (cgui/add-widget! panel plate-count-widget)
    
    panel))

(defn create-slot-panel
  "Create slot panel (3 plates + 1 core)
  
  Args:
  - container: MatrixContainer
  
  Returns: Widget with 4 slot widgets"
  [container]
  (let [panel (cgui/create-container :pos [28 70] :size [120 50])
        
        ;; Plate slots (3 in a row)
        plate-1 (cgui/create-widget :pos [0 0] :size [18 18])
        plate-2 (cgui/create-widget :pos [34 0] :size [18 18])
        plate-3 (cgui/create-widget :pos [68 0] :size [18 18])
        
        ;; Core slot (centered below, larger)
        core-slot (cgui/create-widget :pos [47 24] :size [26 26])]
    
    ;; Add plate slot backgrounds
    (comp/add-component! plate-1 (comp/outline :color 0x8B8B8B :width 1.0))
    (comp/add-component! plate-2 (comp/outline :color 0x8B8B8B :width 1.0))
    (comp/add-component! plate-3 (comp/outline :color 0x8B8B8B :width 1.0))
    
    ;; Add core slot background (gold outline to emphasize)
    (comp/add-component! core-slot (comp/outline :color 0xFFD700 :width 2.0))
    
    ;; Add to panel
    (cgui/add-widget! panel plate-1)
    (cgui/add-widget! panel plate-2)
    (cgui/add-widget! panel plate-3)
    (cgui/add-widget! panel core-slot)
    
    panel))

(defn create-stats-panel
  "Create statistics panel (capacity, bandwidth, range)
  
  Args:
  - container: MatrixContainer
  
  Returns: Widget with stats display"
  [container]
  (let [panel (cgui/create-container :pos [8 130] :size [160 50])
        
        ;; Capacity bar
        capacity-label (cgui/create-widget :pos [0 0] :size [160 8])
        capacity-text (comp/text-box :text "Capacity:" :color 0xFFFFFF :scale 0.8)
        
        capacity-bar-widget (cgui/create-widget :pos [0 10] :size [140 8])
        capacity-bar (comp/progress-bar 
                       :direction :horizontal
                       :progress 0.0
                       :color-full 0x00AAFF
                       :color-empty 0x404040)
        
        ;; Bandwidth display
        bandwidth-widget (cgui/create-widget :pos [0 22] :size [160 10])
        bandwidth-text (comp/text-box :text "Bandwidth: 0 IF/t" :color 0xFFFFFF :scale 0.8)
        
        ;; Range display
        range-widget (cgui/create-widget :pos [0 34] :size [160 10])
        range-text (comp/text-box :text "Range: 0.0 blocks" :color 0xFFFFFF :scale 0.8)]
    
    ;; Setup capacity
    (comp/add-component! capacity-label capacity-text)
    (comp/add-component! capacity-bar-widget capacity-bar)
    (comp/add-component! capacity-bar-widget 
      (comp/outline :color 0x666666 :width 1.0))
    
    ;; Setup bandwidth and range
    (comp/add-component! bandwidth-widget bandwidth-text)
    (comp/add-component! range-widget range-text)
    
    ;; Update on frame
    (events/on-frame panel
      (fn [_]
        (let [capacity @(:capacity container)
              max-capacity @(:max-capacity container)
              bandwidth @(:bandwidth container)
              range @(:range container)
              progress (if (> max-capacity 0)
                        (/ (double capacity) (double max-capacity))
                        0.0)]
          ;; Update capacity bar
          (comp/set-progress! capacity-bar progress)
          (comp/set-text! capacity-text 
                         (str "Capacity: " capacity " / " max-capacity " IF"))
          ;; Update stats
          (comp/set-text! bandwidth-text 
                         (str "Bandwidth: " bandwidth " IF/t"))
          (comp/set-text! range-text 
                         (str "Range: " (format "%.1f" range) " blocks")))))
    
    ;; Add to panel
    (cgui/add-widget! panel capacity-label)
    (cgui/add-widget! panel capacity-bar-widget)
    (cgui/add-widget! panel bandwidth-widget)
    (cgui/add-widget! panel range-widget)
    
    panel))

(defn create-multiblock-status-indicator
  "Create indicator showing multiblock formation status
  
  Args:
  - container: MatrixContainer
  
  Returns: Widget with formation status"
  [container]
  (let [widget (cgui/create-widget :pos [8 185] :size [160 10])
        status-text (comp/text-box :text "Structure: Not Formed" :color 0xFF0000 :scale 0.8)]
    
    (comp/add-component! widget status-text)
    
    ;; Update on frame
    (events/on-frame widget
      (fn [_]
        (let [working? @(:is-working container)]
          (comp/set-text! status-text 
                         (if working?
                           "Structure: Formed ✓"
                           "Structure: Not Formed ✗"))
          (comp/set-text-color! status-text 
                               (if working? 0x00FF00 0xFF0000)))))
    
    widget))

;; ============================================================================
;; Main GUI Builder
;; ============================================================================

(defn create-matrix-gui
  "Create complete Matrix GUI widget tree
  
  Args:
  - container: MatrixContainer instance
  
  Returns: CGui instance"
  [container]
  (let [cgui (cgui/create-cgui)
        root (cgui/get-root cgui)]
    
    ;; Set root size
    (cgui/set-size! root gui-width gui-height)
    
    ;; Build widget tree
    (cgui/add-widget! root (create-background-panel))
    (cgui/add-widget! root (create-status-panel container))
    (cgui/add-widget! root (create-slot-panel container))
    (cgui/add-widget! root (create-stats-panel container))
    (cgui/add-widget! root (create-multiblock-status-indicator container))
    
    ;; Add title drag bar
    (let [title-bar (cgui/create-widget :pos [0 0] :size [gui-width 14])]
      (comp/add-component! title-bar 
        (comp/drag-bar :text "Wireless Matrix" :height 14))
      (cgui/add-widget! root title-bar))
    
    cgui))

;; ============================================================================
;; GUI Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Matrix GUI
  
  Args:
  - container: MatrixContainer instance
  - minecraft-container: Minecraft Container object
  
  Returns: CGuiScreenContainer instance"
  [container minecraft-container]
  (let [cgui (create-matrix-gui container)]
    (cgui/create-cgui-screen-container cgui minecraft-container)))

;; ============================================================================
;; Input Handlers
;; ============================================================================

(defn handle-button-click
  "Handle button clicks from GUI
  
  Args:
  - container: MatrixContainer
  - button-id: Button identifier
  - data: Optional data map"
  [container button-id data]
  (matrix-container/handle-button-click! container button-id data))

;; ============================================================================
;; Dynamic Updates
;; ============================================================================

(defn update-stats-display
  "Force update of stats display
  
  Called when multiblock structure changes"
  [container]
  ;; Stats are automatically updated via on-frame events
  ;; This function can trigger immediate refresh if needed
  (matrix-container/sync-to-client! container))
