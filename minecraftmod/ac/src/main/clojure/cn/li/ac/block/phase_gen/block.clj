(ns cn.li.ac.block.phase-gen.block
  "Phase Generator block - generates energy from imaginary phase liquid.

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
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.phase-gen.config :as phase-config]
            [cn.li.ac.block.phase-gen.schema :as phase-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

(defn- msg [action] (msg-registry/msg :phase-gen action))

(def phase-state-schema
  (state-schema/filter-server-fields phase-schema/phase-gen-schema))

(def phase-default-state
  (state-schema/schema->default-state phase-state-schema))

(def phase-scripted-load-fn
  (state-schema/schema->load-fn phase-state-schema))

(def phase-scripted-save-fn
  (state-schema/schema->save-fn phase-state-schema))

(defn- check-for-phase-liquid
  "Check adjacent blocks for imaginary phase liquid source"
  [_level _pos]
  false)

(defn- phase-tick-fn
  "Tick handler for phase generator"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) phase-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)
          state (if (zero? (mod ticker phase-config/validate-interval))
                  (let [has-liquid? (check-for-phase-liquid level pos)]
                    (assoc state :has-liquid-source has-liquid?))
                  state)
          generating? (:has-liquid-source state false)
          liquid-amount (get state :liquid-amount 0)
          gen-rate (if generating?
                     (phase-config/calculate-generation-rate liquid-amount)
                     0.0)
          current-energy (double (get state :energy 0.0))
          max-energy (double (get state :max-energy phase-config/max-energy))
          new-energy (min max-energy (+ current-energy gen-rate))
          changed? (and (> gen-rate 0) (not= new-energy current-energy))
          new-liquid-amount (if (and generating? (> gen-rate 0))
                              (max 0 (- liquid-amount phase-config/liquid-consumption-rate))
                              liquid-amount)
          status (cond
                   (not generating?) "NO_LIQUID"
                   (>= current-energy max-energy) "FULL"
                   (> gen-rate 0) "GENERATING"
                   :else "IDLE")
          new-state (cond-> (assoc state
                                   :status status
                                   :gen-speed gen-rate
                                   :liquid-amount new-liquid-amount)
                      changed? (assoc :energy new-energy))]
      (when (not= new-state state)
        (platform-be/set-custom-state! be new-state)
        (when changed?
          (platform-be/set-changed! be))))))

(defn- open-phase-gen-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :phase-gen world pos)
        (do (log/error "Phase Gen GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Phase Gen GUI:" (ex-message e))
        nil))))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) phase-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state phase-config/max-energy)
         :gen-speed (:gen-speed state 0.0)
         :liquid-amount (:liquid-amount state 0)
         :status (:status state "IDLE")
         :has-liquid-source (:has-liquid-source state false)})
      {:energy 0.0 :max-energy 0.0 :gen-speed 0.0 :status "ERROR"})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (log/info "Phase Generator network handlers registered"))

(defonce ^:private phase-gen-installed? (atom false))

(defn init-phase-gen!
  []
  (when (compare-and-set! phase-gen-installed? false true)
    (msg-registry/register-block-messages! :phase-gen [:get-status])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :impl :scripted
         :blocks ["phase-gen"]
         :tick-fn phase-tick-fn
         :read-nbt-fn phase-scripted-load-fn
         :write-nbt-fn phase-scripted-save-fn}))
    (platform-cap/declare-capability! :phase-generator IWirelessGenerator
      (fn [be _side] (impls/->WirelessGeneratorImpl be)))
    (tile-logic/register-tile-capability! "phase-gen" :phase-generator)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "phase-gen"
        {:registry-name "phase_gen"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "phase_gen")}
                     :flat-item-icon? true}
         :events {:on-right-click open-phase-gen-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Phase Generator block")))

