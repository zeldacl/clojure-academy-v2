(ns my-mod.wireless.gui.node-gui
  "Wireless Node GUI - CGui-based client-side interface"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.wireless.gui.node-container :as node-container]))

;; ============================================================================
;; GUI Dimensions
;; ============================================================================

(def gui-width 176)
(def gui-height 166)

;; ============================================================================
;; Widget Factory Functions
;; ============================================================================

(defn create-background-panel
  "Create background texture panel"
  []
  (let [widget (cgui/create-widget :pos [0 0] :size [gui-width gui-height])]
    (comp/add-component! widget 
      (comp/texture "my_mod:textures/gui/wireless_node.png" 0 0 gui-width gui-height))
    widget))

(defn create-energy-panel
  "Create energy display panel with progress bar
  
  Args:
  - container: NodeContainer for data access
  
  Returns: Widget with energy bar and text"
  [container]
  (let [panel (cgui/create-container :pos [8 20] :size [60 70])
        
        ;; Energy bar (vertical)
        energy-bar-widget (cgui/create-widget :pos [22 10] :size [16 52])
        progress-bar (comp/progress-bar 
                       :direction :vertical
                       :progress 0.0
                       :color-full 0x00FF00
                       :color-empty 0x404040)
        
        ;; Energy text label
        energy-text (cgui/create-widget :pos [0 64] :size [60 10])
        text-box (comp/text-box 
                   :text "0 / 0 IF"
                   :color 0xFFFFFF
                   :scale 0.8
                   :shadow? true)]
    
    ;; Setup energy bar
    (comp/add-component! energy-bar-widget progress-bar)
    (comp/add-component! energy-bar-widget 
      (comp/outline :color 0x666666 :width 1.0))
    
    ;; Setup text
    (comp/add-component! energy-text text-box)
    
    ;; Add frame event to update energy display
    (events/on-frame energy-bar-widget
      (fn [_]
        (let [energy @(:energy container)
              max-energy @(:max-energy container)
              progress (if (> max-energy 0)
                        (/ (double energy) (double max-energy))
                        0.0)]
          (comp/set-progress! progress-bar progress)
          (comp/set-text! text-box (str energy " / " max-energy " IF")))))
    
    ;; Add widgets to panel
    (cgui/add-widget! panel energy-bar-widget)
    (cgui/add-widget! panel energy-text)
    
    panel))

(defn create-slot-panel
  "Create item slot panel (2 slots: input/output)
  
  Args:
  - container: NodeContainer
  
  Returns: Widget with 2 slot widgets"
  [container]
  (let [panel (cgui/create-container :pos [80 35] :size [70 18])
        
        ;; Input slot (left)
        input-slot (cgui/create-widget :pos [8 0] :size [18 18])
        
        ;; Output slot (right)
        output-slot (cgui/create-widget :pos [44 0] :size [18 18])]
    
    ;; Add slot backgrounds
    (comp/add-component! input-slot (comp/outline :color 0x8B8B8B :width 1.0))
    (comp/add-component! output-slot (comp/outline :color 0x8B8B8B :width 1.0))
    
    ;; Add to panel
    (cgui/add-widget! panel input-slot)
    (cgui/add-widget! panel output-slot)
    
    panel))

