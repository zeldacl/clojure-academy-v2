(ns my-mod.wireless.gui.node-gui-xml
  "Wireless Node GUI - XML-based implementation
  
  Architecture: XML Layout → DSL → Runtime Logic
  
  Flow:
  1. Load page_wireless_node.xml (defines structure)
  2. Parse to DSL GuiSpec (xml-parser)
  3. Add runtime logic (animations, network sync, event handlers)
  4. Render using CGui components"
  (:require [my-mod.gui.dsl :as dsl]
            [my-mod.gui.xml-parser :as xml]
            [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.wireless.gui.node-container :as container]
            [my-mod.util.log :as log]))

;; ============================================================================
;; XML Layout Loading
;; ============================================================================

;; Load base layout from XML
;; This defines: widget tree, slots, buttons, labels, histograms, animations
(defonce node-gui-xml-spec
  (delay
    (try
      (xml/load-gui-from-xml "wireless-node-gui" "page_wireless_node")
      (catch Exception e
        (log/error "Failed to load Node GUI XML layout:" (.getMessage e))
        nil))))

;; ============================================================================
;; Animation System
;; ============================================================================

(defn create-animation-state
  "Create animation state atom
  
  Animation states (from XML):
  - :linked - 8 frames, 800ms/frame (frames 0-7)
  - :unlinked - 2 frames, 3000ms/frame (frames 8-9)"
  []
  {:current-state (atom :unlinked)
   :current-frame (atom 0)
   :last-update (atom (System/currentTimeMillis))})

(defn get-animation-config
  "Get animation configuration for current state"
  [state]
  (case state
    :linked {:begin 0 :frames 8 :frame-time 800}
    :unlinked {:begin 8 :frames 2 :frame-time 3000}
    {:begin 0 :frames 1 :frame-time 1000}))

