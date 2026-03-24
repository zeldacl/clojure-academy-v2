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
  
  (:require [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.ac.gui.tabbed-gui :as tabbed-gui]
            [cn.li.ac.gui.tech-ui-common :as tech-ui]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.ac.wireless.gui.network-handler-helpers :as net-helpers]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.block.wireless-matrix.block :as wm]
            [cn.li.mcmod.gui.slot-schema :as slot-schema]
            [cn.li.ac.item.constraint-plate :as plate]
            [cn.li.ac.item.mat-core :as core]
            [cn.li.ac.wireless.slot-schema :as slots]
            [cn.li.ac.wireless.gui.container-common :as common]
            [cn.li.ac.wireless.gui.container-move-common :as move-common]
            [cn.li.ac.wireless.gui.container-schema :as schema]
            [cn.li.ac.wireless.gui.sync-helpers :as sync-helpers]
            [cn.li.ac.wireless.gui.gui-metadata :as metadata]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.position :as pos])
  (:import [cn.li.acapi.wireless IWirelessMatrix]))

(defn- msg
  "Generate message ID for matrix actions."
  [action]
  (str "wireless_matrix_" (name action)))

(def gui-width tech-ui/gui-width)
(def gui-height tech-ui/gui-height)

;; ============================================================================
;; Field Schema (from matrix_fields.clj)
;; ============================================================================

(def matrix-fields
  [{:key :core-level   :init (fn [s] (:core-level s 0))    :sync? true  :coerce int     :close-reset 0}
   {:key :plate-count  :init (fn [s] (:plate-count s 0))   :sync? true  :coerce int     :close-reset 0}
   {:key :is-working   :init (fn [_] false)                 :sync? true  :coerce boolean :close-reset false}
   {:key :capacity     :init (fn [_] 0)                     :sync? true  :coerce int     :close-reset 0}
   {:key :max-capacity :init (fn [_] 0)                     :sync? true  :coerce int     :close-reset 0}
   {:key :bandwidth    :init (fn [_] 0)                     :sync? true  :coerce long    :close-reset 0}
   {:key :range        :init (fn [_] 0.0)                   :sync? true  :coerce double  :close-reset 0.0}
   {:key :sync-ticker  :init (fn [_] 0)                     :sync? false :coerce int     :close-reset 0}])

(defn sync-field-mappings
  "Return the field-mappings vector for apply-sync-payload-template!."
  []
  (schema/sync-field-mappings matrix-fields))

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

;; ============================================================================
;; Network Messages
;; ============================================================================

(defn send-gather-info
  "Query network information from server
  
  Args:
  - tile: TileMatrix instance
  - callback: (fn [MatrixNetworkData] ...) - receives query result"
  [tile callback]
  (try
    (net-client/send-to-server
      (msg :gather-info)
      (net-helpers/tile-pos-payload tile)
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
                        :initialized (boolean (get response :ssid))})]
            (callback data))
          (catch Exception e
            (log/error "Error processing gather-info response:"(ex-message e))))))
    (catch Exception e
      (log/error "Error sending gather-info:"(ex-message e)))))

(defn send-init-network
  "Initialize network on server
  
  Args:
  - tile: TileMatrix instance
  - ssid: String - network name
  - password: String - network password
  - callback: (fn [success] ...) - receives init result"
  [tile ssid password callback]
  (try
    (net-client/send-to-server
      (msg :init)
      (assoc (net-helpers/tile-pos-payload tile)
             :ssid ssid
             :password password)
      (fn [response]
        (try
          (callback (get response :success false))
          (catch Exception e
            (log/error "Error processing init response:"(ex-message e))))))
    (catch Exception e
      (log/error "Error sending init:"(ex-message e)))))

(defn send-change-ssid
  "Change network SSID
  
  Args:
  - tile: TileMatrix instance
  - new-ssid: String"
  [tile new-ssid]
  (try
    (net-client/send-to-server
      (msg :change-ssid)
      (assoc (net-helpers/tile-pos-payload tile)
             :new-ssid new-ssid))
    (catch Exception e
      (log/error "Error sending change-ssid:"(ex-message e)))))

(defn send-change-password
  "Change network password
  
  Args:
  - tile: TileMatrix instance
  - new-password: String"
  [tile new-password]
  (try
    (net-client/send-to-server
      (msg :change-password)
      (assoc (net-helpers/tile-pos-payload tile)
             :new-password new-password))
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
      (let [state (or (platform-be/get-custom-state tile) wm/matrix-default-state)]
        [tile state])
      (catch Exception e
        (log/warn "Could not resolve customState from BE:"(ex-message e))
        [tile {}]))))

(defn create-container
  "Create a Matrix GUI container instance."
  [tile player]
  (let [[be state] (resolve-state tile)
        proxy      (if be
                     (wm/->MatrixJavaProxy be)
                     (wm/->MatrixJavaProxy tile))]
    (merge {:tile-entity    (or be tile)
            :tile-java      proxy
            :player         player
            :container-type :matrix}
           (schema/build-atoms matrix-fields state))))

;; ============================================================================
;; Slot Management (from matrix_container.clj)
;; ============================================================================

(def ^:private matrix-slot-schema-id slots/wireless-matrix-id)

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
  (common/set-slot-item-be! container slot-index item-stack
                             wm/matrix-default-state
                             wm/recalculate-counts))

(defn slot-changed! [_container slot-index]
  (log/info "Matrix container slot" slot-index "changed"))

;; ============================================================================
;; Container Sync (from matrix_container.clj)
;; ============================================================================

