(ns cn.li.ac.block.ability-interferer.block
  "Ability Interferer block - interferes with player abilities in a radius.

  Features:
  - Configurable range (10-100 blocks)
  - Player whitelist system
  - Energy consumption based on range and player count
  - Battery slot for energy items

  Architecture:
  All persistent state lives in ScriptedBlockEntity.customState as Clojure maps."
  (:require [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.ability-interferer.config :as interferer-config]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]))

(defn- msg [action] (msg-registry/msg :ability-interferer action))

;; ============================================================================
;; Schema Generation
;; ============================================================================

(def interferer-state-schema
  (state-schema/filter-server-fields interferer-schema/ability-interferer-schema))

(def interferer-default-state
  (state-schema/schema->default-state interferer-state-schema))

(def interferer-scripted-load-fn
  (state-schema/schema->load-fn interferer-state-schema))

(def interferer-scripted-save-fn
  (state-schema/schema->save-fn interferer-state-schema))

(def network-handlers
  (state-schema/build-network-handlers
    [{:key :range :network-msg :change-range}
     {:key :enabled :network-msg :toggle-enabled}]))

;; ============================================================================
;; Inventory
;; ============================================================================

(def battery-slot 0)
(def total-slots 1)

;; ============================================================================
;; Player Detection
;; ============================================================================

(defn- find-players-in-range
  "Find all players within interference range"
  [level pos range]
  ;; TODO: Implement player detection
  ;; This needs to use Minecraft's player list and distance calculation
  ;; For now, return empty list
  [])

(defn- is-whitelisted?
  "Check if a player is in the whitelist"
  [player-name whitelist]
  (some #(= % player-name) whitelist))

(defn- apply-interference-effect
  "Apply interference effect to a player"
  [player]
  ;; TODO: Implement interference effect
  ;; This needs to interact with the ability system
  ;; For now, do nothing
  nil)

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- interferer-tick-fn
  "Tick handler for ability interferer"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side level)))
    (let [state (or (platform-be/get-custom-state be) interferer-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker)

          ;; Charge from battery slot
          battery-item (get-in state [:inventory battery-slot])
          state (if (and battery-item (energy/is-energy-item-supported? battery-item))
                  (let [cur-energy (double (:energy state 0.0))
                        max-energy (double (:max-energy state interferer-config/max-energy))
                        needed (- max-energy cur-energy)
                        pulled (double (energy/pull-energy-from-item battery-item needed false))]
                    (if (pos? pulled)
                      (assoc state :energy (+ cur-energy pulled))
                      state))
                  state)

          ;; Check for players and apply interference
          state (if (and (:enabled state false)
                         (zero? (mod ticker interferer-config/check-interval)))
                  (let [range (:range state interferer-config/default-range)
                        players (find-players-in-range level pos range)
                        whitelist (:whitelist state [])
                        affected-players (remove #(is-whitelisted? (str %) whitelist) players)
                        player-count (count affected-players)
                        energy-cost (interferer-config/calculate-energy-cost range player-count)
                        current-energy (:energy state 0.0)]
                    (if (>= current-energy energy-cost)
                      (do
                        ;; Apply interference to affected players
                        (doseq [player affected-players]
                          (apply-interference-effect player))
                        (assoc state
                               :energy (- current-energy energy-cost)
                               :affected-player-count player-count))
                      ;; Not enough energy, disable
                      (assoc state
                             :enabled false
                             :affected-player-count 0)))
                  state)]

      (when (not= state (platform-be/get-custom-state be))
        (platform-be/set-custom-state! be state)
        (platform-be/set-changed! be)))))

;; ============================================================================
;; Container Functions
;; ============================================================================

