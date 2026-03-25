(ns cn.li.ac.block.wireless-node.gui
  "CLIENT-ONLY: Wireless Node GUI implementation.

  This file contains:
  - GUI layout and component builders
  - Client-side network message senders
  - GUI interaction logic
  - Container atom management (generated from schema)

  Must be loaded via side-checked requiring-resolve from platform layer.

  Architecture:
  - Inventory page from shared TechUI builder
  - InfoArea (histogram + properties)
  - Wireless page (network list + connect/disconnect)
  - Animated node status indicator"
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.cgui-document :as cgui-doc]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.ac.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.ac.wireless.gui.wireless-tab :as wireless-tab]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.wireless.gui.container-common :as common]
            [cn.li.ac.wireless.gui.container-move-common :as move-common]
            [cn.li.ac.wireless.gui.container-schema :as schema]
            [cn.li.ac.wireless.gui.sync-helpers :as sync-helpers]
            [cn.li.ac.wireless.gui.gui-metadata :as metadata]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessNode]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def wireless-node-id :wireless-node)

(def wireless-node-slot-schema
  (slot-schema/register-slot-schema!
    {:schema-id wireless-node-id
     :slots [{:id :input :type :energy :x 42 :y 10}
             {:id :output :type :output :x 42 :y 80}]}))

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def wireless-node-quick-move-config
  (slot-schema/build-quick-move-config
    wireless-node-id
    {:inventory-pred inventory-pred
     :rules [{:accept? energy-stub/is-energy-item-supported?
              :slot-ids [:input]}]}))

(defn- msg
  "Generate message ID for node actions."
  [action]
  (str "wireless_node_" (name action)))

;; ============================================================================
;; GUI Dimensions (shared)
;; ============================================================================

;; Note: gui-width and gui-height removed - use tech-ui/gui-width directly if needed

;; ============================================================================
;; Schema-Based Generation (Client-Side)
;; ============================================================================

(defn- build-gui-atoms
  "Generate GUI container atoms from unified schema."
  [schema tile]
  (let [state (or (platform-be/get-custom-state tile) {})]
    (into {}
      (for [field schema
            :when (or (:gui-sync? field) (:gui-only? field))
            :let [k (:gui-container-key field (:key field))
                  init-fn (or (:gui-init field)
                              (fn [s] (get s (:key field) (:default field))))]]
        [k (atom (init-fn state))]))))

(defn- build-sync-to-client-fn
  "Generate sync-to-client! function from schema."
  [schema]
  (fn [container]
    (let [tile (:tile-entity container)
          state (or (common/get-tile-state tile) {})]
      ;; Reset gui-sync? atoms only if value changed
      (doseq [field schema
              :when (:gui-sync? field)
              :let [container-key (:gui-container-key field (:key field))
                    state-key (:key field)
                    coerce-fn (or (:gui-coerce field) identity)
                    value (get state state-key (:default field))]]
        (when-let [atom-ref (get container container-key)]
          (let [new-val (coerce-fn value)]
            (when (not= @atom-ref new-val)
              (reset! atom-ref new-val)))))
      ;; Handle special sync logic (throttled queries, etc.)
      (when-let [ticker (:sync-ticker container)]
        (sync-helpers/with-throttled-sync! ticker 100
          (fn [] (sync-helpers/query-node-network-capacity! container))))
      ;; Calculate transfer-rate from charging flags
      (when-let [rate-atom (:transfer-rate container)]
        (let [rate (cond
                     (and (:charging-in state) (:charging-out state)) 200
                     (:charging-in state) 100
                     (:charging-out state) 100
                     :else 0)]
          (reset! rate-atom rate))))))

(defn- build-get-sync-data-fn
  "Generate get-sync-data function from schema."
  [schema]
  (fn [container]
    (into {}
      (for [field schema
            :when (:gui-sync? field)
            :let [k (:gui-container-key field (:key field))]]
        [k (when-let [a (get container k)] @a)]))))

