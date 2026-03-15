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
            [my-mod.gui.platform-adapter :as gui]
            [my-mod.gui.tabbed-gui :as tabbed-gui]
            [my-mod.gui.tech-ui-common :as tech-ui]
            [my-mod.network.client :as net-client]
            [my-mod.wireless.gui.node-messages :as node-msgs]
            [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.network-handler-helpers :as net-helpers]
            [my-mod.util.log :as log]))

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
  "Render current animation frame (10-frame vertical sprite).
   Texture has 10 frames stacked vertically; each frame is 1/10 of texture height.
   UV: (0, frame/10) to (1, frame/10 + 1/10)."
  [anim-state widget]
  (let [{:keys [current-state current-frame]} anim-state
        config (get-animation-config @current-state)
        absolute-frame (+ (:begin config) @current-frame)
        total-frames 10
        u0 0.0
        v0 (/ (double absolute-frame) total-frames)
        u1 1.0
        ;; v1 must be v0 + 1/10 so stored uv height is 1/10 (one frame), not 0.1 - v0
        v1 (+ v0 (/ 1.0 total-frames))]
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
                        (node-msgs/msg :get-status)
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
                (node-msgs/msg :get-status)
                payload
                (fn [response]
                  (update-connected! (boolean (:linked response))))))

            (connect-network! [ssid pass]
              (net-client/send-to-server
                (node-msgs/msg :connect)
                (assoc payload :ssid ssid :password pass)
                (fn [_]
                  (query-networks!)
                  (query-linked!))))

            (disconnect-network! []
              (net-client/send-to-server
                (node-msgs/msg :disconnect)
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
                (node-msgs/msg :list-networks)
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
                                  (node-msgs/msg :change-name)
                                  (assoc (net-helpers/tile-pos-payload tile)
                                         :node-name new-name))))
                y (tech-ui/add-property
                    info-area "Password" @(:password container) y
                    :editable? true
                    :masked? true
                    :on-change (fn [new-pass]
                                (net-client/send-to-server
                                  (node-msgs/msg :change-password)
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
  - opts: optional map, e.g. {:menu AbstractContainerMenu} (menu passed from screen factory)
  
  Returns: Root CGui widget"
  [container player & [opts]]
  (try
    (let [tile (:tile-entity container)
          inv-page (tech-ui/create-inventory-page "node")
          ;; Use the inventory page window as the size reference so that the
          ;; wrapper container matches the XML layout (176x187) instead of
          ;; the smaller TechUI logical width. This prevents the background
          ;; from appearing zoomed or clipped.
          inv-window (:window inv-page)
          inv-w (cgui/get-width inv-window)
          inv-h (cgui/get-height inv-window)
          info-area (tech-ui/create-info-area)
          anim-state (create-animation-state)
          poller (create-status-poller tile anim-state)
          anim-widget (cgui/create-widget :pos [42 35.5] :size [186, 75] :scale 0.5)
          wireless-panel (create-wireless-panel container)
          pages [inv-page {:id "wireless" :window wireless-panel}]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          ;; Sync tab to server and set client-side tab by container-id so menu.clicked() blocks slot packets on non-inv tab
          on-tab-change (fn [page-id]
                          (when-let [idx (tabbed-gui/page-id->index pages page-id)]
                            (when (tabbed-gui/tabbed-container? container)
                              (reset! (:tab-index container) (int idx)))
                            (when (integer? container-id)
                              (tabbed-gui/set-tab-index-by-container-id! container-id (int idx)))
                            (tabbed-gui/send-set-tab! idx container-id)))
          ;; Compose tech UI from pages (inventory, info, wireless)
          tech-ui (tech-ui/create-tech-ui inv-page {:id "wireless" :window wireless-panel}
                                         {:on-tab-change on-tab-change})
          ;tech-ui (tech-ui/create-tech-ui)
          main-widget (:window tech-ui)
          ;show-info! (fn [] ((:show-page-fn tech-ui) "info"))
          ;show-wireless! (fn [] ((:show-page-fn tech-ui) "wireless"))
          ]
      
      ;; Animation area: add anim widget into inventory window so it draws above
      (events/on-frame anim-widget
                       (fn [_]
                         (update-animation! anim-state)
                         ((:update-fn poller))
                         (render-animation-frame! anim-state anim-widget)))
      (cgui/add-widget! (:window inv-page) anim-widget)

      ;; Position and build info area (create-tech-ui has already attached it,
      ;; but we need to position and populate it)
      (cgui/set-position! info-area (+ (cgui/get-width (:window inv-page)) 7) 5)
      (build-info-area! info-area container player)

      ;(cgui/add-widget! main-widget info-area)
      
      (log/info "Created Wireless Node GUI (TechUI)")
      ;; When opts has :menu (screen path), return map so screen can block slot clicks when tab != inv
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Node GUI:" (.getMessage e))
      (throw e))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-screen
  "Create CGuiScreenContainer for Node GUI.
   minecraft-container is the AbstractContainerMenu (menu); passed as opts for tabbed GUI sync.
   Passes :current-tab-atom so client can block slot clicks when not on inv tab."
  [container minecraft-container player]
  (let [gui (create-node-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (assoc base :current-tab-atom (:current gui))
      base)))

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
