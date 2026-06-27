(ns cn.li.ac.block.wireless-matrix.gui
  "CLIENT-ONLY: Wireless Matrix GUI implementation.

  This file contains:
  - GUI layout and component builders
  - Client-side network message senders
  - GUI interaction logic
  - Container atom management

  Must be loaded via side-checked requiring-resolve from platform layer.

  Architecture: Uses TechUI.ContainerUI pattern

  Flow:
  1. Load shared InventoryPage
  2. Query server for network information
  3. Build dynamic InfoArea based on 3 states:
     - Uninitialized + Owner: Show initialization form
     - Uninitialized + Non-owner: Show prompt message
     - Initialized: Show network info (owner can edit)

  Features:
  - Network capacity histogram
  - Owner/range/bandwidth properties (read-only)
  - Dynamic SSID/password display (owner can edit after init)
  - Initialization form for owner to create network"
  
  (:require [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.gui.container.action-payload :as action-payload]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.wireless-matrix.capability :as matrix-capability]
            [cn.li.ac.block.wireless-matrix.logic :as matrix-logic]
            [cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.mcmod.gui.spec :as gui-reg]
            [cn.li.ac.block.gui.sync :as gui-sync]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.gui.container.common :as common]
            [cn.li.ac.wireless.gui.container.move :as move-common]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.hooks.core :as runtime-hooks])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

;; ============================================================================
;; Slot Schema
;; ============================================================================

(def wireless-matrix-id :wireless-matrix)

(defn- ensure-wireless-matrix-slot-schema!
  []
  (matrix-logic/ensure-matrix-slot-schema!))

(def ^:private inventory-pred
  (fn [slot-index player-inventory-start]
    (>= slot-index player-inventory-start)))

(def ^:private matrix-quick-move-config-lock
  (Object.))

(def ^:private ^:dynamic *wireless-matrix-quick-move-config*
  nil)

