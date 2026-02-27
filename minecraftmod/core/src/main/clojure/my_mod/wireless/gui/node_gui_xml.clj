(ns my-mod.wireless.gui.node-gui-xml
  "Wireless Node GUI - TechUI implementation (参照Scala GuiNode)
  
  Architecture:
  - Inventory page from shared TechUI builder
  - InfoArea (histogram + properties)
  - Wireless page (network list + connect/disconnect)
  - Animated node status indicator
  
  NOTE:
  - No XML parsing
  - Uses existing resources only"
  (:require [my-mod.gui.cgui :as cgui]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log])
  (:import [net.minecraft.entity.player EntityPlayer]))

;; ============================================================================
;; Network Message IDs (match node_network_handler.clj)
;; ============================================================================

(def ^:const MSG_GET_STATUS "wireless_node_get_status")
(def ^:const MSG_CHANGE_NAME "wireless_node_change_name")
(def ^:const MSG_CHANGE_PASSWORD "wireless_node_change_password")
(def ^:const MSG_LIST_NETWORKS "wireless_node_list_networks")
(def ^:const MSG_CONNECT "wireless_node_connect")
(def ^:const MSG_DISCONNECT "wireless_node_disconnect")

;; ============================================================================
;; GUI Dimensions (shared)
;; ============================================================================

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Animation System (Node status)
;; ============================================================================

(defn create-animation-state
  "Create animation state for node indicator"
  []
  {:current-state (atom :unlinked)
   :current-frame (atom 0)
   :last-update (atom (System/currentTimeMillis))})

(defn get-animation-config
  "Get animation config for current state"
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
        {:keys [frames frame-time]} (get-animation-config @current-state)]
    (when (>= dt frame-time)
      (swap! current-frame #(mod (inc %) frames))
      (reset! last-update now))))

(defn render-animation-frame!
  "Render current animation frame (10-frame vertical sprite)"
  [anim-state widget]
  (let [{:keys [current-state current-frame]} anim-state
        config (get-animation-config @current-state)
        absolute-frame (+ (:begin config) @current-frame)
        total-frames 10
        u0 0.0
        v0 (/ (double absolute-frame) total-frames)
        u1 1.0
        v1 (/ 1.0 total-frames)]
    (comp/render-texture-region
      widget
      "my_mod:textures/guis/effect/effect_node.png"
      0 0 186 75
      u0 v0 u1 v1)))

(defn create-status-poller
  "Create status poller to query link state every 2 seconds"
  [tile anim-state]
  (let [last-query (atom (- (System/currentTimeMillis) 3000))]
    {:last-query last-query
     :update-fn (fn []
                  (let [now (System/currentTimeMillis)
                        dt (- now @last-query)]
                    (when (> dt 2000)
                      (reset! last-query now)
                      (net-client/send-to-server
                        MSG_GET_STATUS
                        (net-helpers/tile-pos-payload tile)
                        (fn [response]
                          (let [is-linked (boolean (:linked response))]
                            (reset! (:current-state anim-state)
                                    (if is-linked :linked :unlinked))))))))}))

;; ============================================================================
;; Wireless Page (network list + connect)
;; ============================================================================

(defn create-wireless-panel
  "Create wireless connection panel (list + connect/disconnect)"
  [container]
  (let [panel (cgui/create-container :pos [0 0] :size [gui-width gui-height])
        networks (atom [])
        selected (atom nil)
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
                            :color 0xFFFFFFFF
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
                                        (net-client/send-to-server
                                          MSG_CONNECT
                                          (assoc (net-helpers/tile-pos-payload (:tile-entity container))
                                                 :ssid @selected
                                                 :password @input-password)))) )
            btn-disconnect (comp/button
                             :text "Disconnect"
                             :x 120 :y 40 :width 48 :height 12
                             :on-click (fn []
                                         (net-client/send-to-server
                                           MSG_DISCONNECT
                                           (net-helpers/tile-pos-payload (:tile-entity container)))))
            notice-widget (cgui/create-widget :pos [13 170] :size [150 14])
            notice-label (comp/text-box
                           :text "Note: Only open networks supported"
                           :color 0xFFFFAA00
                           :scale 0.6
                           :shadow? true)]

        (cgui/add-widget! panel btn-refresh)
        (cgui/add-widget! panel btn-connect)
        (cgui/add-widget! panel btn-disconnect)
        (comp/add-component! notice-widget notice-label)
        (cgui/add-widget! panel notice-widget))

      (cgui/add-widget! panel list-widget)
      (query-networks))

    panel))

