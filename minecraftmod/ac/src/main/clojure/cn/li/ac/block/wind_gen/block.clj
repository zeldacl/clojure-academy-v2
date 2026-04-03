(ns cn.li.ac.block.wind-gen.block
  "Wind Generator blocks - 3-part structure for height-based energy generation.

  Structure:
  - Main (top): Generates energy based on height, has blades
  - Pillar (middle): Support structure, can be stacked
  - Base (bottom): Energy storage and wireless capability

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.wind-gen.config :as wind-config]
            [cn.li.ac.block.wind-gen.schema :as wind-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator]))

;; ============================================================================
;; Message Registration
;; ============================================================================

(msg-registry/register-block-messages! :wind-gen
  [:get-status-main :get-status-base])

(defn- msg [action] (msg-registry/msg :wind-gen action))

;; ============================================================================
;; Schema Generation - Main Block
;; ============================================================================

(def main-state-schema
  (state-schema/filter-server-fields wind-schema/wind-gen-main-schema))

(def main-default-state
  (state-schema/schema->default-state main-state-schema))

(def main-scripted-load-fn
  (state-schema/schema->load-fn main-state-schema))

(def main-scripted-save-fn
  (state-schema/schema->save-fn main-state-schema))

;; ============================================================================
;; Schema Generation - Base Block
;; ============================================================================

(def base-state-schema
  (state-schema/filter-server-fields wind-schema/wind-gen-base-schema))

(def base-default-state
  (state-schema/schema->default-state base-state-schema))

(def base-scripted-load-fn
  (state-schema/schema->load-fn base-state-schema))

(def base-scripted-save-fn
  (state-schema/schema->save-fn base-state-schema))

;; ============================================================================
;; Schema Generation - Pillar Block
;; ============================================================================

(def pillar-state-schema
  (state-schema/filter-server-fields wind-schema/wind-gen-pillar-schema))

(def pillar-default-state
  (state-schema/schema->default-state pillar-state-schema))

(def pillar-scripted-load-fn
  (state-schema/schema->load-fn pillar-state-schema))

(def pillar-scripted-save-fn
  (state-schema/schema->save-fn pillar-state-schema))

;; ============================================================================
;; Structure Validation
;; ============================================================================

(defn- find-base-below
  "Find the base block below the current position"
  [level pos max-distance]
  (loop [y (dec (pos/pos-y pos))
         distance 0]
    (when (and (>= y -64)
               (< distance max-distance))
      (let [check-pos (pos/create-block-pos (pos/pos-x pos) y (pos/pos-z pos))
            be (world/world-get-tile-entity level check-pos)]
        (if (and be (= "wind-gen-base" (platform-be/get-block-id be)))
          check-pos
          (let [block-id (when be (platform-be/get-block-id be))]
            (if (= "wind-gen-pillar" block-id)
              (recur (dec y) (inc distance))
              nil)))))))

(defn- find-main-above
  "Find the main block above the current position"
  [level pos max-distance]
  (loop [y (inc (pos/pos-y pos))
         distance 0]
    (when (and (<= y 320)
               (< distance max-distance))
      (let [check-pos (pos/create-block-pos (pos/pos-x pos) y (pos/pos-z pos))
            be (world/world-get-tile-entity level check-pos)]
        (if (and be (= "wind-gen-main" (platform-be/get-block-id be)))
          check-pos
          (let [block-id (when be (platform-be/get-block-id be))]
            (if (= "wind-gen-pillar" block-id)
              (recur (inc y) (inc distance))
              nil)))))))

(defn- validate-structure-from-main
  "Validate structure from main block perspective"
  [level pos]
  (let [base-pos (find-base-below level pos 64)]
    (boolean base-pos)))


;; ============================================================================
;; Main Block Tick Logic
;; ============================================================================