(defn- wireless-matrix-quick-move-config
  []
  (or (var-get #'*wireless-matrix-quick-move-config*)
      (locking matrix-quick-move-config-lock
        (or (var-get #'*wireless-matrix-quick-move-config*)
            (let [cfg (do
                        (ensure-wireless-matrix-slot-schema!)
                        (slot-schema/build-quick-move-config
                          wireless-matrix-id
                          {:inventory-pred inventory-pred
                           :rules [{:accept? core/is-mat-core?
                                    :slot-ids [:core]}
                                   {:accept? plate/is-constraint-plate?
                                    :slot-type :plate}]}))]
              (alter-var-root #'*wireless-matrix-quick-move-config* (constantly cfg))
              cfg)))))

(defn- msg
  "Generate message ID for matrix actions (must match server DSL / underscores)."
  [action]
  (msg-registry/msg :matrix action))

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Data Structures
;; ============================================================================

(defrecord MatrixNetworkData
  [ssid              ;; String | nil - Network SSID (nil = uninitialized)
   password          ;; String | nil - Network password
   owner             ;; String - Owner player name
   load              ;; int - Current device count
   max-capacity      ;; int - Maximum device capacity
   range             ;; int - Network range in blocks
   bandwidth         ;; int - Network bandwidth
   initialized       ;; boolean - Whether network is initialized
   ])

(defn network-initialized? [data]
  ;; Check if network is initialized
  (:initialized data))

(defn matrix-info-area-policy
  "Return Matrix info-area interaction policy.

  Keys:
  - :show-init?          show initialization form
  - :show-noinit?        show not-initialized message only
  - :editable-ssid?      whether SSID is editable
  - :editable-password?  whether password is editable"
  [initialized? is-owner?]
  (let [owner? (true? is-owner?)]
    (cond
      (true? initialized?)
      {:show-init? false
       :show-noinit? false
       :editable-ssid? owner?
       :editable-password? owner?}

      (and (or (false? initialized?) (nil? initialized?)) owner?)
      {:show-init? true
       :show-noinit? false
       :editable-ssid? false
       :editable-password? false}

      (or (false? initialized?) (nil? initialized?))
      {:show-init? false
       :show-noinit? true
       :editable-ssid? false
       :editable-password? false}

      :else
      {:show-init? false
       :show-noinit? false
       :editable-ssid? false
       :editable-password? false})))



;; ============================================================================
;; Network Messages
;; ============================================================================

(defn- current-client-owner
  "Resolve a client owner for GUI-initiated server requests.
  Tries platform-level session resolution first, falls back to dynamic bindings,
  returns nil when no session can be resolved."
  []
  (let [session-id runtime-hooks/*client-session-id*
        player-uuid (some-> runtime-hooks/*player-state-owner* :player-uuid)]
    (when session-id
      (cond-> {:logical-side :client :client-session-id session-id}
        player-uuid (assoc :player-uuid player-uuid)))))

(defn send-gather-info
  "Query network information from server."
  [container callback]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :gather-info)
        (action-payload/action-payload container {})
        (fn [response]
          (try
            (let [data (map->MatrixNetworkData
                         {:ssid (get response :ssid)
                          :password (get response :password)
                          :owner (get response :owner "Unknown")
                          :load (get response :load 0)
                          :max-capacity (get response :max-capacity 16)
                          :range (get response :range 64)
                          :bandwidth (get response :bandwidth 100)
                          :initialized (boolean (if (contains? response :initialized)
                                                  (get response :initialized)
                                                  (get response :ssid)))})]
              (callback data))
            (catch Exception e
              (log/error "Error processing gather-info response:"(ex-message e))))))
      (log/debug "Skip gather-info: no client session bound"))
    (catch Exception e
      (log/error "Error sending gather-info:"(ex-message e)))))

(defn send-init-network
  [container ssid password callback]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :init)
        (action-payload/action-payload container {:ssid ssid :password password})
        (fn [response]
          (try
            (callback (get response :success false))
            (catch Exception e
              (log/error "Error processing init response:"(ex-message e))))))
      (log/debug "Skip init-network: no client session bound"))
    (catch Exception e
      (log/error "Error sending init:"(ex-message e)))))

(defn send-change-ssid
  [container new-ssid]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :change-ssid)
        (action-payload/action-payload container {:new-ssid new-ssid})
        nil)
      (log/warn "Skip change-ssid: no client session bound"))
    (catch Exception e
      (log/error "Error sending change-ssid:"(ex-message e)))))

(defn send-change-password
  [container new-password]
  (try
    (if-let [owner (current-client-owner)]
      (net-client/send-to-server owner
        (msg :change-password)
        (action-payload/action-payload container {:new-password new-password})
        nil)
      (log/warn "Skip change-password: no client session bound"))
    (catch Exception e
      (log/error "Error sending change-password:"(ex-message e)))))

;; ============================================================================
;; Container Creation (from matrix_container.clj)
;; ============================================================================

(defn- resolve-state
  "Resolve the state map from either a ScriptedBlockEntity or an existing map."
  [tile]
  (if (map? tile)
    [nil tile]
    (try
      (let [state (or (platform-be/get-custom-state tile) matrix-logic/matrix-default-state)]
        [tile state])
      (catch Exception e
        (log/warn "Could not resolve customState from BE:"(ex-message e))
        [tile {}]))))

(defn create-container
  "Create a Matrix GUI container instance.
  Uses container atoms for sync; platform layer may attach DataSlots if needed."
  [tile player]
  (let [[be state] (resolve-state tile)
        entity (or be tile)
        proxy (matrix-capability/->MatrixJavaProxy entity)]
    (gui-sync/create-schema-container
      matrix-schema/unified-matrix-schema
      state
      player
      :matrix
      {:gui-id (gui-manifest/gui-id :wireless-matrix)
       :base {:tile-entity entity
              :tile-java proxy}})))

;; ============================================================================
;; Slot Management (from matrix_container.clj)
;; ============================================================================

(def ^:private matrix-slot-schema-id wireless-matrix-id)

(defn get-slot-count [_container]
  (slot-schema/tile-slot-count matrix-slot-schema-id))

(defn can-place-item? [_container slot-index item-stack]
  (case (slot-schema/slot-type matrix-slot-schema-id slot-index)
    :plate (plate/is-constraint-plate? item-stack)
    :core (core/is-mat-core? item-stack)
    false))

(defn get-slot-item [container slot-index]
  (common/get-slot-item-be container slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (log/debug "set-slot-item! - tile=" tile " slot=" slot-index " item=" item-stack)
    (common/set-slot-item-be! container slot-index item-stack
                              matrix-logic/matrix-default-state
                              matrix-logic/recalculate-counts)
    (when tile
      (log/debug "set-slot-item! after-write - plate=" (matrix-logic/get-plate-count tile)
                " core=" (matrix-logic/get-core-level tile)))
    ;; DataSlot synchronization is handled by Menu.broadcastChanges(),
    ;; which reads plate-count and core-level from container atoms every tick.
    nil))

(defn slot-changed! [container slot-index]
  ;; Trigger BE update with recalculation
  (let [item (get-slot-item container slot-index)]
    (set-slot-item! container slot-index item)))

;; ============================================================================
;; Container Sync (from matrix_container.clj)
;; ============================================================================

(def ^:private matrix-sync
  (gui-sync/schema-sync-fns matrix-schema/unified-matrix-schema))

(def server-menu-sync! (:server-menu-sync! matrix-sync))

(defn still-valid? [container player]
  (common/still-valid? container player))

(defn handle-button-click! [container button-id _data]
  (case (int button-id)
    0 (log/info "Toggled matrix working state")
    1 (do
        (set-slot-item! container (slot-schema/slot-index matrix-slot-schema-id :core) nil)
        (log/info "Ejected matrix core"))
    2 (do
        (doseq [slot-idx (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate)]
          (set-slot-item! container slot-idx nil))
        (log/info "Ejected all plates"))
    (log/warn "Unknown button ID:" button-id)))

(defn quick-move-stack [container slot-index player-inventory-start]
  (move-common/quick-move-with-rules
    container
    slot-index
    player-inventory-start
    (wireless-matrix-quick-move-config)))

(defn on-close [container]
  (log/debug "Closing wireless matrix container")
  ((:on-close matrix-sync) container))

;; ============================================================================
;; Component Builders
;; ============================================================================

;; ============================================================================
;; InfoArea Builder (使用共享TechUI组件)
;; ============================================================================

(defn rebuild-info-area!
  [info-area container player data]
  (try
    (let [tile (:tile-entity container)
          state (matrix-logic/safe-state tile)
          placer (or (try (.getPlacerName ^IWirelessMatrix tile) (catch Exception _ nil))
                     (matrix-logic/placer-name state)
                     (:owner data "Unknown"))
          is-owner? (matrix-logic/owner-authorized? state player)
          policy (matrix-info-area-policy (boolean (network-initialized? data)) is-owner?)
          cap (:max-capacity data)
          range (:range data)
          bandwidth (:bandwidth data)]
      (tech-ui/reset-info-area! info-area)
      (let [y (tech-ui/add-histogram
                info-area
                [(tech-ui/hist-capacity
                   (fn [] (:load data))
                   cap)]
                0)
            y (tech-ui/add-sepline info-area "info" y)
            y (tech-ui/add-property info-area "owner" placer y)
            y (tech-ui/add-property info-area "range"
                                    (format "%.0f" (double range))
                                    y)
            y (tech-ui/add-property info-area "bandwidth"
                                    (str bandwidth " IF/T")
                                    y)]
        (if (network-initialized? data)
          (let [y (tech-ui/add-sepline info-area "wireless_info" y)
                y (tech-ui/add-property
                    info-area "ssid" (:ssid data) y
                    :editable? (:editable-ssid? policy)
                    :on-change (fn [new-ssid]
                                (send-change-ssid container new-ssid)))
                y (tech-ui/add-sepline info-area "change_pass" y)
                y (tech-ui/add-property
                    info-area "password" (:password data) y
                    :editable? (:editable-password? policy)
                    :masked? true
                    :on-change (fn [new-pass]
                                (send-change-password container new-pass)))]
            y)
          (if (:show-init? policy)
            (let [ssid-cell (atom nil)
                  pass-cell (atom nil)
                  y (tech-ui/add-sepline info-area "wireless_init" y)
                  y (tech-ui/add-property info-area "ssid" "" y
                                          :editable? true
                                          :color-change? false
                                          :content-cell ssid-cell)
                  y (tech-ui/add-property info-area "password" "" y
                                          :editable? true
                                          :masked? true
                                          :color-change? false
                                          :content-cell pass-cell)
                  y (+ y 1)
                  y (tech-ui/add-button
                      info-area "INIT"
                      (fn []
                        (log/info "Matrix INIT button clicked!")
                        (let [ssid (if-let [tb @ssid-cell] (comp/get-text tb) "")
                              pass (if-let [tb @pass-cell] (comp/get-text tb) "")]
                          (log/info "Matrix INIT ssid=" ssid "pass=" pass)
                          (send-init-network container ssid pass
                            (fn [success]
                              (when success
                                (send-gather-info container
                                  (fn [new-data]
                                    (rebuild-info-area! info-area container player new-data))))))))
                      y)]
              y)
            (if (:show-noinit? policy)
              (tech-ui/add-sepline info-area "wireless_noinit" y)
              y)))))
    (catch Exception e
      (log/error "Error rebuilding info area:"(ex-message e)))))

;; ============================================================================
;; Main GUI Factory
;; ============================================================================

(defn create-matrix-gui
  "Create Wireless Matrix GUI (参照Scala GuiMatrix2)
  
  Args:
  - container: ContainerMatrix instance
  - player: EntityPlayer who opened GUI
  
  Returns: Root CGui widget"
  [container player & [opts]]
  (try
    (let [container (cond-> container
                      (:menu opts) (assoc :minecraft-container (:menu opts)))
          tile (or (:tile-entity container)
                   (:tile container))

          ;; Create inventory page using shared builder
          inv-page (tech-ui/create-inventory-page "matrix")
          pages [inv-page]
          
          ;; Compose tech UI from pages (inv only, like Scala GuiMatrix2)
          tech-ui (apply tech-ui/create-tech-ui pages)
          ;tech-ui (tech-ui/create-tech-ui)
          main-widget (:window tech-ui)
          
          ;; Create InfoArea
          info-area (tech-ui/create-info-area)]
      
      ;; Add inventory page window
      ;(cgui-core/add-widget! main-widget (:window inv-page))
      
      ;; Position info area
      (cgui-core/set-position! info-area
        (+ (cgui-core/get-width main-widget) 7)
        5)
      
      ;; Initialize network data and build InfoArea
      (send-gather-info container
        (fn [data]
          (rebuild-info-area! info-area container player data)))
      
      ;; Add info area to main widget
      (cgui-core/add-widget! main-widget info-area)
      
      (log/info "Created Wireless Matrix GUI")
      
      (if (:menu opts)
        {:root main-widget :current (:current tech-ui)}
        main-widget))
    (catch Exception e
      (log/error "Error creating Matrix GUI:"(ex-message e))
      (throw e))))

;; ============================================================================
;; Screen Creation
;; ============================================================================

(defn create-screen
  "Create screen container for GUI
  
  Args:
  - container: ContainerMatrix
  - minecraft-container: Minecraft Container
  - player: EntityPlayer
  
  Returns: CGuiScreenContainer"
  [container minecraft-container player]
  (let [gui (create-matrix-gui container player {:menu minecraft-container})
        root (if (map? gui) (:root gui) gui)
        base (cgui-screen/create-cgui-screen-container root minecraft-container)]
    (if (map? gui)
      (tech-ui/assoc-tech-ui-screen-size (assoc base :current-tab-atom (:current gui)))
      (tech-ui/assoc-tech-ui-screen-size base))))

;; ============================================================================
;; Public API
;; ============================================================================

(defn open-matrix-gui
  "Open Wireless Matrix GUI for player"
  [container player]
  (create-matrix-gui container player))

(defn init!
  "Initialize Matrix GUI module"
  []
  (log/info "Wireless Matrix GUI XML module initialized"))

;; ============================================================================
;; GUI Registration
;; ============================================================================

(defn- matrix-container? [container]
  (and (map? container)
       (contains? container :tile-entity)
       (contains? container :plate-count)
       (contains? container :core-level)))

(def ^:private matrix-gui-guard-lock
  (Object.))

(def ^:private ^:dynamic *wireless-matrix-gui-installed?*
  false)

(defn init-wireless-matrix-gui!
  []
  (when-not (var-get #'*wireless-matrix-gui-installed?*)
    (locking matrix-gui-guard-lock
      (when-not (var-get #'*wireless-matrix-gui-installed?*)
        (ensure-wireless-matrix-slot-schema!)
        (gui-reg/register-block-gui!
          (gui-manifest/gui-name :wireless-matrix)
          (merge (gui-manifest/gui-registration :wireless-matrix)
                 {:container-predicate matrix-container?
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
                  :slot-changed-fn slot-changed!}))
        (alter-var-root #'*wireless-matrix-gui-installed?* (constantly true))
        (log/info "Wireless Matrix GUI registered")))))