(defn- build-apply-sync-data-fn
  "Generate apply-sync-data! function from schema."
  [schema]
  (fn [container data]
    (doseq [field schema
            :when (:gui-sync? field)
            :let [k (:gui-container-key field (:key field))
                  coerce-fn (or (:gui-coerce field) identity)]]
      (when-let [atom-ref (get container k)]
        (when (contains? data k)
          (reset! atom-ref (coerce-fn (get data k))))))))

(defn- build-on-close-fn
  "Generate on-close function from schema."
  [schema]
  (fn [container]
    (doseq [field schema
            :when (contains? field :gui-close-reset)
            :let [k (:gui-container-key field (:key field))
                  reset-val (:gui-close-reset field)]]
      (when-let [atom-ref (get container k)]
        (reset! atom-ref reset-val)))))

;; Generate from schema
(defn sync-field-mappings []
  (into {}
    (for [field node-schema/unified-node-schema
          :when (:gui-sync? field)
          :let [container-key (:gui-container-key field (:key field))
                payload-key (:gui-payload-key field (:key field))]]
      [payload-key container-key])))

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
                        (msg :get-status)
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
;; Container Creation (from node_container.clj)
;; ============================================================================

(defn- resolve-state [tile]
  (if (map? tile)
    [nil tile]
    (try
      [tile (or (platform-be/get-custom-state tile) {})]
      (catch Exception e
        (log/warn "Could not resolve customState from BE:"(ex-message e))
        [tile {}]))))

(defn create-container [tile player]
  (let [[be state] (resolve-state tile)
        entity     (or be tile)]
    (merge {:tile-entity    entity
            :player         player
            :container-type :node}
           (build-gui-atoms node-schema/unified-node-schema entity))))

;; ============================================================================
;; Slot Management (from node_container.clj)
;; ============================================================================

(def ^:private node-slot-schema-id wireless-node-id)

(defn- tile-state [tile] (common/get-tile-state tile))

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count node-slot-schema-id))

(defn get-owner [container]
  (let [tile (:tile-entity container)]
    (:placer-name (tile-state tile))))

(defn can-place-item? [_container slot-index item-stack]
  (case (slot-schema/slot-type node-slot-schema-id slot-index)
    :energy (energy-stub/is-energy-item-supported? item-stack)
    :output false
    false))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed! [_container slot-index]
  (log/info "Node container slot" slot-index "changed"))

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
;; Container Sync (from node_container.clj)
;; ============================================================================

;; Generated from schema
(def sync-to-client! (build-sync-to-client-fn node-schema/unified-node-schema))
(def get-sync-data (build-get-sync-data-fn node-schema/unified-node-schema))
(def apply-sync-data! (build-apply-sync-data-fn node-schema/unified-node-schema))

(defn still-valid? [container player]
  (common/still-valid? container player))

(defn tick! [container]
  (sync-to-client! container)
  (swap! (:charge-ticker container) inc))

