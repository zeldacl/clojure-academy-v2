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
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.ability :as platform-ability]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.network.server :as net-server]
            [cn.li.ac.block.ability-interferer.config :as interferer-config]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.sync.handler :as net-helpers]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.registry.hooks :as hooks]
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.config.modid :as modid]
            [clojure.string :as str]
            [clojure.set :as set]))

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

(def interferer-blockstate-fields
  (filterv :block-state interferer-schema/ability-interferer-schema))

(def interferer-block-state-properties
  (state-schema/extract-block-state-properties interferer-blockstate-fields))

(def interferer-blockstate-updater
  (state-schema/build-block-state-updater interferer-blockstate-fields))

;; ============================================================================
;; Inventory
;; ============================================================================

(def battery-slot 0)
(def total-slots 1)

;; ============================================================================
;; Player Detection
;; ============================================================================

(defn- clamp-range [v]
  (let [r (double (or v interferer-config/default-range))]
    (-> r
        (max interferer-config/min-range)
        (min interferer-config/max-range))))

(defn- source-id
  [level pos]
  (let [dim-id (or (try
                     (when-let [rk (.dimension level)]
                       (str (.location rk)))
                     (catch Exception _ nil))
                   "unknown")]
    (str "interferer@" dim-id "/" (pos/pos-x pos) "," (pos/pos-y pos) "," (pos/pos-z pos))))

(defn- player-name [player]
  (let [n (try (entity/player-get-name player) (catch Exception _ nil))]
    (if (and n (not (str/blank? (str n)))) (str n) "")))

(defn- player-uuid-str [player]
  (some-> (try (entity/player-get-uuid player) (catch Exception _ nil)) str))

(defn- player-creative?
  [player]
  (or (try (boolean (.isCreative player)) (catch Exception _ false))
      (try (boolean (.. player getAbilities instabuild)) (catch Exception _ false))))

(defn- player-spectator?
  [player]
  (try (boolean (.isSpectator player)) (catch Exception _ false)))

(defn- raw-level-players
  [level]
  (or (try (seq (.players level)) (catch Exception _ nil))
      (try (seq (.getPlayers level)) (catch Exception _ nil))
      []))

(defn- find-players-in-range
  "Find all players within interference range"
  [level pos range]
  (let [cx (+ 0.5 (double (pos/pos-x pos)))
        cy (+ 0.5 (double (pos/pos-y pos)))
        cz (+ 0.5 (double (pos/pos-z pos)))
        max-d2 (* (double range) (double range))]
    (->> (raw-level-players level)
         (filter (fn [p]
                   (and p
                        (not (player-creative? p))
                        (not (player-spectator? p))
                        (<= (double (entity/entity-distance-to-sqr p cx cy cz)) max-d2))))
         vec)))

(defn- apply-interference-effect!
  [player src-id]
  (when-let [uuid (player-uuid-str player)]
    (try
      (let [store (platform-ability/player-ability-store)]
        (platform-ability/res-add-interference! store uuid src-id)
        true)
      (catch Exception e
        (log/warn "Failed to add interference for" uuid ":" (ex-message e))
        false))))

(defn- remove-interference-effect-by-uuid!
  [uuid src-id]
  (when (and uuid src-id)
    (try
      (let [store (platform-ability/player-ability-store)]
        (platform-ability/res-remove-interference! store uuid src-id)
        true)
      (catch Exception e
        (log/warn "Failed to remove interference for" uuid ":" (ex-message e))
        false))))

(defn- clear-interference-by-uuids!
  [uuids src-id]
  (doseq [uuid uuids]
    (remove-interference-effect-by-uuid! uuid src-id)))

(defn- update-be-state!
  [be level pos state]
  (platform-be/set-custom-state! be state)
  (platform-be/set-changed! be)
  (when (seq interferer-blockstate-fields)
    (interferer-blockstate-updater state level pos)))

;; ============================================================================
;; Tick Logic
;; ============================================================================

