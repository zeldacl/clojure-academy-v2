(ns cn.li.ac.block.developer.block
  "Developer block — irregular multi-block for ability development (normal / advanced).

  Footprint matches the classic AcademyCraft developer shape: controller at origin plus
  seven part cells in the +Z column slice (three tall at z=1 and z=2, one block above
  at z=0), not a 3×3×3 cube.

  Behaviour aligned with `BlockDeveloper` / `TileDeveloper`: single GUI session per station,
  wireless IF receiver buffer, periodic stimulation while developing, structure checks.

  State: `ScriptedBlockEntity.customState` (Clojure maps)."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.developer.config :as dev-config]
            [cn.li.ac.block.developer.schema :as dev-schema]
            [cn.li.ac.block.energy-converter.wireless-impl :as wireless-impl]
            [cn.li.ac.wireless.api :as wapi]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessNode IWirelessReceiver]))

(defn- msg [action] (msg-registry/msg :developer action))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def dev-state-schema
  (state-schema/filter-server-fields dev-schema/developer-schema))

(def dev-default-state
  (state-schema/schema->default-state dev-state-schema))

(def dev-scripted-load-fn
  (state-schema/schema->load-fn dev-state-schema))

(def dev-scripted-save-fn
  (state-schema/schema->save-fn dev-state-schema))

;; ============================================================================
;; Multi-block Validation
;; ============================================================================

(defn- validate-structure
  "True when every cell of this controller's multi-block spec is present."
  [level pos block-spec]
  (try
    (bdsl/is-multi-block-complete? level pos block-spec)
    (catch Exception e
      (log/debug "Developer structure validation failed:" (ex-message e))
      false)))

;; ============================================================================
;; Tier + development (classic stimulation pacing)
;; ============================================================================

(defn- tier-kw-for-block-id
  [block-id]
  (if (= (name (or block-id "")) "developer-advanced")
    :advanced
    :normal))

(defn- ensure-inventory-shape
  [state]
  (let [v (vec (:inventory state []))
        v2 (vec (take 2 (concat v (repeat nil))))]
    (assoc state :inventory v2)))

(defn- ensure-tier-defaults
  "Apply tier-derived max energy / wireless bandwidth from controller block id."
  [state be]
  (let [bid (platform-be/get-block-id be)
        tier (tier-kw-for-block-id bid)
        cfg (dev-config/tier-config tier)]
    (-> state
        (merge {:tier (name tier)
                :max-energy (:max-energy cfg)
                :wireless-bandwidth (:wireless-bandwidth cfg)})
        ensure-inventory-shape)))

(defn- tick-development
  "Each stimulation interval, consume one lump of IF and advance progress (classic `cps`)."
  [state ^long ticker]
  (if-not (:is-developing state)
    state
    (let [tier (keyword (:tier state :normal))
          {:keys [energy-per-stimulation stimulation-interval-ticks]}
          (dev-config/tier-config tier)]
      (if (zero? (mod ticker (long stimulation-interval-ticks)))
        (let [e (double (:energy state 0.0))
              cost (double energy-per-stimulation)]
          (if (>= e cost)
            (-> state
                (update :energy - cost)
                (update :development-progress + 1.0))
            (assoc state :is-developing false)))
        state))))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- developer-tick-fn
  "Server tick: tier defaults, structure validation, stimulation while developing."
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) dev-default-state)
          ticker (inc (long (get state :update-ticker 0)))
          state (-> state
                    (assoc :update-ticker ticker)
                    (ensure-tier-defaults be))
          state (if (zero? (mod ticker dev-config/validate-interval))
                  (let [block-id (platform-be/get-block-id be)
                        block-spec (some-> block-id bdsl/get-block)
                        valid? (if block-spec
                                 (validate-structure level pos block-spec)
                                 false)]
                    (assoc state :structure-valid valid?))
                  state)
          ;; Break development when structure is broken (classic multiblock dependency)
          state (if-not (:structure-valid state false)
                  (assoc state :is-developing false)
                  state)
          state (if (:structure-valid state false)
                  (tick-development state ticker)
                  state)
          ;; Flush wireless inject accumulator → last-tick for GUI (real sync bar).
          state (-> state
                     (assoc :wireless-inject-last-tick
                            (double (:wireless-inject-this-tick state 0.0)))
                     (assoc :wireless-inject-this-tick 0.0))]
      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-developer-gui!
  "Server: claim session (`TileDeveloper#use`) when idle or same player; then open TechUI.
  Client: open screen metadata only (Forge opens menu from server)."
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (if (world/world-is-client-side* world)
          (open-gui-by-type player :developer world pos)
          (when-let [be (world/world-get-tile-entity* world pos)]
            (let [state (or (platform-be/get-custom-state be) dev-default-state)
                  pid (str (entity/player-get-uuid player))
                  cur (str (:user-uuid state ""))]
              (when (or (str/blank? cur) (= cur pid))
                (let [state (assoc state
                              :user-uuid pid
                              :user-name (entity/player-get-name player))]
                  (platform-be/set-custom-state! be state)
                  (platform-be/set-changed! be)
                  (open-gui-by-type player :developer world pos))))))
        (do (log/error "Developer GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Developer GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- node->info
  [^IWirelessNode node]
  (when node
    (let [p (try (.getBlockPos node) (catch Exception _ nil))
          pw (try (str (.getPassword node)) (catch Exception _ ""))]
      {:node-name     (try (str (.getNodeName node)) (catch Exception _ "Node"))
       :pos-x         (when p (pos/pos-x p))
       :pos-y         (when p (pos/pos-y p))
       :pos-z         (when p (pos/pos-z p))
       :is-encrypted? (not (str/blank? pw))})))

(defn- get-linked-node-for-receiver
  [tile]
  (when-let [conn (try (wapi/get-node-conn-by-receiver tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) dev-default-state)
            linked-node (get-linked-node-for-receiver tile)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state 50000.0)
         :tier (:tier state "normal")
         :user-uuid (:user-uuid state "")
         :user-name (:user-name state "")
         :development-progress (:development-progress state 0.0)
         :is-developing (:is-developing state false)
         :structure-valid (:structure-valid state false)
         :linked (some-> linked-node node->info)
         :avail []})
      {:energy 0.0 :max-energy 0.0 :tier "normal" :user-uuid "" :user-name ""
       :development-progress 0.0 :is-developing false :structure-valid false
       :linked nil :avail []})))