(defn- update-wind-speed
  "Update wind speed with random variation"
  [state]
  (if wind-config/wind-variation-enabled?
    (let [ticker (:wind-change-ticker state 0)]
      (if (zero? (mod ticker wind-config/wind-change-interval))
        (let [new-multiplier (+ wind-config/wind-variation-min
                                (* (rand)
                                   (- wind-config/wind-variation-max
                                      wind-config/wind-variation-min)))]
          (assoc state :wind-multiplier new-multiplier))
        state))
    state))

(defn- main-tick-fn
  "Tick handler for wind generator main block"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) main-default-state)
          ticker (inc (get state :update-ticker 0))
          wind-ticker (inc (get state :wind-change-ticker 0))
          state (assoc state :update-ticker ticker :wind-change-ticker wind-ticker)

          ;; Validate structure periodically
          state (if (zero? (mod ticker wind-config/validate-interval))
                  (let [valid? (validate-structure-from-main level pos)]
                    (assoc state :structure-valid valid?))
                  state)

          ;; Update wind speed
          state (update-wind-speed state)

          ;; Generate energy if structure is valid
          generating? (:structure-valid state false)
          y-level (pos/pos-y pos)
          wind-mult (:wind-multiplier state 1.0)
          gen-rate (if generating?
                     (wind-config/calculate-generation-rate y-level wind-mult)
                     0.0)
          current-energy (double (get state :energy 0.0))
          max-energy (double (get state :max-energy wind-config/max-energy-main))
          new-energy (min max-energy (+ current-energy gen-rate))
          changed? (and (> gen-rate 0) (not= new-energy current-energy))

          status (cond
                   (not generating?) "STOPPED"
                   (>= current-energy max-energy) "FULL"
                   :else "GENERATING")

          new-state (cond-> (assoc state
                                   :status status
                                   :gen-speed gen-rate)
                      changed? (assoc :energy new-energy))]

      (when (not= new-state state)
        (platform-be/set-custom-state! be new-state)
        (when changed?
          (platform-be/set-changed! be))))))

;; ============================================================================
;; Base Block Tick Logic
;; ============================================================================

(defn- base-tick-fn
  "Tick handler for wind generator base block"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) base-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Validate structure periodically
          state (if (zero? (mod ticker wind-config/validate-interval))
                  (let [main-pos (find-main-above level pos 64)
                        valid? (boolean main-pos)]
                    (if main-pos
                      (assoc state
                             :structure-valid valid?
                             :main-pos-x (pos/pos-x main-pos)
                             :main-pos-y (pos/pos-y main-pos)
                             :main-pos-z (pos/pos-z main-pos))
                      (assoc state :structure-valid valid?)))
                  state)

          ;; Transfer energy from main block if structure is valid
          state (if (and (:structure-valid state false)
                         (zero? (mod ticker 20)))
                  (let [main-x (:main-pos-x state)
                        main-y (:main-pos-y state)
                        main-z (:main-pos-z state)]
                    (when (and main-x main-y main-z)
                      (let [main-pos (pos/create-block-pos main-x main-y main-z)
                            main-be (world/world-get-tile-entity level main-pos)]
                        (when main-be
                          (let [main-state (platform-be/get-custom-state main-be)
                                main-energy (double (get main-state :energy 0.0))]
                            (when (pos? main-energy)
                              (let [base-energy (double (get state :energy 0.0))
                                    base-max (double (get state :max-energy wind-config/max-energy-base))
                                    can-receive (- base-max base-energy)
                                    transfer (min main-energy can-receive)]
                                (when (pos? transfer)
                                  (platform-be/set-custom-state! main-be
                                    (assoc main-state :energy (- main-energy transfer)))
                                  (platform-be/set-changed! main-be)
                                  (assoc state :energy (+ base-energy transfer))))))))))
                  state)

          status (cond
                   (not (:structure-valid state false)) "INVALID"
                   (>= (get state :energy 0.0) (get state :max-energy wind-config/max-energy-base)) "FULL"
                   (pos? (get state :energy 0.0)) "ACTIVE"
                   :else "IDLE")]

      (when (not= (:status state) status)
        (let [new-state (assoc state :status status)]
          (platform-be/set-custom-state! be new-state)
          (platform-be/set-changed! be))))))