(defn create-info-panel
  "Create info panel (SSID, password, node type)
  
  Args:
  - container: NodeContainer
  
  Returns: Widget with text displays"
  [container]
  (let [panel (cgui/create-container :pos [8 95] :size [160 60])
        
        ;; SSID label
        ssid-label (cgui/create-widget :pos [0 0] :size [160 10])
        ssid-text (comp/text-box :text "SSID: " :color 0xFFFFFF :scale 0.9)
        
        ;; Password label  
        password-label (cgui/create-widget :pos [0 12] :size [160 10])
        password-text (comp/text-box :text "Password: ***" :color 0xFFFFFF :scale 0.9)
        
        ;; Node type label
        type-label (cgui/create-widget :pos [0 24] :size [160 10])
        type-text (comp/text-box :text "Type: Basic" :color 0xFFFFFF :scale 0.9)
        
        ;; Transfer rate label
        transfer-label (cgui/create-widget :pos [0 36] :size [160 10])
        transfer-text (comp/text-box :text "Transfer: 0 IF/t" :color 0xFFFFFF :scale 0.9)]
    
    ;; Setup text components
    (comp/add-component! ssid-label ssid-text)
    (comp/add-component! password-label password-text)
    (comp/add-component! type-label type-text)
    (comp/add-component! transfer-label transfer-text)
    
    ;; Update on frame
    (events/on-frame panel
      (fn [_]
        (comp/set-text! ssid-text (str "SSID: " @(:ssid container)))
        (comp/set-text! password-text (str "Password: " 
                                           (if (empty? @(:password container))
                                             "(none)"
                                             "***")))
        (comp/set-text! type-text (str "Type: " 
                                       (name @(:node-type container))))
        (comp/set-text! transfer-text (str "Transfer: " 
                                           @(:transfer-rate container) " IF/t"))))
    
    ;; Add to panel
    (cgui/add-widget! panel ssid-label)
    (cgui/add-widget! panel password-label)
    (cgui/add-widget! panel type-label)
    (cgui/add-widget! panel transfer-label)
    
    panel))

(defn create-status-indicator
  "Create online/offline status indicator
  
  Args:
  - container: NodeContainer
  
  Returns: Widget with colored indicator"
  [container]
  (let [widget (cgui/create-widget :pos [150 8] :size [16 16])
        indicator-tint (comp/tint 0xFF808080)] ; Gray by default
    
    (comp/add-component! widget indicator-tint)
    (comp/add-component! widget (comp/outline :color 0x000000 :width 2.0))
    
    ;; Update color based on online status
    (events/on-frame widget
      (fn [_]
        (let [online? @(:is-online container)
              color (if online? 0xFF00FF00 0xFFFF0000)] ; Green/Red
          (comp/set-tint! indicator-tint color))))
    
    widget))

;; ============================================================================
;; Main GUI Builder
;; ============================================================================

(defn create-node-gui
  "Create complete Node GUI widget tree
  
  Args:
  - container: NodeContainer instance
  
  Returns: CGui instance"
  [container]
  (let [cgui (cgui/create-cgui)
        root (cgui/get-root cgui)]
    
    ;; Set root size
    (cgui/set-size! root gui-width gui-height)
    
    ;; Build widget tree
    (cgui/add-widget! root (create-background-panel))
    (cgui/add-widget! root (create-energy-panel container))
    (cgui/add-widget! root (create-slot-panel container))
    (cgui/add-widget! root (create-info-panel container))
    (cgui/add-widget! root (create-status-indicator container))
    
    ;; Add title drag bar
    (let [title-bar (cgui/create-widget :pos [0 0] :size [gui-width 14])]
      (comp/add-component! title-bar 
        (comp/drag-bar :text "Wireless Node" :height 14))
      (cgui/add-widget! root title-bar))
    
    cgui))

;; ============================================================================
;; GUI Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Node GUI
  
  Args:
  - container: NodeContainer instance
  - minecraft-container: Minecraft Container object
  
  Returns: CGuiScreenContainer instance"
  [container minecraft-container]
  (let [cgui (create-node-gui container)]
    (cgui/create-cgui-screen-container cgui minecraft-container)))

;; ============================================================================
;; Input Handlers
;; ============================================================================

(defn handle-text-input
  "Handle text input for SSID/Password fields
  
  This would be called from keyboard events in a text input widget"
  [container field-type text]
  (case field-type
    :ssid (reset! (:ssid container) text)
    :password (reset! (:password container) text)
    nil))

(defn handle-button-click
  "Handle button clicks from GUI
  
  Args:
  - container: NodeContainer
  - button-id: Button identifier
  - data: Optional data map"
  [container button-id data]
  (node-container/handle-button-click! container button-id data))