(defn update-animation!
  "Update animation frame based on elapsed time"
  [anim-state]
  (let [{:keys [current-state current-frame last-update]} anim-state
        now (System/currentTimeMillis)
        dt (- now @last-update)
        config (get-animation-config @current-state)
        {:keys [frames frame-time]} config]
    (when (>= dt frame-time)
      (swap! current-frame #(mod (inc %) frames))
      (reset! last-update now))))

(defn render-animation-frame!
  "Render current animation frame
  
  Uses UV mapping: texture is vertically sliced into 10 frames
  Frame N maps to UV (0, N/10, 1, 1/10)"
  [anim-state widget]
  (let [{:keys [current-state current-frame]} anim-state
        config (get-animation-config @current-state)
        absolute-frame (+ (:begin config) @current-frame)
        total-frames 10
        u0 0.0
        v0 (/ (double absolute-frame) total-frames)
        u1 1.0
        v1 (/ 1.0 total-frames)]
    ;; Render texture region with calculated UV
    (comp/render-texture-region
      widget
      "my_mod:textures/gui/effect_node.png"
      0 0 186 75 ; Size from XML (then scaled 0.5 → 93x37.5)
      u0 v0 u1 v1)))

;; ============================================================================
;; Network Status Poller
;; ============================================================================

(defn create-status-poller
  "Create a network status poller that queries link state every 2 seconds
  
  Args:
  - tile: NodeTileEntity
  - anim-state: Animation state atom
  
  Returns: Poller map with timer"
  [tile anim-state]
  (let [last-query (atom (- (System/currentTimeMillis) 3000))] ; Start immediately
    {:last-query last-query
     :update-fn (fn []
                  (let [now (System/currentTimeMillis)
                        dt (- now @last-query)]
                    (when (> dt 2000) ; Query every 2 seconds
                      (reset! last-query now)
                      ;; Query network link status
                      ;; TODO: Implement network query
                      ;; For now, simulate with tile state
                      (let [is-linked @(:enabled tile)]
                        (reset! (:current-state anim-state)
                                (if is-linked :linked :unlinked))))))}))

;; ============================================================================
;; Info Panel Components
;; ============================================================================

(defn create-histogram-widget
  "Create histogram widget from XML specification
  
  Args:
  - hist-spec: Histogram spec from XML {:name :label :type :color :y :height}
  - container: NodeContainer for data access"
  [hist-spec container]
  (let [widget (cgui/create-widget :pos [0 (:y hist-spec 0)] :size [100 (:height hist-spec 40)])
        {:keys [label type color]} hist-spec
        
        ;; Create progress bar component
        bar (comp/progress-bar
              :direction :horizontal
              :progress 0.0
              :color-full color
              :color-empty 0x404040)
        
        ;; Create label
        label-text (comp/text-box
                     :text label
                     :color 0xFFFFFF
                     :scale 0.7
                     :shadow? true)]
    
    ;; Add components
    (comp/add-component! widget label-text)
    (comp/add-component! widget bar)
    
    ;; Add update logic based on type
    (events/on-frame widget
      (fn [_]
        (let [[current max-val] (case type
                                  :energy [@(:energy container) @(:max-energy container)]
                                  :capacity [@(:capacity container) @(:max-capacity container)]
                                  [0 1])
              progress (if (> max-val 0) (/ (double current) (double max-val)) 0.0)]
          (comp/set-progress! bar progress)
          (comp/set-text! label-text (str label ": " current "/" max-val)))))
    
    widget))

(defn create-property-widget
  "Create property widget from XML specification
  
  Args:
  - prop-spec: Property spec from XML {:name :label :editable :masked :max-length :requires-owner}
  - container: NodeContainer
  - player: Player who opened GUI"
  [prop-spec container player]
  (let [widget (cgui/create-widget :pos [0 0] :size [100 12])
        {:keys [label editable masked requires-owner]} prop-spec
        prop-name (:name prop-spec)
        
        ;; Check if player is owner (for editable fields)
        is-owner (= (container/get-owner container) player)
        can-edit (and editable (or (not requires-owner) is-owner))
        
        ;; Create label
        label-widget (comp/text-box
                       :text (str label ": ")
                       :color 0xCCCCCC
                       :scale 0.7
                       :shadow? true)
        
        ;; Create value display/edit field
        value-widget (if can-edit
                       (comp/text-field
                         :text ""
                         :max-length (:max-length prop-spec 32)
                         :masked masked
                         :on-confirm (fn [new-value]
                                       ;; Send network update
                                       (log/info "Property" prop-name "updated to:" new-value)
                                       ;; TODO: Implement network send
                                       ))
                       (comp/text-box
                         :text ""
                         :color 0xFFFFFF
                         :scale 0.7
                         :shadow? true))]
    
    ;; Add components
    (comp/add-component! widget label-widget)
    (comp/add-component! widget value-widget)
    
    ;; Add update logic to display current value
    (events/on-frame widget
      (fn [_]
        (let [current-value (case (keyword prop-name)
                              :range (str @(:range container) " blocks")
                              :owner @(:owner container)
                              :node_name @(:ssid container)
                              :password (if masked "••••••" @(:password container))
                              "")]
          (if can-edit
            (comp/set-placeholder! value-widget current-value)
            (comp/set-text! value-widget current-value)))))
    
    widget))

(defn create-info-panel
  "Create information panel from XML specification
  
  Shows: histograms + properties"
  [xml-spec container player]
  (let [panel (cgui/create-container :pos [183 5] :size [100 180])
        ;; Extract components from XML
        widget-tree (get xml-spec :widget-tree)
        info-widget (first (filter #(= (:name %) "info_panel") (:children widget-tree)))
        histograms (:histograms info-widget [])
        properties (:properties info-widget [])]
    
    ;; Add histograms
    (doseq [hist-spec histograms]
      (cgui/add-widget! panel (create-histogram-widget hist-spec container)))
    
    ;; Add separator (TODO: implement)
    
    ;; Add properties
    (let [prop-y-start 120]
      (doseq [[idx prop-spec] (map-indexed vector properties)]
        (let [prop-widget (create-property-widget prop-spec container player)]
          (cgui/set-position! prop-widget [0 (+ prop-y-start (* idx 14))])
          (cgui/add-widget! panel prop-widget))))
    
    panel))

;; ============================================================================
;; Wireless Panel
;; ============================================================================

(defn create-wireless-panel
  "Create wireless connection panel (network list + connect/disconnect)
  
  TODO: Implement network discovery and connection logic"
  [xml-spec container]
  (let [panel (cgui/create-container :pos [0 0] :size [176 187])]
    ;; For now, placeholder
    (cgui/add-widget! panel
      (comp/text-box
        :text "Wireless Panel (TODO)"
        :color 0xFFFFFF
        :scale 1.0
        :shadow? true))
    panel))

;; ============================================================================
;; Main GUI Factory
;; ============================================================================

(defn create-node-gui
  "Create Wireless Node GUI from XML layout
  
  Flow:
  1. Load XML layout spec
  2. Create animation system
  3. Create info panel from XML histograms/properties
  4. Create wireless panel
  5. Setup network status poller
  6. Return root widget
  
  Args:
  - container: NodeContainer instance
  - player: Player who opened GUI
  
  Returns: Root CGui widget"
  [container player]
  (let [xml-spec @node-gui-xml-spec
        _ (when-not xml-spec
            (throw (ex-info "Failed to load Node GUI XML spec" {})))
        
        ;; Root widget
        root (cgui/create-container :pos [0 0] :size [176 187])
        
        ;; Create animation state
        anim-state (create-animation-state)
        
        ;; Extract tile entity
        tile (:tile-entity container)
        
        ;; Create network status poller
        poller (create-status-poller tile anim-state)]
    
    ;; Background (from XML)
    (let [bg-texture (get-in xml-spec [:background] "my_mod:textures/gui/node_background.png")]
      (comp/add-component! root
        (comp/texture bg-texture 0 0 176 187)))
    
    ;; Animation area (from XML: 42, 35.5, 186x75, scale 0.5)
    (let [anim-widget (cgui/create-widget :pos [42 35.5] :size [93 37.5])]
      ;; Frame update
      (events/on-frame anim-widget
        (fn [_]
          (update-animation! anim-state)
          ((:update-fn poller)) ; Update network status
          (render-animation-frame! anim-state anim-widget)))
      (cgui/add-widget! root anim-widget))
    
    ;; Info panel (right side)
    (cgui/add-widget! root (create-info-panel xml-spec container player))
    
    ;; Wireless panel (separate page, initially hidden)
    (let [wireless-widget (create-wireless-panel xml-spec container)]
      (cgui/set-visible! wireless-widget false)
      (cgui/add-widget! root wireless-widget))
    
    ;; TODO: Page switching buttons
    
    (log/info "Created Wireless Node GUI from XML layout")
    root))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-node-gui
  "Open Wireless Node GUI for player
  
  This is called from the platform bridge when GUI is requested"
  [container player]
  (create-node-gui container player))

;; ============================================================================
;; Example Usage
;; ============================================================================

(comment
  ;; Check if XML spec loaded successfully
  (some? @node-gui-xml-spec)
  ; => true (if XML file exists and is valid)
  
  ;; Inspect XML spec structure
  (keys @node-gui-xml-spec)
  ; => (:id :title :width :height :background :slots :buttons :labels :widget-tree)
  
  ;; Check widget tree
  (get-in @node-gui-xml-spec [:widget-tree :children])
  ; => Vector of child widgets (ui_inventory, ui_node, slots, anim_area, info_panel, wireless_panel, etc.)
  
  ;; Create GUI (requires container and player)
  ;; (def gui (create-node-gui container player))
  )
