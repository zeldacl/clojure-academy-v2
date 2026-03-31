(ns cn.li.ac.block.solar-gen.block
  "Solar Generator block - wireless energy generator powered by sunlight.

  This file contains:
  - Block definition
  - Server-side logic (tick, NBT, energy generation)
  - Container functions

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as a Clojure
  persistent map."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.ac.block.role-impls :as impls]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.platform.item :as item]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.wireless.api :as helper]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid])
  (:import [cn.li.acapi.wireless IWirelessGenerator IWirelessNode]))

;; ============================================================================
;; Part 1: Constants
;; ============================================================================

(def ^:private max-energy 1000.0)

;; Message Registration
(msg-registry/register-block-messages! :generator
  [:get-status :list-nodes :connect :disconnect])

(defn- msg [action] (msg-registry/msg :generator action))

;; ============================================================================
;; Part 2: Helper Functions
;; ============================================================================

(defn- can-generate?
  "True when the level is daytime and the block above has sky access."
  [level pos]
  (when (and level pos)
    (let [time (rem (long (world/world-get-day-time level)) 24000)
          day? (<= time 12500)]
      (and day? (world/world-can-see-sky level
                  (pos/create-block-pos (pos/pos-x pos) (inc (pos/pos-y pos)) (pos/pos-z pos)))))))

;; ============================================================================
;; Part 3: Server-Side Tick Logic
;; ============================================================================

(defn- solar-tick-fn
  "Tick handler for solar generator ScriptedBlockEntity.

  All mutable state is stored in BE customState as a Clojure keyword map.
  No reflection — uses direct Java interop and getCustomState/setCustomState."
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state       (or (platform-be/get-custom-state be) {})
          generating? (can-generate? level pos)
          raining?    (world/world-is-raining level)
          status      (cond (not generating?) "STOPPED"
                            raining?          "WEAK"
                            :else             "STRONG")
          bright      (if generating? 1.0 0.0)
          bright*     (if (and (> bright 0) raining?) (* bright 0.2) bright)
          gen         (* bright* 3.0)
          current     (double (get state :energy 0.0))
          new-energy  (min max-energy (+ current gen))
          changed?    (and (> gen 0) (not= new-energy current))
          new-state   (cond-> (assoc state
                               :status status
                               :max-energy max-energy
                               :gen-speed (double gen))
                        changed? (assoc :energy new-energy))]
      (when (not= new-state state)
        (platform-be/set-custom-state! be new-state)
        (when changed?
          (platform-be/set-changed! be))))))

;; ============================================================================
;; Part 4: NBT Serialization
;; ============================================================================

(defn- solar-read-nbt-fn
  "Deserialize CompoundTag → state keyword map (stored in BE customState)."
  [tag]
  {:energy     (if (nbt/nbt-has-key? tag "Energy")
                 (nbt/nbt-get-double tag "Energy")
                 0.0)
   :max-energy max-energy
   :status     "STOPPED"
   :battery    (when (nbt/nbt-has-key? tag "Battery")
                 (item/create-item-from-nbt (nbt/nbt-get-compound tag "Battery")))})

(defn- solar-write-nbt-fn
  "Serialize BE customState → CompoundTag."
  [be tag]
  (let [state (or (platform-be/get-custom-state be) {})]
    (nbt/nbt-set-double! tag "Energy" (double (get state :energy 0.0)))
    (let [stack (:battery state)]
      (when (and stack (not (item/item-is-empty? stack)))
        (let [sub (nbt/create-nbt-compound)]
          (item/item-save-to-nbt stack sub)
          (nbt/nbt-set-tag! tag "Battery" sub))))))

;; ============================================================================
;; Part 5: Block Event Handlers
;; ============================================================================

