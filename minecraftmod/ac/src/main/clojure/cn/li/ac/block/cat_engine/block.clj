(ns cn.li.ac.block.cat-engine.block
  "Cat Engine block - AcademyCraft-compatible wireless generator adapter.

  Behavior aligned with AcademyCraft 1.12:
  - Right click toggles link/unlink with nearby wireless nodes
  - No GUI/menu/container
  - Server-side energy buffer regenerated each tick
  - Client-side custom renderer handles visual rotor animation"
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.ac.block.cat-engine.config :as cat-config]
            [cn.li.ac.block.cat-engine.schema :as cat-schema]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator IWirelessNode]))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def cat-state-schema
  (state-schema/filter-server-fields cat-schema/cat-engine-schema))

(def cat-default-state
  (state-schema/schema->default-state cat-state-schema))

(def cat-scripted-load-fn
  (state-schema/schema->load-fn cat-state-schema))

(def cat-scripted-save-fn
  (state-schema/schema->save-fn cat-state-schema))

;; ============================================================================
;; Wireless Generator Capability
;; ============================================================================

(deftype CatEngineGeneratorImpl [be]
  IWirelessGenerator

  (getEnergy [_]
    (let [state (or (platform-be/get-custom-state be) cat-default-state)]
      (double (max 0.0 (double (get state :energy 0.0))))))

  (setEnergy [_ energy]
    (let [state (or (platform-be/get-custom-state be) cat-default-state)
          max-energy (double (get state :max-energy cat-config/max-energy))
          clamped (-> (double energy) (max 0.0) (min max-energy))]
      (platform-be/set-custom-state! be (assoc state :energy clamped))
      (platform-be/set-changed! be)))

  (getProvidedEnergy [_ req]
    (let [state (or (platform-be/get-custom-state be) cat-default-state)
          energy (double (get state :energy 0.0))
          max-out (double cat-config/generator-bandwidth)
          actual (min (double req) energy max-out)]
      (when (pos? actual)
        (platform-be/set-custom-state! be (assoc state :energy (- energy actual)))
        (platform-be/set-changed! be))
      (double actual)))

  (getGeneratorBandwidth [_]
    (double cat-config/generator-bandwidth))

  Object
  (toString [_]
    (str "CatEngineGeneratorImpl@" (pos/position-get-block-pos be))))

;; ============================================================================
;; Node Search / Link State
;; ============================================================================

(defn- find-nearby-nodes
  [level block-pos]
  (try
    (vec (wireless-api/get-nodes-in-range level block-pos))
    (catch Exception e
      (log/error "Cat Engine node search failed:" (ex-message e))
      [])))

(defn- get-linked-node
  ^IWirelessNode [be]
  (when-let [conn (try (wireless-api/get-node-conn-by-generator be)
                       (catch Exception _ nil))]
    (try
      (node-conn/get-node conn)
      (catch Exception _ nil))))

(defn- sync-link-state
  [be state]
  (if-let [node (get-linked-node be)]
    (let [p (.getBlockPos node)
          node-name (try (str (.getNodeName node)) (catch Exception _ ""))]
      (assoc state
             :has-link true
             :linked-node-name node-name
             :linked-node-x (if p (pos/pos-x p) 0)
             :linked-node-y (if p (pos/pos-y p) 0)
             :linked-node-z (if p (pos/pos-z p) 0)))
    (assoc state
           :has-link false
           :linked-node-name ""
           :linked-node-x 0
           :linked-node-y 0
           :linked-node-z 0)))

(defn- attempt-link-to-node
  [be node]
  (try
    (boolean (wireless-api/link-generator-to-node! be node "" false))
    (catch Exception e
      (log/error "Cat Engine link attempt failed:" (ex-message e))
      false)))

(defn- refresh-link-state! [be]
  (let [state0 (or (platform-be/get-custom-state be) cat-default-state)
        state1 (sync-link-state be state0)]
    (when (not= state1 state0)
      (platform-be/set-custom-state! be state1)
      (platform-be/set-changed! be))))

(defn- right-click-result
  [message-key & args]
  {:consume? true
   :messages [{:type :translatable
               :key message-key
               :args (vec args)}]})

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- cat-tick-fn
  "Server tick for Cat Engine.
  Keeps a finite internal buffer that regenerates every tick, matching legacy behavior."
  [level _pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state0 (or (platform-be/get-custom-state be) cat-default-state)
          ticker (inc (long (get state0 :update-ticker 0)))
          energy (double (get state0 :energy 0.0))
          max-energy (double (get state0 :max-energy cat-config/max-energy))
          generated (min (double cat-config/generation-per-tick)
                         (max 0.0 (- max-energy energy)))
          energy* (+ energy generated)
          state1 (-> state0
                     (assoc :update-ticker ticker)
                     (assoc :energy energy*)
                     (assoc :max-energy max-energy)
                     (assoc :this-tick-gen generated)
                     (assoc :gen-speed generated))
          state2 (sync-link-state be state1)]
      (when (not= state2 state0)
        (platform-be/set-custom-state! be state2)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Interaction Logic
;; ============================================================================

(defn- cat-right-click!
  [{:keys [world pos] :as _ctx}]
  (let [be (and world pos (world/world-get-tile-entity* world pos))]
    (cond
      (nil? be)
      {:consume? true}

      (world/world-is-client-side* world)
      {:consume? true}

      (wireless-api/is-generator-linked? be)
      (do
        (wireless-api/unlink-generator-from-node! be)
        (refresh-link-state! be)
        (right-click-result "ac.cat_engine.unlink"))

      :else
      (let [nodes (find-nearby-nodes world pos)]
        (if (empty? nodes)
          (right-click-result "ac.cat_engine.notfound")
          (let [target-node (nth nodes (rand-int (count nodes)))
                linked? (attempt-link-to-node be target-node)]
            (if linked?
              (do
                (refresh-link-state! be)
                (right-click-result "ac.cat_engine.linked"
                                    (try (str (.getNodeName ^IWirelessNode target-node))
                                         (catch Exception _ "Node"))))
              (right-click-result "ac.cat_engine.notfound"))))))))

;; ============================================================================
;; Tile Registration
;; ============================================================================


(defonce ^:private cat-engine-installed? (atom false))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-cat-engine!
  []
  (when (compare-and-set! cat-engine-installed? false true)
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "cat-engine"
        {:registry-name "cat_engine"
         :impl :scripted
         :blocks ["cat-engine"]
         :tick-fn cat-tick-fn
         :read-nbt-fn cat-scripted-load-fn
         :write-nbt-fn cat-scripted-save-fn}))
    (platform-cap/declare-capability! :cat-engine-generator IWirelessGenerator
      (fn [be _side] (->CatEngineGeneratorImpl be)))
    (tile-logic/register-tile-capability! "cat-engine" :cat-engine-generator)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "cat-engine"
        {:registry-name "cat_engine"
         :physical {:material :metal
                    :hardness 2.0
                    :resistance 6.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 1
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "cat_engine")}
                     :flat-item-icon? true
                     :light-level 0}
                  :events {:on-right-click cat-right-click!}}))
                (hooks/register-client-renderer! 'cn.li.ac.block.cat-engine.render/init!)
    (log/info "Initialized Cat Engine block")))

