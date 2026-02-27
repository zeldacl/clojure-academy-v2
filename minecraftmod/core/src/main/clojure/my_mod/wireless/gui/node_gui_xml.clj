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
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.node-container :as container]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

;; =========================================================================
;; Network Message IDs
;; =========================================================================

(def ^:const MSG_GET_STATUS "wireless_node_get_status")
(def ^:const MSG_CHANGE_NAME "wireless_node_change_name")
(def ^:const MSG_CHANGE_PASSWORD "wireless_node_change_password")
(def ^:const MSG_LIST_NETWORKS "wireless_node_list_networks")
(def ^:const MSG_CONNECT "wireless_node_connect")
(def ^:const MSG_DISCONNECT "wireless_node_disconnect")

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
                      (net-client/send-to-server
                        MSG_GET_STATUS
                        (net-helpers/tile-pos-payload tile)
                        (fn [response]
                          (let [is-linked (boolean (:linked response))]
                            (reset! (:current-state anim-state)
                                    (if is-linked :linked :unlinked))))))))}))

;; ============================================================================
;; Info Panel Components
;; ============================================================================

(defn create-histogram-widget
  "Create histogram widget from XML specification
  
  Uses comp/histogram for cleaner implementation with automatic updates.
  
  Args:
  - hist-spec: Histogram spec from XML {:name :label :type :color :y :height}
  - container: NodeContainer for data access"
  [hist-spec container]
  (let [{:keys [label type color y height]} hist-spec
        [value-fn max-fn] (case type
                            :energy [(fn [] @(:energy container))
                                     (fn [] @(:max-energy container))]
                            :capacity [(fn [] @(:capacity container))
                                       (fn [] @(:max-capacity container))]
                            [(fn [] 0) (fn [] 1)])]
    (comp/histogram
      :label label
      :x 0
      :y (or y 0)
      :width 100
      :height (or height 40)
      :color color
      :value-fn value-fn
      :max-fn max-fn
      :direction :horizontal)))