(defn- handle-start-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if-not tile
      {:success false :reason "no-tile"}
      (let [state (or (platform-be/get-custom-state tile) dev-default-state)
            pid (str (entity/player-get-uuid player))
            holder (str (:user-uuid state ""))]
        (cond
          (not (:structure-valid state false))
          {:success false :reason "invalid-structure"}

          (and (not (str/blank? holder)) (not= holder pid))
          {:success false :reason "wrong-user"}

          :else
          (do
            (platform-be/set-custom-state! tile
              (assoc state
                     :is-developing true
                     :user-uuid pid
                     :user-name (entity/player-get-name player)))
            (platform-be/set-changed! tile)
            {:success true}))))))

(defn- handle-stop-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) dev-default-state)]
        (platform-be/set-custom-state! tile (assoc state :is-developing false))
        (platform-be/set-changed! tile)
        {:success true})
      {:success false})))

(defn- handle-list-nodes [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [tile-pos (pos/position-get-block-pos tile)
            linked-node (get-linked-node-for-receiver tile)
            linked-pos (when linked-node (try (.getBlockPos ^IWirelessNode linked-node) (catch Exception _ nil)))
            nodes (if tile-pos (wapi/get-nodes-in-range world tile-pos) [])
            avail (->> nodes
                       (remove (fn [^IWirelessNode n]
                                 (let [p (try (.getBlockPos n) (catch Exception _ nil))]
                                   (and p linked-pos
                                        (= (pos/pos-x p) (pos/pos-x linked-pos))
                                        (= (pos/pos-y p) (pos/pos-y linked-pos))
                                        (= (pos/pos-z p) (pos/pos-z linked-pos))))))
                       (mapv node->info))]
        {:linked (node->info linked-node) :avail avail})
      {:linked nil :avail []})))

(defn- handle-connect [payload player]
  (let [world (net-helpers/get-world player)
        recv (net-helpers/get-tile-at world payload)
        node-pos (select-keys payload [:node-x :node-y :node-z])
        pass (:password payload "")
        need-auth? (boolean (:need-auth? payload true))]
    (if (and world recv (every? number? (vals node-pos)))
      (if-let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
                                                   :pos-y (:node-y node-pos)
                                                   :pos-z (:node-z node-pos)})]
        {:success (boolean (wapi/link-receiver-to-node! recv node pass need-auth?))}
        {:success false})
      {:success false})))

