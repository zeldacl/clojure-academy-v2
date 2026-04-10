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
            [cn.li.mcmod.gui.xml-parser :as cgui-doc]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.platform-adapter :as gui]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.slot-registry :as slot-registry]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.container.schema-runtime :as schema-runtime]
            [cn.li.mcmod.gui.container.schema :as schema]
            [cn.li.ac.wireless.gui.sync.helpers :as sync-helpers]
            [cn.li.mcmod.gui.metadata :as metadata]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.gui.animation :as anim]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def wireless-node-id :wireless-node)

(defn- ensure-wireless-node-slot-schema!
  []
  (if-let [ensure-fn (requiring-resolve 'cn.li.ac.block.wireless-node.block/ensure-node-slot-schema!)]
    (ensure-fn)
    (slot-schema/register-slot-schema!
      {:schema-id wireless-node-id
       :slots [{:id :input :type :energy :x 42 :y 10}
               {:id :output :type :output :x 42 :y 80}]})))

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def ^:private wireless-node-quick-move-config*
  (delay
    (ensure-wireless-node-slot-schema!)
    (slot-schema/build-quick-move-config
      wireless-node-id
      {:inventory-pred inventory-pred
       :rules [{:accept? energy-stub/is-energy-item-supported?
                :slot-ids [:input]}]})))

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

;; Generate from schema
(defn sync-field-mappings []
  (schema-runtime/build-sync-field-mappings node-schema/unified-node-schema))

;; ============================================================================
;; Animation System (Node status)
;; ============================================================================

(defn get-animation-config
  "Get animation config for current state"
  [state]
  (case state
    :linked {:begin 0 :frames 8 :frame-time 800}
    :unlinked {:begin 8 :frames 2 :frame-time 3000}
    {:begin 0 :frames 1 :frame-time 1000}))

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
        anim-state (anim/create-animation-state)
        ;; Create status poller with query function
        poller (anim/create-status-poller
                 (fn []
                   (net-client/send-to-server
                     (msg :get-status)
                     (net-helpers/tile-pos-payload tile)
                     (fn [response]
                       (let [is-linked (boolean (:linked response))]
                         (reset! (:current-state anim-state)
                                 (if is-linked :linked :unlinked))))))
                 2000)
        widget (apply cgui/create-widget
                      (concat [:pos pos :size size]
                              (when scale [:scale scale])))]
    ;; attach per-frame update: animation + poller + render
    (events/on-frame widget
                     (fn [_]
                       (let [config (get-animation-config @(:current-state anim-state))]
                         (anim/update-animation! anim-state config))
                       ((:update-fn poller))
                       (let [config (get-animation-config @(:current-state anim-state))
                             absolute-frame (+ (:begin config) @(:current-frame anim-state))]
                         (anim/render-animation-frame!
                           widget
                           (modid/asset-path "textures" "guis/effect/effect_node.png")
                           0 0 186 75
                           absolute-frame
                           10))))
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
  (let [[be _state] (resolve-state tile)
        entity     (or be tile)]
    (merge {:tile-entity    entity
            :player         player
            :container-type :node}
         (schema-runtime/build-gui-atoms node-schema/unified-node-schema entity))))

;; ============================================================================
;; Slot Management (from node_container.clj)
;; ============================================================================

(def ^:private node-slot-schema-id wireless-node-id)

(defn- tile-state [tile] (common/get-tile-state tile))

(defn get-slot-count [_container]
  (slot-registry/get-slot-count node-slot-schema-id))

(defn get-owner [container]
  (let [tile (:tile-entity container)]
    (:placer-name (tile-state tile))))

(defn can-place-item? [_container slot-index item-stack]
  (case (slot-registry/get-slot-type-for-index node-slot-schema-id slot-index)
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

;; Generated from schema with custom sync logic
(defn sync-to-client! [container]
  (let [base-sync (schema-runtime/build-sync-to-client-fn node-schema/unified-node-schema)
        tile (:tile-entity container)
        state (or (common/get-tile-state tile) {})
        old-rate @(:transfer-rate container)]
    (base-sync container)
    ;; Handle special sync logic (throttled queries, etc.)
    (when-let [ticker (:sync-ticker container)]
      (sync-helpers/with-throttled-sync! ticker 100
        (fn [] (sync-helpers/query-node-network-capacity! container))))
    ;; Calculate transfer-rate from charging flags - only update if changed
    (when-let [rate-atom (:transfer-rate container)]
      (let [rate (cond
                   (and (:charging-in state) (:charging-out state)) 200
                   (:charging-in state) 100
                   (:charging-out state) 100
                   :else 0)]
        (when (not= rate old-rate)
          (reset! rate-atom rate))))))

(def get-sync-data (schema-runtime/build-get-sync-data-fn node-schema/unified-node-schema))
(def apply-sync-data! (schema-runtime/build-apply-sync-data-fn node-schema/unified-node-schema))

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
    @wireless-node-quick-move-config*))

