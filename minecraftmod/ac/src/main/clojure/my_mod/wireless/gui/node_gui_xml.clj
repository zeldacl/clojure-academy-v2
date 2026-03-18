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
            [my-mod.wireless.gui.wireless-tab :as wireless-tab]
            [my-mod.platform.entity :as entity]
            [my-mod.util.log :as log])
  (:import [my_mod.api.wireless IWirelessNode]))

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

(defn create-anim-widget
  "Create animation widget, poller and attach frame handler.

    Args:
    - tile: tile entity used by status poller
    - target-window: the window widget to which the anim widget will be added
    - opts: optional map {:pos [x y] :size [w h] :scale s}

    Returns: map {:widget widget :anim-state anim-state :poller poller}
    "
  [tile & [opts]]
  (let [opts (or opts {})
        pos (get opts :pos [42 35.5])
        size (get opts :size [186 75])
        scale (get opts :scale 0.5)
        anim-state (create-animation-state)
        poller (create-status-poller tile anim-state)
        widget (apply cgui/create-widget
                      (concat [:pos pos :size size]
                              (when scale [:scale scale])))]
    ;; attach per-frame update: animation + poller + render
    (events/on-frame widget
                     (fn [_]
                       (update-animation! anim-state)
                       ((:update-fn poller))
                       (render-animation-frame! anim-state widget)))
    {:widget widget :anim-state anim-state :poller poller}))

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
  "Shared wireless tab (node mode)."
  [container]
  (wireless-tab/create-wireless-panel {:mode :node :container container}))

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
          is-owner? (= owner-name (entity/player-get-name player))]

      (tech-ui/reset-info-area! info-area)

      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-energy
                   (fn [] @(:energy container))
                   @(:max-energy container))
                 (tech-ui/hist-capacity
                   (fn [] @(:capacity container))
                   (max 1 @(:max-capacity container)))]
                0)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "Range"
                                    (fn [] (str (try (.getRange ^IWirelessNode tile) (catch Exception _ 0.0))))
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
          info-area (tech-ui/create-info-area)
          ;; create animation widget (includes anim-state and poller)
          {:keys [widget]} (create-anim-widget tile)
          anim-widget widget
          wireless-panel (create-wireless-panel container)
          pages [inv-page {:id "wireless" :window wireless-panel}]
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          ;; Compose tech UI from pages (inventory, info, wireless)
          tech-ui (apply tech-ui/create-tech-ui pages)
          ;; Attach generic tab-change sync (pages sequence, tech-ui map, container, container-id)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          ;tech-ui (tech-ui/create-tech-ui)
          main-widget (:window tech-ui)
          ;show-info! (fn [] ((:show-page-fn tech-ui) "info"))
          ;show-wireless! (fn [] ((:show-page-fn tech-ui) "wireless"))
          ]
      
      (cgui/add-widget! inv-window anim-widget)

      ;; Position and build info area (create-tech-ui has already attached it,
      ;; but we need to position and populate it)
      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (build-info-area! info-area container player)

      (cgui/add-widget! main-widget info-area)
      
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
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
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