(defn- handle-disconnect [payload player]
  (let [world (net-helpers/get-world player)
        recv (net-helpers/get-tile-at world payload)]
    (if (and world recv)
      (do (wapi/unlink-receiver-from-node! recv)
          {:success true})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :start-development) handle-start-development)
  (net-server/register-handler (msg :stop-development) handle-stop-development)
  (net-server/register-handler (msg :list-nodes) handle-list-nodes)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Developer network handlers registered"))

;; ============================================================================
;; Capability — wireless receiver (IF buffer + node link)
;; ============================================================================

(defn- create-dev-receiver-cap
  [be]
  (wireless-impl/create-wireless-receiver
    be
    (fn [] (or (platform-be/get-custom-state be) dev-default-state))
    (fn [s]
      (platform-be/set-custom-state! be s)
      (platform-be/set-changed! be))
    {:after-inject!
     (fn [^double accepted]
       (when (pos? accepted)
         (let [st (or (platform-be/get-custom-state be) dev-default-state)
               cur (double (:wireless-inject-this-tick st 0.0))]
           (platform-be/set-custom-state! be
             (assoc st :wireless-inject-this-tick (+ cur accepted)))
           (platform-be/set-changed! be))))}))

;; ============================================================================
;; Block Definitions
;; ============================================================================

;; Relative to controller at [0 0 0]. Same relative layout as the original
;; BlockDeveloper sub-blocks: one block above the foot, then two 3×1 pillars
;; along +Z (y = 0..2 at z = 1 and z = 2). List must include origin for mcmod DSL.
(def developer-multiblock-positions
  [[0 0 0]
   [0 1 0]
   [0 0 1] [0 1 1] [0 2 1]
   [0 0 2] [0 1 2] [0 2 2]])

(defonce ^:private developer-installed? (atom false))

(defn init-developer!
  []
  (when (compare-and-set! developer-installed? false true)
    (msg-registry/register-block-messages!
      :developer
      [:get-status :start-development :stop-development :list-nodes :connect :disconnect])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "developer-normal"
        {:registry-name "developer_normal"
         :impl :scripted
         ;; Same BlockEntityType for controller + parts (matches wireless-matrix).
         :blocks ["developer-normal" "developer-normal-part"]
         :tick-fn developer-tick-fn
         :read-nbt-fn dev-scripted-load-fn
         :write-nbt-fn dev-scripted-save-fn}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "developer-advanced"
        {:registry-name "developer_advanced"
         :impl :scripted
         :blocks ["developer-advanced" "developer-advanced-part"]
         :tick-fn developer-tick-fn
         :read-nbt-fn dev-scripted-load-fn
         :write-nbt-fn dev-scripted-save-fn}))
    (platform-cap/declare-capability! :wireless-receiver IWirelessReceiver
      (fn [be _side] (create-dev-receiver-cap be)))
    (tile-logic/register-tile-capability! "developer-normal" :wireless-receiver)
    (tile-logic/register-tile-capability! "developer-advanced" :wireless-receiver)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "developer-normal"
        {:multi-block {:positions developer-multiblock-positions
                       ;; Footprint floor in controller space; Z +1 block vs prior center (user: 往前一格).
                       ;; Legacy direction->rotation-center is for 2×2×2; use raw + Y override here.
                       :rotation-center [0.5 0.0 0.5]
                       :pivot-xz-override [0.0 0.0]
                       :tesr-use-raw-rotation-center? true
                       ;; Tile has no :direction yet; default :north would apply 180° in helper.
                       :tesr-y-deg-override 0.0}
         :multiblock-mode :controller-parts
         :controller-block-id "developer-normal"
         :part-block-id "developer-normal-part"
         :registry-name "developer_normal"
         :physical {:material :metal
                    :hardness 4.0
                    :resistance 10.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:light-level 1.0
                     :flat-item-icon? true
                     :textures {:all (modid/asset-path "block" "dev_normal")}}
         :events {:on-right-click open-developer-gui!}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "developer-normal-part"
        {:multiblock-mode :controller-parts
         :controller-block-id "developer-normal"
         :part-block-id "developer-normal-part"
         :registry-name "developer_normal_part"
         :physical {:material :metal
                    :hardness 4.0
                    :resistance 10.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:light-level 1.0
                     :has-item-form? false
                     :model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "dev_normal")}}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "developer-advanced"
        {:multi-block {:positions developer-multiblock-positions
                       :rotation-center [0.5 0.0 0.5]
                       :pivot-xz-override [0.0 0.0]
                       :tesr-use-raw-rotation-center? true
                       :tesr-y-deg-override 0.0}
         :multiblock-mode :controller-parts
         :controller-block-id "developer-advanced"
         :part-block-id "developer-advanced-part"
         :registry-name "developer_advanced"
         :physical {:material :metal
                    :hardness 5.0
                    :resistance 12.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:light-level 2.0
                     :flat-item-icon? true
                     :textures {:all (modid/asset-path "block" "dev_advanced")}}
         :events {:on-right-click open-developer-gui!}}))
    (bdsl/register-block!
      (bdsl/create-block-spec
        "developer-advanced-part"
        {:multiblock-mode :controller-parts
         :controller-block-id "developer-advanced"
         :part-block-id "developer-advanced-part"
         :registry-name "developer_advanced_part"
         :physical {:material :metal
                    :hardness 5.0
                    :resistance 12.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:light-level 2.0
                     :has-item-form? false
                     :model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "dev_advanced")}}}))
    (hooks/register-network-handler! register-network-handlers!)
    (hooks/register-client-renderer! 'cn.li.ac.block.developer.render/init!)
    (log/info "Initialized Developer blocks (Normal and Advanced)")))