;; ============================================================================
;; InfoArea Builder (TechUI)
;; ============================================================================

(defn build-info-area!
  "Build InfoArea for Node GUI
  
  Args:
  - info-area: InfoArea widget
  - container: NodeContainer
  - player: EntityPlayer"
  [info-area container player]
  (try
    (let [tile (:tile-entity container)
          owner-name (node-container/get-owner container)
          is-owner? (= owner-name (.getName player))]

      (cgui/clear-widgets! info-area)

      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-energy
                   (fn [] @(:energy container))
                   @(:max-energy container))
                 (tech-ui/hist-capacity
                   (fn [] @(:capacity container))
                   (max 1 @(:max-capacity container)))]
                10)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "Range"
                                    (fn [] (str (try (.getRange tile) (catch Exception _ 0.0))))
                                    y)
            y (tech-ui/add-property info-area "Owner" owner-name y)]

        (if is-owner?
          (let [y (tech-ui/add-property
                    info-area "Node Name" @(:ssid container) y
                    :editable? true
                    :on-change (fn [new-name]
                                (net-client/send-to-server
                                  MSG_CHANGE_NAME
                                  (assoc (net-helpers/tile-pos-payload tile)
                                         :node-name new-name))))
                y (tech-ui/add-property
                    info-area "Password" @(:password container) y
                    :editable? true
                    :masked? true
                    :on-change (fn [new-pass]
                                (net-client/send-to-server
                                  MSG_CHANGE_PASSWORD
                                  (assoc (net-helpers/tile-pos-payload tile)
                                         :password new-pass))))]
            y)
          (tech-ui/add-property info-area "Node Name" @(:ssid container) y))))
    (catch Exception e
      (log/error "Error building info area:" (.getMessage e)))))

;; ============================================================================
;; Main GUI Factory
;; ============================================================================

(defn create-node-gui
  "Create Wireless Node GUI (TechUI)
  
  Args:
  - container: NodeContainer
  - player: EntityPlayer
  
  Returns: Root CGui widget"
  [container player]
  (try
    (let [tile (:tile-entity container)
          inv-page (tech-ui/create-inventory-page "node")
          main-widget (cgui/create-container :pos [-18 0] :size [gui-width gui-height])
          info-area (tech-ui/create-info-area)
          anim-state (create-animation-state)
          poller (create-status-poller tile anim-state)
          anim-widget (cgui/create-widget :pos [42 35.5] :size [93 37.5])
          wireless-panel (create-wireless-panel container)
          show-info! (fn []
                       (cgui/set-visible! info-area true)
                       (cgui/set-visible! wireless-panel false))
          show-wireless! (fn []
                           (cgui/set-visible! info-area false)
                           (cgui/set-visible! wireless-panel true))]

      ;; Add inventory page window
      (cgui/add-widget! main-widget (:window inv-page))

      ;; Animation area
      (events/on-frame anim-widget
        (fn [_]
          (update-animation! anim-state)
          ((:update-fn poller))
          (render-animation-frame! anim-state anim-widget)))
      (cgui/add-widget! (:window inv-page) anim-widget)

      ;; Position and build info area
      (cgui/set-position! info-area (+ (cgui/get-width main-widget) 7) 5)
      (build-info-area! info-area container player)
      (cgui/add-widget! main-widget info-area)

      ;; Wireless panel page (hidden by default)
      (cgui/set-visible! wireless-panel false)
      (cgui/add-widget! main-widget wireless-panel)

      ;; Page switching buttons
      (let [btn-info (comp/button :text "Info" :x 8 :y 172 :width 40 :height 12
                                  :on-click show-info!)
            btn-wireless (comp/button :text "Wireless" :x 52 :y 172 :width 60 :height 12
                                      :on-click show-wireless!)]
        (cgui/add-widget! main-widget btn-info)
        (cgui/add-widget! main-widget btn-wireless))

      (log/info "Created Wireless Node GUI (TechUI)")
      main-widget)
    (catch Exception e
      (log/error "Error creating Node GUI:" (.getMessage e))
      (throw e))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Node GUI"
  [container minecraft-container player]
  (let [root (create-node-gui container player)]
    (cgui/create-cgui-screen-container root minecraft-container)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-node-gui
  "Open Wireless Node GUI for player"
  [container player]
  (create-node-gui container player))

(defn init!
  "Initialize Node GUI module"
  []
  (log/info "Wireless Node GUI module initialized"))
