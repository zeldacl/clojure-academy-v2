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
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mcmod.gui.container-state :as container-state]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.mcmod.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.ac.wireless.gui.tab :as wireless-tab]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.energy.operations :as energy-stub]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.block.wireless-node.logic :as node-logic]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.mcmod.gui.animation :as anim]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessNode]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def wireless-node-id :wireless-node)

(defn- ensure-wireless-node-slot-schema!
  []
  (node-logic/ensure-node-slot-schema!))

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def ^:private node-quick-move-config-lock
  (Object.))

(def ^:private ^:dynamic *wireless-node-quick-move-config*
  nil)

(defn- wireless-node-quick-move-config
  []
  (or (var-get #'*wireless-node-quick-move-config*)
      (locking node-quick-move-config-lock
        (or (var-get #'*wireless-node-quick-move-config*)
            (let [cfg (do
                        (ensure-wireless-node-slot-schema!)
                        (slot-schema/build-quick-move-config
                          wireless-node-id
                          {:inventory-pred inventory-pred
                           :rules [{:accept? energy-stub/is-energy-item-supported?
                                    :slot-ids [:input :output]}]}))]
              (alter-var-root #'*wireless-node-quick-move-config* (constantly cfg))
              cfg)))))

(defn- msg
  "Generate message ID for node actions (must match server DSL / underscores)."
  [action]
  (msg-registry/msg :node action))

;; ============================================================================
;; GUI Dimensions (shared)
;; ============================================================================

;; Note: gui-width and gui-height removed - use tech-ui/gui-width directly if needed

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
  "Create animation widget and attach frame handler."
  [container & [opts]]
  (let [opts (or opts {})
        pos (get opts :pos [42 35.5])
        size (get opts :size [186 75])
        scale (get opts :scale 0.5)
        anim-state (anim/create-animation-state)
        widget (apply cgui-core/create-widget
                      (concat [:pos pos :size size]
                              (when scale [:scale scale])))]
    ;; attach per-frame update: animation + render
    (events/on-frame widget
                     (fn [_]
                       (let [linked? (boolean (when-let [linked (:linked container)] @linked))]
                         (reset! (:current-state anim-state) (if linked? :linked :unlinked)))
                       (let [config (get-animation-config @(:current-state anim-state))]
                         (anim/update-animation! anim-state config))
                       (let [config (get-animation-config @(:current-state anim-state))
                             absolute-frame (+ (:begin config) @(:current-frame anim-state))]
                         (anim/render-animation-frame!
                           widget
                           (modid/asset-path "textures" "guis/effect/effect_node.png")
                           0 0 186 75
                           absolute-frame
                           10))))
    {:widget widget :anim-state anim-state}))

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
    (gui-sync/create-schema-container node-schema/unified-node-schema
                                      entity
                                      player
                                      :node
                                      {:gui-id (gui-manifest/gui-id :wireless-node)})))

;; ============================================================================
;; Slot Management (from node_container.clj)
;; ============================================================================

(def ^:private node-slot-schema-id wireless-node-id)

(defn- tile-state [tile] (common/get-tile-state tile))

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count node-slot-schema-id))

(defn get-owner [container]
  (let [tile (:tile-entity container)]
    (node-logic/owner-name (tile-state tile))))

(defn can-place-item? [_container slot-index item-stack]
  (case (slot-schema/slot-type node-slot-schema-id slot-index)
    :energy (energy-stub/is-energy-item-supported? item-stack)
    :output (energy-stub/is-energy-item-supported? item-stack)
    false))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (common/set-slot-item-be! container slot-index item-stack {} identity))

(defn slot-changed! [_container slot-index]
  ;(log/info "Node container slot" slot-index "changed")
  )

;; ============================================================================
;; Wireless Page (network list + connect)
;; ============================================================================

(defn create-wireless-panel
  "Shared wireless tab (node mode)."
  [container & [opts]]
  (wireless-tab/create-wireless-panel {:role :node
                                       :container container
                                       :menu (:menu opts)}))