(defn handle-button-click! [container button-id data]
  (let [tile (:tile-entity container)]
    (when-not (map? tile)
      (case (int button-id)
        0 (let [state  (or (platform-be/get-custom-state tile) {})
                state' (update state :enabled not)]
            (platform-be/set-custom-state! tile state')
            (log/info "Toggled node connection:" (:enabled state')))
        1 (when-let [new-ssid (:ssid data)]
            (let [state  (or (platform-be/get-custom-state tile) {})
                  state' (assoc state :node-name new-ssid)]
              (platform-be/set-custom-state! tile state')
              (log/info "Set node SSID to:" new-ssid)))
        2 (when-let [new-password (:password data)]
            (let [state  (or (platform-be/get-custom-state tile) {})
                  state' (assoc state :password new-password)]
              (platform-be/set-custom-state! tile state')
              (log/info "Set node password")))
        (log/warn "Unknown button ID:" button-id)))))

(defn quick-move-stack [container slot-index player-inventory-start]
  (move-common/quick-move-with-rules
    container slot-index player-inventory-start
    wireless-node-quick-move-config))

(defn on-close [container]
  (log/debug "Closing wireless node container")
  ((build-on-close-fn node-schema/unified-node-schema) container))

;; ============================================================================
;; Sync Packet Handling (from node_sync.clj)
;; ============================================================================

(defn broadcast-node-state [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "node"))

(defn make-sync-packet [source]
  (let [container? (= (:container-type source) :node)
        tile      (if container? (:tile-entity source) source)
        block-pos (when tile
                    (try (pos/position-get-block-pos tile) (catch Exception _ nil)))
        state     (tile-state tile)]
    (when block-pos
      (merge {:gui-id 0
              :pos-x  (pos/pos-x block-pos)
              :pos-y  (pos/pos-y block-pos)
              :pos-z  (pos/pos-z block-pos)}
             (when state
               (into {}
                 (for [field node-schema/unified-node-schema
                       :when (:gui-sync? field)
                       :let [k (:key field)
                             container-key (:gui-container-key field k)
                             value (if container?
                                    (when-let [a (get source container-key)] @a)
                                    (get state k))]]
                   [container-key value])))))))

(defn apply-node-sync-payload! [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    (sync-field-mappings)
    "node"))

;; Removed extract-position wrapper - use sync-helpers/extract-position directly

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
          owner-name (get-owner container)
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
                                    (fn [] (str (try (.getRange ^ IWirelessNode tile) (catch Exception _ 0.0))))
                                    y)
            y (tech-ui/add-property info-area "Owner" owner-name y)]

        (if is-owner?
          (let [y (tech-ui/add-property
                    info-area "Node Name" @(:ssid container) y
                    :editable? true
                    :on-change (fn [new-name]
                                (net-client/send-to-server
                                  (msg :change-name)
                                  (assoc (net-helpers/tile-pos-payload tile)
                                         :node-name new-name))))
                y (tech-ui/add-property
                    info-area "Password" @(:password container) y
                    :editable? true
                    :masked? true
                    :on-change (fn [new-pass]
                                (net-client/send-to-server
                                  (msg :change-password)
                                  (assoc (net-helpers/tile-pos-payload tile)
                                         :password new-pass))))]
            y)
          (tech-ui/add-property info-area "Node Name" @(:ssid container) y))))
    (catch Exception e
      (log/error "Error building info area:"(ex-message e)))))

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
      (log/error "Error creating Node GUI:"(ex-message e))
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

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- node-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :ssid)
       (contains? container :password)))

(def ^:private node-slot-layout
  (slot-schema/get-slot-layout wireless-node-id))

(gui-dsl/defgui wireless-node
  :gui-id 0
  :display-name "Wireless Node"
  :gui-type :node
  :registry-name "wireless_node_gui"
  :screen-factory-fn-kw :create-node-screen
  :slot-layout node-slot-layout
  :container-predicate node-container?
  :container-fn (fn [tile player]
                  (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/create-container)]
                    (f tile player)))
  :screen-fn (fn [container minecraft-container player]
               (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/create-screen)]
                 (f container minecraft-container player)))
  :tick-fn (fn [container]
             (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/tick!)]
               (f container)))
  :sync-get (fn [container]
              (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/get-sync-data)]
                (f container)))
  :sync-apply (fn [container data]
                (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/apply-sync-data!)]
                  (f container data)))
  :payload-sync-apply-fn (fn [payload]
                           (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/apply-node-sync-payload!)]
                             (f payload)))
  :validate-fn (fn [container player]
                 (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/still-valid?)]
                   (f container player)))
  :close-fn (fn [container]
              (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/on-close)]
                (f container)))
  :button-click-fn (fn [container button-id player]
                     (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/handle-button-click!)]
                       (f container button-id player)))
  :slot-count-fn (fn [container]
                   (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/get-slot-count)]
                     (f container)))
  :slot-get-fn (fn [container slot-index]
                 (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/get-slot-item)]
                   (f container slot-index)))
  :slot-set-fn (fn [container slot-index item-stack]
                 (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/set-slot-item!)]
                   (f container slot-index item-stack)))
  :slot-can-place-fn (fn [container slot-index item-stack]
                       (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/can-place-item?)]
                         (f container slot-index item-stack)))
  :slot-changed-fn (fn [container slot-index]
                     (when-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.gui/slot-changed!)]
                       (f container slot-index))))