(defn on-close [container]
  (log/debug "Closing wireless node container")
  ((schema-runtime/build-on-close-fn node-schema/unified-node-schema) container))

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

      (let [node-range (fn []
                         (try
                           (str (.getRange tile))
                           (catch Exception _
                             "0.0")))
            y (tech-ui/add-histogram
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
                                    node-range
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
          _ (log/info "DEBUG: inv-page created, id=" (:id inv-page) "window size=" (cgui/get-size (:window inv-page)) "visible=" (cgui/visible? (:window inv-page)))
          ;; Use the inventory page window as the size reference so that the
          ;; wrapper container matches the XML layout (176x187) instead of
          ;; the smaller TechUI logical width. This prevents the background
          ;; from appearing zoomed or clipped.
          inv-window (:window inv-page)
          info-area (tech-ui/create-info-area)
          _ (log/info "DEBUG: info-area created, size=" (cgui/get-size info-area))
          ;; create animation widget (includes anim-state and poller)
          {:keys [widget]} (create-anim-widget tile)
          anim-widget widget
          _ (log/info "DEBUG: anim-widget created, size=" (cgui/get-size anim-widget) "visible=" (cgui/visible? anim-widget))
          wireless-panel (create-wireless-panel container)
          _ (log/info "DEBUG: wireless-panel created, size=" (cgui/get-size wireless-panel) "visible=" (cgui/visible? wireless-panel))
          pages [inv-page {:id "wireless" :window wireless-panel}]
          _ (log/info "DEBUG: pages created, count=" (count pages))
          container-id (when-let [m (:menu opts)] (gui/get-menu-container-id m))
          ;; Compose tech UI from pages (inventory, info, wireless)
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (log/info "DEBUG: tech-ui created, window size=" (cgui/get-size (:window tech-ui)) "current=" @(:current tech-ui))
          ;; Attach generic tab-change sync (pages sequence, tech-ui map, container, container-id)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          ;tech-ui (tech-ui/create-tech-ui)
          main-widget (:window tech-ui)
          _ (log/info "DEBUG: main-widget extracted, size=" (cgui/get-size main-widget) "children count=" (count (cgui/get-widgets main-widget)))
          ;show-info! (fn [] ((:show-page-fn tech-ui) "info"))
          ;show-wireless! (fn [] ((:show-page-fn tech-ui) "wireless"))
          ]
      
      (cgui/add-widget! inv-window anim-widget)
      (log/info "DEBUG: anim-widget added to inv-window")

      ;; Position and build info area (create-tech-ui has already attached it,
      ;; but we need to position and populate it)
      (cgui/set-position! info-area (+ (cgui/get-width inv-window) 7) 5)
      (build-info-area! info-area container player)
      (log/info "DEBUG: info-area positioned and built")

      (cgui/add-widget! main-widget info-area)
      (log/info "DEBUG: info-area added to main-widget")
      
      (log/info "Created Wireless Node GUI (TechUI)")
      ;; When opts has :menu (screen path), return map so screen can block slot clicks when tab != inv
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Node GUI:"(ex-message e))
      (log/error "Stack trace:" (.printStackTrace e))
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

(declare node-container?)
(defonce ^:private wireless-node-gui-installed? (atom false))

(defn init!
  "Initialize Node GUI module"
  []
  (when (compare-and-set! wireless-node-gui-installed? false true)
    (ensure-wireless-node-slot-schema!)
    (gui-dsl/register-gui!
      (gui-dsl/create-gui-spec
        "wireless-node"
        {:gui-id 0
         :display-name "Wireless Node"
         :gui-type :node
         :registry-name "wireless_node_gui"
         :screen-factory-fn-kw :create-node-screen
         :slot-layout (slot-schema/get-slot-layout wireless-node-id)
         :container-predicate node-container?
         :container-fn create-container
         :screen-fn create-screen
         :tick-fn tick!
         :sync-get get-sync-data
         :sync-apply apply-sync-data!
         :payload-sync-apply-fn apply-node-sync-payload!
         :validate-fn still-valid?
         :close-fn on-close
         :button-click-fn handle-button-click!
         :slot-count-fn get-slot-count
         :slot-get-fn get-slot-item
         :slot-set-fn set-slot-item!
         :slot-can-place-fn can-place-item?
         :slot-changed-fn slot-changed!}))
    (log/info "Wireless Node GUI module initialized")))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- node-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :ssid)
       (contains? container :password)))
