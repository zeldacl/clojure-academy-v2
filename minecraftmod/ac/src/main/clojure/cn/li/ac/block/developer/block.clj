(ns cn.li.ac.block.developer.block
  "Developer block - 3x3x3 multi-block for ability development.

  Two tiers: Normal and Advanced
  - Normal: 1 user, slower development
  - Advanced: 2 users, faster development

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.developer.config :as dev-config]
            [cn.li.ac.block.developer.schema :as dev-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessUser]))

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
  "Validate 3x3x3 structure"
  [level pos block-spec]
  (try
    (bdsl/is-multi-block-complete? level pos block-spec)
    (catch Exception e
      (log/debug "Developer structure validation failed:" (ex-message e))
      false)))

;; ============================================================================
;; Development Logic
;; ============================================================================

(defn- tick-development
  "Process development progress"
  [state]
  (if (:is-developing state false)
    (let [tier (keyword (:tier state "normal"))
          config (dev-config/tier-config tier)
          energy (:energy state 0.0)
          energy-cost (:energy-per-tick config 100.0)]
      (if (>= energy energy-cost)
        (let [progress (:development-progress state 0.0)
              speed (:development-speed config 1.0)
              new-progress (+ progress speed)
              new-energy (- energy energy-cost)]
          (assoc state
                 :development-progress new-progress
                 :energy new-energy))
        ;; Not enough energy, stop development
        (assoc state :is-developing false)))
    state))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- developer-tick-fn
  "Tick handler for developer controller"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) dev-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Validate structure periodically
          state (if (zero? (mod ticker dev-config/validate-interval))
                  (let [block-spec (bdsl/get-block "developer-normal")
                        valid? (validate-structure level pos block-spec)]
                    (assoc state :structure-valid valid?))
                  state)

          ;; Process development
          state (if (and (:structure-valid state false)
                         (zero? (mod ticker dev-config/development-tick-interval)))
                  (tick-development state)
                  state)]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-developer-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :developer world pos)
        (do (log/error "Developer GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Developer GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) dev-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state 100000.0)
         :tier (:tier state "normal")
         :user-name (:user-name state "")
         :development-progress (:development-progress state 0.0)
         :is-developing (:is-developing state false)
         :structure-valid (:structure-valid state false)})
      {:energy 0.0 :max-energy 0.0 :tier "normal" :user-name ""
       :development-progress 0.0 :is-developing false :structure-valid false})))

(defn- handle-start-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) dev-default-state)
        player-uuid (str player)
        player-name (str player)]
        (if (:structure-valid state false)
          (do
            (platform-be/set-custom-state! tile
              (assoc state
                     :is-developing true
                     :user-uuid player-uuid
                     :user-name player-name))
            {:success true})
          {:success false :reason "invalid-structure"}))
      {:success false :reason "no-tile"})))

(defn- handle-stop-development [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (do
        (platform-be/set-custom-state! tile
          (assoc (or (platform-be/get-custom-state tile) dev-default-state)
                 :is-developing false))
        {:success true})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :start-development) handle-start-development)
  (net-server/register-handler (msg :stop-development) handle-stop-development)
  (log/info "Developer network handlers registered"))

;; ============================================================================
;; Capability Implementation
;; ============================================================================

(deftype WirelessUserImpl [be]
  IWirelessUser)

;; ============================================================================
;; Block Definitions
;; ============================================================================

;; Calculate 3x3x3 positions (27 blocks)
(def multiblock-positions
  (for [x (range 3)
        y (range 3)
        z (range 3)]
    [x y z]))

(defonce ^:private developer-installed? (atom false))

(defn init-developer!
  []
  (when (compare-and-set! developer-installed? false true)
    (msg-registry/register-block-messages! :developer [:get-status :start-development :stop-development])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "developer-normal"
        {:registry-name "developer_normal"
         :impl :scripted
         :blocks ["developer-normal"]
         :tick-fn developer-tick-fn
         :read-nbt-fn dev-scripted-load-fn
         :write-nbt-fn dev-scripted-save-fn}))
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "developer-advanced"
        {:registry-name "developer_advanced"
         :impl :scripted
         :blocks ["developer-advanced"]
         :tick-fn developer-tick-fn
         :read-nbt-fn dev-scripted-load-fn
         :write-nbt-fn dev-scripted-save-fn}))
    (platform-cap/declare-capability! :wireless-user IWirelessUser
      (fn [be _side] (->WirelessUserImpl be)))
    (tile-logic/register-tile-capability! "developer-normal" :wireless-user)
    (tile-logic/register-tile-capability! "developer-advanced" :wireless-user)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "developer-normal"
        {:multi-block {:positions multiblock-positions
                       :rotation-center [1.0 1.0 1.0]}
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
        {:multi-block {:positions multiblock-positions
                       :rotation-center [1.0 1.0 1.0]}
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
    (log/info "Initialized Developer blocks (Normal and Advanced)")))