(def interferer-container-fns
  {:get-size (fn [_be] total-slots)
   :get-item (fn [be slot]
               (get-in (or (platform-be/get-custom-state be) interferer-default-state)
                       [:inventory slot]))
   :set-item! (fn [be slot item]
                (let [state (or (platform-be/get-custom-state be) interferer-default-state)
                      state' (assoc-in state [:inventory slot] item)]
                  (platform-be/set-custom-state! be state')))
   :remove-item (fn [be slot amount]
                  (let [state (or (platform-be/get-custom-state be) interferer-default-state)
                        item (get-in state [:inventory slot])]
                    (when item
                      (let [cnt (pitem/item-get-count item)]
                        (if (<= cnt amount)
                          (do (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item)
                          (pitem/item-split item amount))))))
   :remove-item-no-update (fn [be slot]
                            (let [state (or (platform-be/get-custom-state be) interferer-default-state)
                                  item (get-in state [:inventory slot])]
                              (platform-be/set-custom-state! be (assoc-in state [:inventory slot] nil))
                              item))
   :clear! (fn [be]
             (platform-be/set-custom-state! be
               (assoc (or (platform-be/get-custom-state be) interferer-default-state)
                      :inventory (vec (repeat total-slots nil)))))
   :still-valid? (fn [_be _player] true)
   :can-place-through-face? (fn [_be slot item _face]
                               (and (= slot battery-slot)
                                    (energy/is-energy-item-supported? item)))
   :can-take-through-face? (fn [_be slot _item _face]
                             (= slot battery-slot))})

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defn- open-interferer-gui!
  [{:keys [player world pos sneaking] :as _ctx}]
  (when (and player world pos (not sneaking))
    (try
      (if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.wireless.gui.registry/open-gui-by-type)]
        (open-gui-by-type player :ability-interferer world pos)
        (do (log/error "Ability Interferer GUI open fn not found") nil))
      (catch Exception e
        (log/error "Failed to open Ability Interferer GUI:" (ex-message e))
        nil))))

;; ============================================================================
;; Network Handlers
;; ============================================================================

(defn- handle-get-status [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)]
        {:energy (:energy state 0.0)
         :max-energy (:max-energy state interferer-config/max-energy)
         :range (:range state interferer-config/default-range)
         :enabled (:enabled state false)
         :whitelist (:whitelist state [])
         :affected-player-count (:affected-player-count state 0)})
      {:energy 0.0 :max-energy 0.0 :range 20.0 :enabled false
       :whitelist [] :affected-player-count 0})))

(def handle-change-range (get network-handlers :change-range))
(def handle-toggle-enabled (get network-handlers :toggle-enabled))

(defn- handle-add-to-whitelist [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        player-name (:player-name payload)]
    (if (and tile player-name)
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
            whitelist (:whitelist state [])
            new-whitelist (if (some #(= % player-name) whitelist)
                            whitelist
                            (conj whitelist player-name))]
        (platform-be/set-custom-state! tile (assoc state :whitelist new-whitelist))
        {:success true :whitelist new-whitelist})
      {:success false})))

(defn- handle-remove-from-whitelist [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        player-name (:player-name payload)]
    (if (and tile player-name)
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
            whitelist (:whitelist state [])
            new-whitelist (vec (remove #(= % player-name) whitelist))]
        (platform-be/set-custom-state! tile (assoc state :whitelist new-whitelist))
        {:success true :whitelist new-whitelist})
      {:success false})))

(defn register-network-handlers! []
  (net-server/register-handler (msg :get-status) handle-get-status)
  (net-server/register-handler (msg :change-range) handle-change-range)
  (net-server/register-handler (msg :toggle-enabled) handle-toggle-enabled)
  (net-server/register-handler (msg :add-to-whitelist) handle-add-to-whitelist)
  (net-server/register-handler (msg :remove-from-whitelist) handle-remove-from-whitelist)
  (log/info "Ability Interferer network handlers registered"))

;; ============================================================================
;; Runtime Installation (Scheme A)
;; ============================================================================

(defonce ^:private ability-interferer-installed? (atom false))

(defn init-ability-interferer!
  []
  (when (compare-and-set! ability-interferer-installed? false true)
    (msg-registry/register-block-messages! :ability-interferer
      [:get-status :change-range :toggle-enabled :add-to-whitelist :remove-from-whitelist])
    (tdsl/register-tile!
      (tdsl/create-tile-spec
        "ability-interferer"
        {:registry-name "ability_interferer"
         :impl :scripted
         :blocks ["ability-interferer"]
         :tick-fn interferer-tick-fn
         :read-nbt-fn interferer-scripted-load-fn
         :write-nbt-fn interferer-scripted-save-fn}))
    (tile-logic/register-container! "ability-interferer" interferer-container-fns)
    (bdsl/register-block!
      (bdsl/create-block-spec
        "ability-interferer"
        {:registry-name "ability_interferer"
         :physical {:material :metal
                    :hardness 3.0
                    :resistance 8.0
                    :requires-tool true
                    :harvest-tool :pickaxe
                    :harvest-level 2
                    :sounds :metal}
         :rendering {:model-parent "minecraft:block/cube_all"
                     :textures {:all (modid/asset-path "block" "ability_interferer")}
                     :flat-item-icon? true
                     :light-level 3}
         :events {:on-right-click open-interferer-gui!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Ability Interferer block")))