(defn- interferer-tick-fn
  "Tick handler for ability interferer"
  [level pos _block-state be]
  (when (and level (not (world/world-is-client-side* level)))
    (let [state (or (platform-be/get-custom-state be) interferer-default-state)
          ticker (inc (get state :update-ticker 0))
          state (assoc state :update-ticker ticker
                             :range (clamp-range (:range state)))
          src-id (source-id level pos)
          prev-uuids (set (:affected-player-uuids state []))

          ;; Charge from battery slot
          battery-item (get-in state [:inventory battery-slot])
          state (if (and battery-item (energy/is-energy-item-supported? battery-item))
                  (let [cur-energy (double (:energy state 0.0))
                        max-energy (double (:max-energy state interferer-config/max-energy))
                        needed (max 0.0 (- max-energy cur-energy))
                        wanted (min needed interferer-config/battery-pull-per-tick)
                        pulled (double (energy/pull-energy-from-item battery-item wanted false))]
                    (if (pos? pulled)
                      (assoc state :energy (+ cur-energy (min pulled wanted)))
                      state))
                  state)

          state (if (and (not (:enabled state false)) (seq prev-uuids))
                  (do
                    (clear-interference-by-uuids! prev-uuids src-id)
                    (assoc state :affected-player-count 0
                                 :affected-player-uuids []))
                  state)

          state (if (and (:enabled state false)
                         (zero? (mod ticker interferer-config/check-interval)))
                  (let [range (clamp-range (:range state interferer-config/default-range))
                        players (find-players-in-range level pos range)
                        whitelist (set (map str (:whitelist state [])))
                        affected-players (remove #(contains? whitelist (player-name %)) players)
                        affected-uuids (set (keep player-uuid-str affected-players))
                        player-count (count affected-uuids)
                        energy-cost (interferer-config/calculate-energy-cost range)
                        current-energy (double (:energy state 0.0))]
                    (if (>= current-energy energy-cost)
                      (do
                        ;; Keep per-player interference set aligned with current affected players.
                        (doseq [uuid (set/difference prev-uuids affected-uuids)]
                          (remove-interference-effect-by-uuid! uuid src-id))
                        (doseq [player affected-players]
                          (apply-interference-effect! player src-id))
                        (assoc state
                               :range range
                               :energy (- current-energy energy-cost)
                               :affected-player-count player-count
                               :affected-player-uuids (vec affected-uuids)))
                      (do
                        (clear-interference-by-uuids! prev-uuids src-id)
                        ;; Classic behavior: auto-disable when IF depletes.
                        (assoc state
                               :enabled false
                               :affected-player-count 0
                               :affected-player-uuids []))))
                  state)]

      (when (not= state (platform-be/get-custom-state be))
        (update-be-state! be level pos state)))))

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

(defn- on-interferer-placed!
  [{:keys [player world pos] :as _ctx}]
  (when (and player world pos)
    (let [tile (world/world-get-tile-entity* world pos)]
      (when tile
        (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
              existing-placer (str (get state :placer-name ""))
              placer-name (player-name player)
              should-init? (str/blank? existing-placer)
              whitelist (vec (or (:whitelist state) []))
              whitelist' (if (or (str/blank? placer-name)
                                 (some #(= % placer-name) whitelist))
                           whitelist
                           (conj whitelist placer-name))
              state' (if should-init?
                       (assoc state :placer-name placer-name
                                    :whitelist whitelist')
                       state)]
          (when (not= state state')
            (update-be-state! tile world pos state')))))))

(defn- on-interferer-break!
  [{:keys [world pos] :as _ctx}]
  (when (and world pos)
    (let [tile (world/world-get-tile-entity* world pos)]
      (when tile
        (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
              uuids (set (:affected-player-uuids state []))
              src-id (source-id world pos)]
          (when (seq uuids)
            (clear-interference-by-uuids! uuids src-id)))))))

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
         :placer-name (:placer-name state "")
         :whitelist (:whitelist state [])
         :affected-player-count (:affected-player-count state 0)})
      {:energy 0.0 :max-energy 0.0 :range interferer-config/default-range :enabled false
       :placer-name "" :whitelist [] :affected-player-count 0})))

(defn- handle-change-range [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        requested (:range payload)]
    (if (and tile (number? requested))
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
            state' (assoc state :range (clamp-range requested))]
        (platform-be/set-custom-state! tile state')
        (platform-be/set-changed! tile)
        {:success true :range (:range state')})
      {:success false})))

(defn- handle-toggle-enabled [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        new-enabled (boolean (:enabled payload))]
    (if tile
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
            src-id (source-id world (pos/position-get-block-pos tile))
            uuids (set (:affected-player-uuids state []))
            state' (if new-enabled
                     (assoc state :enabled true)
                     (do
                       (clear-interference-by-uuids! uuids src-id)
                       (assoc state
                              :enabled false
                              :affected-player-count 0
                              :affected-player-uuids [])))]
        (platform-be/set-custom-state! tile state')
        (platform-be/set-changed! tile)
        {:success true :enabled (:enabled state')})
      {:success false})))

(defn- handle-set-whitelist [payload player]
  (let [world (net-helpers/get-world player)
        tile (net-helpers/get-tile-at world payload)
        names (:whitelist payload)]
    (if (and tile (sequential? names))
      (let [state (or (platform-be/get-custom-state tile) interferer-default-state)
            cleaned (->> names
                         (map #(str/trim (str %)))
                         (remove str/blank?)
                         distinct
                         sort
                         vec)
            state' (assoc state :whitelist cleaned)]
        (platform-be/set-custom-state! tile state')
        (platform-be/set-changed! tile)
        {:success true :whitelist cleaned})
      {:success false})))

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
  (net-server/register-handler (msg :set-whitelist) handle-set-whitelist)
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
      [:get-status :change-range :toggle-enabled :set-whitelist :add-to-whitelist :remove-from-whitelist])
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
               :textures {:all (modid/asset-path "block" "ability_interf_off")}
                     :flat-item-icon? true
                     :light-level 3}
            :block-state {:block-state-properties interferer-block-state-properties}
            :events {:on-right-click open-interferer-gui!
               :on-place on-interferer-placed!
               :on-break on-interferer-break!}}))
    (hooks/register-network-handler! register-network-handlers!)
    (log/info "Initialized Ability Interferer block")))