;; ============================================================================
;; Pillar Block Tick Logic
;; ============================================================================

(defn- pillar-tick-fn
  "Tick handler for wind generator pillar block"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) pillar-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Validate structure periodically
          state (if (zero? (mod ticker wind-config/validate-interval))
                  (let [main-pos (find-main-above level pos 64)
                        base-pos (find-base-below level pos 64)
                        valid? (and (boolean main-pos) (boolean base-pos))]
                    (assoc state :structure-valid valid?))
                  state)]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)))))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-wind-main-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :wind-gen-main world pos)
        (do (log/error "Wind Gen Main GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Wind Gen Main GUI:" (ex-message e))
        nil))))

(defn- open-wind-base-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :wind-gen-base world pos)
        (do (log/error "Wind Gen Base GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Wind Gen Base GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status-main [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) main-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state wind-config/max-energy-main)
         :gen-speed (:gen-speed state 0.0)
         :wind-multiplier (:wind-multiplier state 1.0)
         :status (:status state "STOPPED")
         :structure-valid (:structure-valid state false)})
      {:energy 0.0 :max-energy 0.0 :gen-speed 0.0 :status "ERROR"})))

(defn- handle-get-status-base [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) base-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state wind-config/max-energy-base)
         :status (:status state "IDLE")
         :structure-valid (:structure-valid state false)})
      {:energy 0.0 :max-energy 0.0 :status "ERROR"})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status-main) handle-get-status-main)
  (net-server/register-handler (msg :get-status-base) handle-get-status-base)
  (log/info "Wind Generator network handlers registered"))

;; ============================================================================
;; Tile Registration
;; ============================================================================

(tdsl/deftile wind-gen-main-tile
  :id "wind-gen-main"
  :registry-name "wind_gen_main"
  :impl :scripted
  :blocks ["wind-gen-main"]
  :tick-fn main-tick-fn
  :read-nbt-fn main-scripted-load-fn
  :write-nbt-fn main-scripted-save-fn)

(tdsl/deftile wind-gen-base-tile
  :id "wind-gen-base"
  :registry-name "wind_gen_base"
  :impl :scripted
  :blocks ["wind-gen-base"]
  :tick-fn base-tick-fn
  :read-nbt-fn base-scripted-load-fn
  :write-nbt-fn base-scripted-save-fn)

(tdsl/deftile wind-gen-pillar-tile
  :id "wind-gen-pillar"
  :registry-name "wind_gen_pillar"
  :impl :scripted
  :blocks ["wind-gen-pillar"]
  :tick-fn pillar-tick-fn
  :read-nbt-fn pillar-scripted-load-fn
  :write-nbt-fn pillar-scripted-save-fn)

;; ============================================================================
;; Capability Registration
;; ============================================================================

(platform-cap/declare-capability! :wind-generator IWirelessGenerator
  (fn [be _side] (impls/->WirelessGeneratorImpl be)))

(tile-logic/register-tile-capability! "wind-gen-base" :wind-generator)

;; ============================================================================
;; Block Definitions
;; ============================================================================

(bdsl/defblock wind-gen-main
  :registry-name "wind_gen_main"
  :physical {:material :metal
             :hardness 3.0
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "wind_gen_main")}
              :flat-item-icon? true}
  :events {:on-right-click open-wind-main-gui!})

(bdsl/defblock wind-gen-base
  :registry-name "wind_gen_base"
  :physical {:material :metal
             :hardness 3.0
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "wind_gen_base")}
              :flat-item-icon? true}
  :events {:on-right-click open-wind-base-gui!})

(bdsl/defblock wind-gen-pillar
  :registry-name "wind_gen_pillar"
  :physical {:material :metal
             :hardness 3.0
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :metal}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "wind_gen_pillar")}
              :flat-item-icon? true})

;; ============================================================================
;; Auto-Registration
;; ============================================================================

(hooks/register-network-handler! register-network-handlers!)

(defn init-wind-gen!
  []
  (log/info "Initialized Wind Generator blocks (3-part structure)"))