(defn create-property-widget
  "Create property widget from XML specification
  
  Simplified version using declarative value mapping.
  
  Args:
  - prop-spec: Property spec from XML {:name :label :editable :masked :max-length :requires-owner}
  - container: NodeContainer
  - player: Player who opened GUI"
  [prop-spec container player]
  (let [{:keys [label editable masked requires-owner]} prop-spec
        prop-name (:name prop-spec)
        
        ;; Check if player is owner (for editable fields)
        player-name (try (.getName player) (catch Exception _ (str player)))
        is-owner (= (container/get-owner container) player-name)
        can-edit (and editable (or (not requires-owner) is-owner))
        
        ;; Value accessor based on property name
        get-value (fn []
                    (case (keyword prop-name)
                      :range (str @(:range container) " blocks")
                      :owner @(:owner container)
                      :node_name @(:ssid container)
                      :password (if masked "••••••" @(:password container))
                      ""))
        
        ;; Change handler for editable fields
        on-change (when can-edit
                    (fn [new-value]
                      (case (keyword prop-name)
                        :node_name
                        (net-client/send-to-server
                          MSG_CHANGE_NAME
                          (assoc (net-helpers/tile-pos-payload (:tile-entity container))
                                 :node-name new-value))
                        
                        :password
                        (net-client/send-to-server
                          MSG_CHANGE_PASSWORD
                          (assoc (net-helpers/tile-pos-payload (:tile-entity container))
                                 :password new-value))
                        
                        nil)))]
    
    ;; Use comp/property-field for cleaner implementation
    (comp/property-field
      :label label
      :value-fn get-value
      :editable can-edit
      :masked masked
      :max-length (:max-length prop-spec 32)
      :on-change on-change)))

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

    ;; Add separator
    (let [sep-widget (cgui/create-widget :pos [0 110] :size [96 1])]
      (comp/add-component! sep-widget (comp/outline :color 0x505050 :width 1.0))
      (cgui/add-widget! panel sep-widget))
    
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
  
  TODO: Add proper password input UI component"
  [xml-spec container]
  (let [panel (cgui/create-container :pos [0 0] :size [176 187])
        networks (atom [])
        selected (atom nil)
        ;; TODO: Replace with actual text input component when available
        ;; For now, use empty password for connection attempts
        input-password (atom "")
        list-widget (cgui/create-widget :pos [13 50] :size [150 120])
        list-comp (comp/element-list :spacing 2)]

    (comp/add-component! list-widget list-comp)

    (let [refresh-list
          (fn []
            (comp/list-clear! list-comp)
            (doseq [net @networks]
              (let [ssid (:ssid net)
                    load (:load net)
                    item (cgui/create-widget :pos [0 0] :size [140 18])
                    label (comp/text-box
                            :text (str ssid " (" load ")")
                            :color 0xFFFFFF
                            :scale 0.7
                            :shadow? true)]
                (comp/add-component! item label)
                (events/on-left-click item
                  (events/make-click-handler
                    (fn [] (reset! selected ssid))))
                (comp/list-add! list-comp item))))

          query-networks
          (fn []
            (net-client/send-to-server
              MSG_LIST_NETWORKS
              (net-helpers/tile-pos-payload (:tile-entity container))
              (fn [response]
                (reset! networks (vec (:networks response [])))
                (refresh-list))))]

      (let [btn-refresh (comp/button
                          :text "Refresh"
                          :x 120 :y 10 :width 48 :height 12
                          :on-click query-networks)
            btn-connect (comp/button
                          :text "Connect"
                          :x 120 :y 25 :width 48 :height 12
                          :on-click (fn []
                                      (when @selected
                                        ;; TODO: Use password from text input UI when implemented
                                        ;; Currently uses empty password (open networks only)
                                        (net-client/send-to-server
                                          MSG_CONNECT
                                          (assoc (net-helpers/tile-pos-payload (:tile-entity container))
                                                 :ssid @selected
                                                 :password @input-password)))))
            btn-disconnect (comp/button
                             :text "Disconnect"
                             :x 120 :y 40 :width 48 :height 12
                             :on-click (fn []
                                         (net-client/send-to-server
                                           MSG_DISCONNECT
                                           (net-helpers/tile-pos-payload (:tile-entity container)))))]
        (cgui/add-widget! panel btn-refresh)
        (cgui/add-widget! panel btn-connect)
        (cgui/add-widget! panel btn-disconnect)
        
        ;; Add password notice for encrypted networks
        (let [notice-widget (cgui/create-widget :pos [13 170] :size [150 14])
              notice-label (comp/text-box
                             :text "Note: Only open networks supported"
                             :color 0xFFAA00
                             :scale 0.6
                             :shadow? true)]
          (comp/add-component! notice-widget notice-label)
          (cgui/add-widget! panel notice-widget)))

      (cgui/add-widget! panel list-widget)

      ;; Initial query
      (query-networks))

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
    (let [info-panel (create-info-panel xml-spec container player)]
      (cgui/add-widget! root info-panel)
    
      ;; Wireless panel (separate page, initially hidden)
      (let [wireless-widget (create-wireless-panel xml-spec container)
        show-info! (fn []
             (cgui/set-visible! info-panel true)
             (cgui/set-visible! wireless-widget false))
        show-wireless! (fn []
                 (cgui/set-visible! info-panel false)
                 (cgui/set-visible! wireless-widget true))]
        (cgui/set-visible! wireless-widget false)
        (cgui/add-widget! root wireless-widget)

        ;; Page switching buttons
        (let [btn-info (comp/button :text "Info" :x 8 :y 172 :width 40 :height 12
                :on-click show-info!)
          btn-wireless (comp/button :text "Wireless" :x 52 :y 172 :width 60 :height 12
                    :on-click show-wireless!)]
          (cgui/add-widget! root btn-info)
          (cgui/add-widget! root btn-wireless))))
    
    (log/info "Created Wireless Node GUI from XML layout")
    root))

;; ============================================================================
;; GUI Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Node GUI (XML)
  
  Args:
  - container: NodeContainer instance
  - minecraft-container: Minecraft Container object
  - player: Player who opened GUI
  
  Returns: CGuiScreenContainer instance"
  [container minecraft-container player]
  (let [root (create-node-gui container player)]
    (cgui/create-cgui-screen-container root minecraft-container)))

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
