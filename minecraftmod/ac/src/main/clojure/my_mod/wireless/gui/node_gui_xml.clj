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
            [my-mod.gui.cgui-document :as cgui-doc]
            [my-mod.config.modid :as modid]
            [my-mod.gui.components :as comp]
            [my-mod.gui.events :as events]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

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
      (modid/asset-path "textures" "guis/effect/effect_node.png")
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

(defn- widget-textbox
  [widget]
  (comp/get-textbox-component widget))

(defn- widget-drawtexture
  [widget]
  (comp/get-drawtexture-component widget))

(defn- set-textbox-text!
  [widget text]
  (when-let [tb (widget-textbox widget)]
    (comp/set-text! tb text)))

(defn- set-drawtexture!
  [widget texture-path]
  (when-let [dt (widget-drawtexture widget)]
    (comp/set-texture! dt texture-path)))

(defn create-wireless-panel
  "Create wireless connection panel from page_wireless.xml (Scala WirelessPage style)"
  [container]
  (let [doc (cgui-doc/read-xml (modid/namespaced-path "guis/rework/page_wireless.xml"))
        root (cgui-doc/get-widget doc "main")
        wlist (cgui/find-widget root "panel_wireless/zone_elementlist")
        elem-template (cgui/find-widget root "panel_wireless/zone_elementlist/element")
        connected-elem (cgui/find-widget root "panel_wireless/elem_connected")
        btn-up (cgui/find-widget root "panel_wireless/btn_arrowup")
        btn-down (cgui/find-widget root "panel_wireless/btn_arrowdown")
        elist (comp/element-list :spacing 2)
        payload (net-helpers/tile-pos-payload (:tile-entity container))]

    (when wlist
      (comp/add-component! wlist elist))

    (when elem-template
      (cgui/set-visible! elem-template false))

    (when (and btn-up elist)
      (events/on-left-click btn-up (fn [_] (comp/list-progress-last! elist))))
    (when (and btn-down elist)
      (events/on-left-click btn-down (fn [_] (comp/list-progress-next! elist))))

    (letfn [(update-connected! [linked?]
              (when connected-elem
                (let [icon-connect (cgui/find-widget connected-elem "icon_connect")
                      text-name (cgui/find-widget connected-elem "text_name")]
                  (set-textbox-text! text-name (if linked? "Connected" "Not Connected"))
                  (set-drawtexture! icon-connect
                    (if linked?
                      (modid/asset-path "textures" "guis/icons/icon_connected.png")
                      (modid/asset-path "textures" "guis/icons/icon_unconnected.png"))))))

            (query-linked! []
              (net-client/send-to-server
                MSG_GET_STATUS
                payload
                (fn [response]
                  (update-connected! (boolean (:linked response))))))

            (connect-network! [ssid pass]
              (net-client/send-to-server
                MSG_CONNECT
                (assoc payload :ssid ssid :password pass)
                (fn [_]
                  (query-networks!)
                  (query-linked!))))

            (disconnect-network! []
              (net-client/send-to-server
                MSG_DISCONNECT
                payload
                (fn [_]
                  (query-networks!)
                  (query-linked!))))

            (build-element! [net]
              (when elem-template
                (let [elem (cgui/copy-widget elem-template)
                      ssid (:ssid net)
                      encrypted? (boolean (:is-encrypted? net))
                      text-name (cgui/find-widget elem "text_name")
                      icon-key (cgui/find-widget elem "icon_key")
                      input-pass (cgui/find-widget elem "input_pass")
                      icon-connect (cgui/find-widget elem "icon_connect")
                      pass-box (when input-pass (widget-textbox input-pass))]

                  (set-textbox-text! text-name (str ssid))

                  (if encrypted?
                    (do
                      (when icon-key (cgui/set-visible! icon-key true))
                      (when input-pass (cgui/set-visible! input-pass true)))
                    (do
                      (when icon-key (cgui/set-visible! icon-key false))
                      (when input-pass (cgui/set-visible! input-pass false))))

                  (when icon-connect
                    (events/on-left-click icon-connect
                      (fn [_]
                        (let [pwd (if (and encrypted? pass-box)
                                    (comp/get-text pass-box)
                                    "")]
                          (connect-network! ssid pwd)
                          (when pass-box
                            (comp/set-text! pass-box ""))))))

                  (when elist
                    (comp/list-add! elist elem)))))

            (rebuild-list! [nets]
              (when elist
                (comp/list-clear! elist)
                (doseq [net nets]
                  (build-element! net))))

            (query-networks! []
              (net-client/send-to-server
                MSG_LIST_NETWORKS
                payload
                (fn [response]
                  (rebuild-list! (vec (:networks response []))))))]

      (when connected-elem
        (let [icon-connect (cgui/find-widget connected-elem "icon_connect")]
          (when icon-connect
            (events/on-left-click icon-connect (fn [_] (disconnect-network!))))))

      (query-networks!)
      (query-linked!))

    root))

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