(defn- count-plates [container]
  (reduce (fn [count slot-idx]
            (let [stk (get-slot-item container slot-idx)]
              (if (and stk (not (pitem/item-is-empty? stk)))
                (inc count)
                count)))
          0
          (slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate)))

(defn- get-core-level [container]
  (let [core-item (get-slot-item container (slot-schema/slot-index matrix-slot-schema-id :core))]
    (if (and core-item (core/is-mat-core? core-item))
      (core/get-core-level core-item)
      0)))

(defn- calculate-matrix-stats [core-level plate-count]
  (let [base-capacity  (* core-level 50000)
        base-bandwidth (* core-level 1000)
        base-range     (* core-level 16.0)
        plate-mult     (+ 1.0 (* plate-count 0.2))
        capacity       (int (* base-capacity plate-mult))
        bandwidth      (int (* base-bandwidth plate-mult))
        range          (* base-range plate-mult)]
    {:capacity capacity
     :bandwidth bandwidth
     :range range}))

(defn sync-to-client! [container]
  (let [plates   (count-plates container)
        core-lvl (get-core-level container)
        working? (> core-lvl 0)]
    (reset! (:core-level container) core-lvl)
    (reset! (:plate-count container) plates)
    (reset! (:is-working container) working?)
    (sync-helpers/with-throttled-sync! (:sync-ticker container) 100
      (fn []
        (let [stats (calculate-matrix-stats core-lvl plates)]
          (reset! (:bandwidth container) (:bandwidth stats))
          (reset! (:range container) (:range stats))
          (sync-helpers/query-matrix-network-capacity! container stats))))))

(defn get-sync-data [container]
  (schema/get-sync-data matrix-fields container))

(defn apply-sync-data! [container data]
  (schema/apply-sync-data! matrix-fields container data))

(defn still-valid? [container player]
  (common/still-valid? container player))

(defn tick! [container]
  (sync-to-client! container))

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
    slots/wireless-matrix-quick-move-config))

(defn on-close [container]
  (log/debug "Closing wireless matrix container")
  (schema/reset-atoms! matrix-fields container))

;; ============================================================================
;; Sync Packet Handling (from matrix_sync.clj)
;; ============================================================================

(defn broadcast-matrix-state [world pos sync-data]
  (sync-helpers/broadcast-state world pos sync-data "matrix"))

(defn- matrix-container? [source]
  (= (:container-type source) :matrix))

(defn make-sync-packet [source]
  (let [container? (matrix-container? source)
        tile       (if container? (:tile-entity source) source)
        container  (when container? source)
        block-pos  (pos/position-get-block-pos tile)]
    (merge {:gui-id      metadata/gui-wireless-matrix
            :pos-x       (pos/pos-x block-pos)
            :pos-y       (pos/pos-y block-pos)
            :pos-z       (pos/pos-z block-pos)
            :placer-name (or (:placer-name tile) "Unknown")}
           (schema/build-sync-packet-fields matrix-fields container))))

(defn apply-matrix-sync-payload! [payload]
  (sync-helpers/apply-sync-payload-template!
    payload
    (sync-field-mappings)
    "matrix"))

(defn extract-position [sync-data world]
  (sync-helpers/extract-position sync-data world))

;; ============================================================================
;; Component Builders
;; ============================================================================

;; ============================================================================
;; InfoArea Builder (使用共享TechUI组件)
;; ============================================================================

(defn rebuild-info-area!
  "Rebuild InfoArea based on network state (参照Scala GuiMatrix2.rebuildInfo)
  
  Args:
  - info-area: InfoArea widget
  - tile: TileMatrix
  - player: EntityPlayer
  - data: MatrixNetworkData"
  [info-area tile player data]
  (try
    (let [placer (try (.getPlacerName ^ IWirelessMatrix tile) (catch Exception _ (:owner data)))
          player-name (try (entity/player-get-name player) (catch Exception _ (str player)))
          is-owner? (= (str placer) (str player-name))
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
          (let [y (tech-ui/add-sepline info-area "wireless_info" y)]
            (if is-owner?
              (let [y (tech-ui/add-property
                        info-area "ssid" (:ssid data) y
                        :editable? true
                        :on-change (fn [new-ssid]
                                    (send-change-ssid tile new-ssid)))
                    y (tech-ui/add-sepline info-area "change_pass" y)
                    y (tech-ui/add-property
                        info-area "password" (:password data) y
                        :editable? true
                        :masked? true
                        :on-change (fn [new-pass]
                                    (send-change-password tile new-pass)))]
                y)
              (let [y (tech-ui/add-property info-area "ssid" (:ssid data) y)
                    y (tech-ui/add-property info-area "password" (:password data) y
                                            :masked? true)]
                y)))
          (if is-owner?
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
                        (let [ssid (if-let [tb @ssid-cell] (comp/get-text tb) "")
                              pass (if-let [tb @pass-cell] (comp/get-text tb) "")]
                          (send-init-network tile ssid pass
                            (fn [success]
                              (when success
                                (send-gather-info tile
                                  (fn [new-data]
                                    (rebuild-info-area! info-area tile player new-data))))))))
                      y)]
              y)
            (tech-ui/add-sepline info-area "wireless_noinit" y)))))
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
    (let [tile (or (:tile-entity container)
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
      ;(cgui/add-widget! main-widget (:window inv-page))
      
      ;; Position info area
      (cgui/set-position! info-area
        (+ (cgui/get-width main-widget) 7)
        5)
      
      ;; Initialize network data and build InfoArea
      (send-gather-info tile
        (fn [data]
          (rebuild-info-area! info-area tile player data)))
      
      ;; Add info area to main widget
      (cgui/add-widget! main-widget info-area)
      
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
        base (cgui/create-cgui-screen-container root minecraft-container)]
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