(defn- open-solar-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :solar world pos)
        (do (log/error "SolarGen GUI open fn not found: cn.li.ac.wireless.gui.registry/open-gui-by-type") nil))
      (catch Exception e
        (log/error "Failed to open SolarGen GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Part 6: Network Handlers (moved from gui.clj)
;; ============================================================================

(defn- node->info
  "Converts an IWirelessNode to a serializable map, or nil."
  [^IWirelessNode node]
  (when node
    (let [p  (.getBlockPos node)
          pw (try (str (.getPassword node)) (catch Exception _ ""))]
      {:node-name     (try (str (.getNodeName node)) (catch Exception _ "Node"))
       :pos-x         (when p (pos/pos-x p))
       :pos-y         (when p (pos/pos-y p))
       :pos-z         (when p (pos/pos-z p))
       :is-encrypted? (not (empty? pw))})))

(defn- get-linked-node
  "Returns the IWirelessNode linked to a generator tile, or nil."
  ^IWirelessNode [tile]
  (when-let [conn (try (helper/get-node-conn-by-generator tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile  (net-helpers/get-tile-at world payload)]
    {:linked (some-> tile get-linked-node node->info) :avail []}))

(defn- handle-list-nodes [payload player]
  (let [world (net-helpers/get-world player)
        tile  (net-helpers/get-tile-at world payload)]
    (if tile
      (let [tile-pos    (pos/position-get-block-pos tile)
            linked-node (get-linked-node tile)
            linked-pos  (when linked-node (.getBlockPos linked-node))
            nodes       (if tile-pos (helper/get-nodes-in-range world tile-pos) [])
            avail       (->> nodes
                             (remove (fn [^IWirelessNode n]
                                       (let [p (.getBlockPos n)]
                                         (and p linked-pos
                                              (= (pos/pos-x p) (pos/pos-x linked-pos))
                                              (= (pos/pos-y p) (pos/pos-y linked-pos))
                                              (= (pos/pos-z p) (pos/pos-z linked-pos))))))
                             (mapv node->info))]
        {:linked (node->info linked-node) :avail avail})
      {:linked nil :avail []})))

(defn- handle-connect [payload player]
  (let [world      (net-helpers/get-world player)
        gen        (net-helpers/get-tile-at world payload)
        node-pos   (select-keys payload [:node-x :node-y :node-z])
        pass       (:password payload "")
        need-auth? (boolean (:need-auth? payload true))]
    (if (and world gen (every? number? (vals node-pos)))
      (if-let [node (net-helpers/get-tile-at world {:pos-x (:node-x node-pos)
                                                    :pos-y (:node-y node-pos)
                                                    :pos-z (:node-z node-pos)})]
        {:success (boolean (helper/link-generator-to-node! gen node pass need-auth?))}
        {:success false})
      {:success false})))

(defn- handle-disconnect [payload player]
  (let [world (net-helpers/get-world player)
        gen   (net-helpers/get-tile-at world payload)]
    (if (and world gen)
      (do (helper/unlink-generator-from-node! gen)
          {:success true})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :list-nodes) handle-list-nodes)
  (net-server/register-handler (msg :connect) handle-connect)
  (net-server/register-handler (msg :disconnect) handle-disconnect)
  (log/info "Solar Generator network handlers registered"))

;; ============================================================================
;; Part 7: Registration
;; ============================================================================

;; Register tile logic
(tdsl/deftile solar-gen-tile
  :id "solar-gen"
  :registry-name "solar_gen"
  :impl :scripted
  :blocks ["solar-gen"]
  :tick-fn solar-tick-fn
  :read-nbt-fn solar-read-nbt-fn
  :write-nbt-fn solar-write-nbt-fn)

;; Register capability
(platform-cap/declare-capability! :wireless-generator IWirelessGenerator
  (fn [be _side] (impls/->WirelessGeneratorImpl be)))

(tile-logic/register-tile-capability! "solar-gen" :wireless-generator)

;; Define block
(bdsl/defblock solar-gen
  :registry-name "solar_gen"
  :physical {:material :stone
             :hardness 1.5
             :resistance 6.0
             :requires-tool true
             :harvest-tool :pickaxe
             :harvest-level 1
             :sounds :stone}
  :rendering {:model-parent "minecraft:block/cube_all"
              :textures {:all (modid/asset-path "block" "solar_gen")}
              :flat-item-icon? true}
  :events {:on-right-click open-solar-gui!})

;; Auto-Registration
(hooks/register-network-handler! register-network-handlers!)

;; Helper functions
(defn init-solar-gen!
  []
  (log/info "Initialized Solar Generator block"))