;; ============================================================================
;; Container Sync (from node_container.clj)
;; ============================================================================

(defn- update-derived-sync-fields!
  "Local tile-state derived fields only; no network queries during menu sync."
  [container]
  (let [tile (:tile-entity container)
        state (or (common/get-tile-state tile) {})
        current-rate @(:transfer-rate container)]
    (when-let [rate-atom (:transfer-rate container)]
      (let [rate (cond
                   (and (:charging-in state) (:charging-out state)) 200
                   (:charging-in state) 100
                   (:charging-out state) 100
                   :else 0)]
        (when (not= rate current-rate)
          (reset! rate-atom rate))))))

(def ^:private node-sync
  (gui-sync/schema-sync-fns node-schema/unified-node-schema
                            {:after-sync! update-derived-sync-fields!}))

(def server-menu-sync! (:server-menu-sync! node-sync))

(defn still-valid? [container player]
  (common/still-valid? container player))

(defn handle-button-click! [container button-id data]
  (let [tile (:tile-entity container)]
    (when-not (map? tile)
      (case (int button-id)
        0 (let [state (or (platform-be/get-custom-state tile) node-logic/node-default-state)]
            (machine-runtime/commit-transform! tile node-logic/node-default-state
                                               #(update % :enabled not)
                                               :blockstate-updater node-logic/update-block-state!)
            (log/info "Toggled node connection:" (:enabled state)))
        1 (when-let [new-ssid (:ssid data)]
            (machine-runtime/commit-transform! tile node-logic/node-default-state
                                               #(assoc % :node-name new-ssid))
            (log/info "Set node SSID to:" new-ssid))
        2 (when-let [new-password (:password data)]
            (machine-runtime/commit-transform! tile node-logic/node-default-state
                                               #(assoc % :password new-password))
            (log/info "Set node password"))
        (log/warn "Unknown button ID:" button-id)))))

(defn quick-move-stack [container slot-index player-inventory-start]
  (move-common/quick-move-with-rules
    container slot-index player-inventory-start
    (wireless-node-quick-move-config)))

(defn on-close [container]
  (log/debug "Closing wireless node container")
  ((:on-close node-sync) container))

(defn node-info-area-policy
  "Compute editable policy for node info-area fields."
  [is-owner?]
  {:editable-node-name? (boolean is-owner?)
   :editable-password? (boolean is-owner?)})

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
          is-owner? (node-logic/owner-authorized? owner-name player)
          policy (node-info-area-policy is-owner?)]

      (tech-ui/reset-info-area! info-area)

      (let [node-range (fn []
                         (try
                           (str (.getRange ^IWirelessNode tile))
                           (catch Exception _
                             "0.0")))
            y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-energy
                   (fn [] @(:energy container))
                   (fn [] @(:max-energy container)))
                 (tech-ui/hist-capacity
                   (fn [] @(:capacity container))
                   (fn [] (max 1 @(:max-capacity container))))]
                0)
            y (tech-ui/add-sepline info-area "Info" y)
            y (tech-ui/add-property info-area "Range"
                                    node-range
                                    y)
            y (tech-ui/add-property info-area "Owner" owner-name y)]

        (if (:editable-node-name? policy)
          (let [y (tech-ui/add-property
                    info-area "Node Name" @(:ssid container) y
                    :editable? true
                    :on-change (fn [new-name]
                                (net-client/send-to-server
                                  (msg :change-name)
                                  (action-payload/action-payload container {:node-name new-name}))))
                y (tech-ui/add-property
                    info-area "Password" @(:password container) y
                  :editable? (:editable-password? policy)
                    :masked? true
                    :on-change (fn [new-pass]
                                (net-client/send-to-server
                                  (msg :change-password)
                                  (action-payload/action-payload container {:password new-pass}))))]
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
    (let [container (cond-> container
                      (:menu opts) (assoc :minecraft-container (:menu opts)))
          inv-page (tech-ui/create-inventory-page "node")
          ;; Use the inventory page window as the size reference so that the
          ;; wrapper container matches the XML layout (176x187) instead of
          ;; the smaller TechUI logical width. This prevents the background
          ;; from appearing zoomed or clipped.
          inv-window (:window inv-page)
          info-area (tech-ui/create-info-area)
          _ (log/info "DEBUG: info-area created, size=" (cgui-core/get-size info-area))
          ;; create animation widget (includes anim-state and poller)
          {:keys [widget]} (create-anim-widget container)
          anim-widget widget
          _ (log/info "DEBUG: anim-widget created, size=" (cgui-core/get-size anim-widget) "visible=" (cgui-core/visible? anim-widget))
          wireless-panel (create-wireless-panel container {:menu (:menu opts)})
          _ (log/info "DEBUG: wireless-panel created, size=" (cgui-core/get-size wireless-panel) "visible=" (cgui-core/visible? wireless-panel))
          pages [inv-page {:id "wireless" :window wireless-panel}]
          _ (log/info "DEBUG: pages created, count=" (count pages))
          container-id (when-let [m (:menu opts)] (container-state/get-menu-container-id m))
          ;; Compose tech UI from pages (inventory, info, wireless)
          tech-ui (apply tech-ui/create-tech-ui pages)
          _ (log/info "DEBUG: tech-ui created, window size=" (cgui-core/get-size (:window tech-ui)) "current=" @(:current tech-ui))
          ;; Attach generic tab-change sync (pages sequence, tech-ui map, container, container-id)
          _ (tabbed-gui/attach-tab-sync! pages tech-ui container container-id)
          ;tech-ui (tech-ui/create-tech-ui)
          main-widget (:window tech-ui)
          _ (log/info "DEBUG: main-widget extracted, size=" (cgui-core/get-size main-widget) "children count=" (count (cgui-core/get-widgets main-widget)))
          ;show-info! (fn [] ((:show-page-fn tech-ui) "info"))
          ;show-wireless! (fn [] ((:show-page-fn tech-ui) "wireless"))
          ]
      
      (cgui-core/add-widget! inv-window anim-widget)
      (log/info "DEBUG: anim-widget added to inv-window")

      ;; Position and build info area (create-tech-ui has already attached it,
      ;; but we need to position and populate it)
      (cgui-core/set-position! info-area (+ (cgui-core/get-width inv-window) 7) 5)
      (build-info-area! info-area container player)
      (log/info "DEBUG: info-area positioned and built")

      (cgui-core/add-widget! main-widget info-area)
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
        base (cgui-screen/create-cgui-screen-container root minecraft-container)]
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
(def ^:private wireless-node-gui-guard-lock
  (Object.))

(def ^:private ^:dynamic *wireless-node-gui-installed?*
  false)

(defn init-wireless-node-gui!
  "Initialize Node GUI module"
  []
  (when-not (var-get #'*wireless-node-gui-installed?*)
    (locking wireless-node-gui-guard-lock
      (when-not (var-get #'*wireless-node-gui-installed?*)
        (ensure-wireless-node-slot-schema!)
    (gui-reg/register-block-gui!
      (gui-manifest/gui-name :wireless-node)
      (merge (gui-manifest/gui-registration :wireless-node)
             {:container-predicate node-container?
              :container-fn create-container
              :screen-fn create-screen
              :server-menu-sync-fn server-menu-sync!
              :validate-fn still-valid?
              :close-fn on-close
              :button-click-fn handle-button-click!
              :slot-count-fn get-slot-count
              :slot-get-fn get-slot-item
              :slot-set-fn set-slot-item!
              :slot-can-place-fn can-place-item?
              :slot-changed-fn slot-changed!
              :quick-move-fn quick-move-stack}))
        (alter-var-root #'*wireless-node-gui-installed?* (constantly true))
        (log/info "Wireless Node GUI module initialized")))))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- node-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :ssid)
       (contains? container :password)))
