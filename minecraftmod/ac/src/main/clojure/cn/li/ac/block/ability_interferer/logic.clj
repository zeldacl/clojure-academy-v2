(ns cn.li.ac.block.ability-interferer.logic
  "Ability Interferer business logic: state schema, player detection, tick, GUI, container."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [cn.li.ac.ability.model.resource :as rd]
            [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.block.ability-interferer.config :as interferer-config]
            [cn.li.ac.block.ability-interferer.schema :as interferer-schema]
            [cn.li.ac.block.energy-converter.wireless-impl :as wireless-impl]
            [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.item.item-energy-base :as energy-base]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.events.world-lifecycle :as world-lifecycle]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.util.log :as log]))

(def ^:private interferer-rt
  (machine-runtime/schema-runtime interferer-schema/ability-interferer-schema :server-only? true))

(def interferer-state-schema (:server-schema interferer-rt))
(def interferer-default-state (:default-state interferer-rt))
(def interferer-scripted-load-fn (:load-fn interferer-rt))
(def interferer-scripted-save-fn (:save-fn interferer-rt))

(def interferer-block-state-properties
  (:block-state-properties interferer-rt))

(def interferer-blockstate-updater
  (:blockstate-updater interferer-rt))

(def battery-slot 0)
(def total-slots 1)

(def clamp-range interferer-config/clamp-range)

(defn source-id [level pos]
	(let [dim-id (or (try (world/world-get-dimension-id* level) (catch Exception _ nil)) "unknown")]
		(str "interferer@" dim-id "/" (pos/pos-x pos) "," (pos/pos-y pos) "," (pos/pos-z pos))))

(defn- player-name [player]
	(let [n (try (entity/player-get-name player) (catch Exception _ nil))]
		(if (and n (not (str/blank? (str n)))) (str n) "")))

(defn- player-creative? [player]
	(try (boolean (entity/player-creative? player)) (catch Exception _ false)))

(defn- player-spectator? [player]
	(try (boolean (entity/player-spectator? player)) (catch Exception _ false)))

(defn- raw-level-players [level]
	(or (try (world/world-get-players* level) (catch Exception _ nil)) []))

(defn- find-players-in-range [level pos range]
  "Find players within a cubic AABB centered on the block (matching original AcademyCraft behavior)."
  (let [cx (+ 0.5 (double (pos/pos-x pos)))
        cy (+ 0.5 (double (pos/pos-y pos)))
        cz (+ 0.5 (double (pos/pos-z pos)))
        r (double range)
        min-x (- cx r) max-x (+ cx r)
        min-y (- cy r) max-y (+ cy r)
        min-z (- cz r) max-z (+ cz r)]
    (->> (raw-level-players level)
         (filter (fn [p]
                   (and p
                        (not (player-creative? p))
                        (not (player-spectator? p))
                            (let [x (entity/entity-get-x p)
                            y (entity/entity-get-y p)
                            z (entity/entity-get-z p)]
                           (and (<= min-x x max-x)
                             (<= min-y y max-y)
                             (<= min-z z max-z))))))
         vec)))

(defn- apply-interference-effect! [player src-id]
	(when-let [uuid (uuid/player-uuid player)]
		(try
			(let [session-id (runtime-hooks/require-player-state-server-session-id "ability-interferer")
						state (store/get-player-state* session-id uuid)
						resource-data (some-> state :resource-data (rd/add-interference src-id))]
				(when resource-data
					(command-rt/run-command-in-session!
					 session-id
					 uuid
					 {:command :hydrate-player-state
						:resource-data resource-data})))
			true
			(catch Exception e
				(log/warn "Failed to add interference for" uuid ":" (ex-message e))
				false))))

(defn- remove-interference-effect-by-uuid! [uuid src-id]
	(when (and uuid src-id)
		(try
			(let [session-id (runtime-hooks/require-player-state-server-session-id "ability-interferer")
						state (store/get-player-state* session-id uuid)
						resource-data (some-> state :resource-data (rd/remove-interference src-id))]
				(when resource-data
					(command-rt/run-command-in-session!
					 session-id
					 uuid
					 {:command :hydrate-player-state
						:resource-data resource-data})))
			true
			(catch Exception e
				(log/warn "Failed to remove interference for" uuid ":" (ex-message e))
				false))))

(defn clear-interference-by-uuids! [uuids src-id]
	(doseq [uuid uuids]
		(remove-interference-effect-by-uuid! uuid src-id)))

;; ============================================================================
;; Global registry of active interferers (for tile invalidation detection)
;; ============================================================================

(def ^:private active-interferers (atom {}))
;; {source-id {:level world :pos block-pos :affected-uuids #{...}}}

(def ^:private active-interferers-tick-counter (atom 0))

(defn- register-active-interferer! [src-id level pos uuids]
  (swap! active-interferers assoc src-id {:level level :pos pos :affected-uuids (set uuids)}))

(defn- unregister-active-interferer! [src-id]
  (swap! active-interferers dissoc src-id))

(defn- update-active-interferer-uuids! [src-id uuids]
  (swap! active-interferers (fn [m]
                              (if (contains? m src-id)
                                (assoc-in m [src-id :affected-uuids] (set uuids))
                                m))))

(defn- cleanup-stale-interferers!
  "World-tick handler: check all registered interferers for chunk unload.
   Matching original AcademyCraft's !tile.isInvalid per-tick check."
  [world]
  (swap! active-interferers-tick-counter inc)
  (when (zero? (mod @active-interferers-tick-counter 40))
    (let [stale (atom [])]
      (doseq [[src-id {:keys [level pos affected-uuids]}] @active-interferers]
        (when (and level pos)
          (try
            (when-not (world/world-is-chunk-loaded?* level (world/block-to-chunk-coord (pos/position-get-x pos)) (world/block-to-chunk-coord (pos/position-get-z pos)))
              (swap! stale conj src-id)
              (log/warn "[Interferer] Chunk unloaded for" src-id "- cleaning up" (count affected-uuids) "players")
              (doseq [u affected-uuids]
                (remove-interference-effect-by-uuid! u src-id)))
            (catch Exception e
              (log/warn "[Interferer] Error checking chunk for" src-id ":" (ex-message e))))))
      (when (seq @stale)
        (swap! active-interferers #(apply dissoc % @stale))))))

;; ============================================================================
;; Scan and tick
;; ============================================================================

(defn- scan-affected-uuids
  [level pos state]
  (let [range (clamp-range (:range state (interferer-config/default-range)))
        whitelist (set (map str (:whitelist state [])))
        players (find-players-in-range level pos range)]
    {:range range
     :uuids (vec (set (keep uuid/player-uuid
                            (remove #(contains? whitelist (player-name %)) players))))}))

(defn interferer-tick-state
  "Pure state step; ability I/O runs in interferer-after-commit!."
  [base-state {:keys [level pos]}]
  (let [ticker (inc (long (get base-state :update-ticker 0)))
        state0 (assoc base-state
                      :update-ticker ticker
                      :max-energy (double (interferer-config/max-energy))
                      :range (clamp-range (:range base-state)))
        battery-item (get-in state0 [:inventory battery-slot])
        state1 (if (and battery-item (energy/is-energy-item-supported? battery-item))
                 (let [cur-energy (double (:energy state0 0.0))
                       max-energy (double (:max-energy state0 (interferer-config/max-energy)))
                       needed (max 0.0 (- max-energy cur-energy))
                       wanted (min needed (interferer-config/battery-pull-per-tick))
                       pulled (double (energy/pull-energy-from-item battery-item wanted false))]
                   (if (pos? pulled)
                     (assoc state0 :energy (+ cur-energy (min pulled wanted)))
                     state0))
                 state0)
        state2 (if (not (:enabled state1 false))
                 (assoc state1 :affected-player-count 0 :affected-player-uuids [])
                 state1)]
    (if (and (:enabled state2 false)
             (zero? (mod ticker (interferer-config/check-interval))))
      (let [{:keys [range uuids]} (scan-affected-uuids level pos state2)
            energy-cost (interferer-config/calculate-energy-cost range)
            current-energy (double (:energy state2 0.0))]
        (if (>= current-energy energy-cost)
          (assoc state2
                 :range range
                 :energy (- current-energy energy-cost)
                 :affected-player-count (count uuids)
                 :affected-player-uuids uuids)
          (assoc state2
                 :enabled false
                 :affected-player-count 0
                 :affected-player-uuids [])))
      state2)))

(defn interferer-after-commit!
  "1.20-idiomatic: cheap in-memory :enabled comparison first; only touch world
   BlockState when the visual property actually changes. Ability interference
   effects (player registration) are handled after the BlockState check."
  [_be level pos old-state new-state _ctx]
  (when (and level pos (not= old-state new-state))
    ;; BlockState update (1.20 pattern: cheap comparison before world access)
    (when (not= (:enabled old-state) (:enabled new-state))
      (interferer-blockstate-updater new-state level pos))
    ;; Ability interference effects
    (let [src-id (source-id level pos)
          prev (set (:affected-player-uuids old-state []))
          next (set (:affected-player-uuids new-state []))
          removed (set/difference prev next)
          added (set/difference next prev)]
      ;; Remove interference from players who left range
      (doseq [u removed]
        (remove-interference-effect-by-uuid! u src-id))
      ;; Add interference to new players in range
      (when (seq added)
        (let [range (clamp-range (:range new-state (interferer-config/default-range)))
              whitelist (set (map str (:whitelist new-state [])))
              players (find-players-in-range level pos range)]
          (doseq [player players
                  :let [uid (uuid/player-uuid player)]
                  :when (and uid (contains? added uid)
                             (not (contains? whitelist (player-name player))))]
            (apply-interference-effect! player src-id))))
      ;; Update the global registry for tile invalidation tracking
      (if (seq next)
        (if (contains? @active-interferers src-id)
          (update-active-interferer-uuids! src-id next)
          (register-active-interferer! src-id level pos next))
        (unregister-active-interferer! src-id)))))

(def interferer-tick-fn
  "BlockState updates follow the vanilla 1.20 pattern:
   :after-commit! does a cheap in-memory :enabled comparison first;
   :blockstate-updater is intentionally omitted — only touches world
   BlockState when :enabled actually changes (rare: energy depletion or
   owner toggle). Handlers pass blockstate-updater explicitly for
   immediate visual feedback."
  (machine-runtime/make-tick-fn
    {:default-state interferer-default-state
     :tick-state interferer-tick-state
     :after-commit! interferer-after-commit!}))

;; ============================================================================
;; Container functions
;; ============================================================================

(defn- can-place-battery? [_be slot item _face]
  "Only accept energy_unit items in the battery slot (matching original AcademyCraft)."
  (and (= slot battery-slot)
       (= :energy-unit (energy-base/get-energy-item-type item))))

(defn- can-take-battery? [_be _slot _item _face]
  "Block automated extraction (matching original AcademyCraft)."
  false)

(def interferer-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state interferer-default-state
     :slot-count total-slots
     :can-place? can-place-battery?
     :can-take? can-take-battery?}))

(def open-interferer-gui!
  (machine-runtime/make-open-gui-handler :ability-interferer))

;; ============================================================================
;; Block lifecycle: place, break
;; ============================================================================

(defn on-interferer-placed! [{:keys [player world pos] :as _ctx}]
	(when (and player world pos)
		(let [tile (world/world-get-tile-entity* world pos)]
			(when tile
				(let [state (or (platform-be/get-custom-state tile) interferer-default-state)
							existing-placer (str (get state :placer-name ""))
							placer-name (try (entity/player-get-name player) (catch Exception _ ""))
							placer-name (if (and placer-name (not (str/blank? (str placer-name)))) (str placer-name) "")
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
          (machine-runtime/commit-state! tile world pos state state'
                                         :blockstate-updater interferer-blockstate-updater))))))

(defn on-interferer-break! [{:keys [world pos] :as _ctx}]
	(when (and world pos)
		(let [tile (world/world-get-tile-entity* world pos)]
			(when tile
				(let [state (or (platform-be/get-custom-state tile) interferer-default-state)
							uuids (set (:affected-player-uuids state []))
							src-id (source-id world pos)]
					(when (seq uuids)
						(clear-interference-by-uuids! uuids src-id))
          ;; Remove from global registry
          (unregister-active-interferer! src-id))))))

;; ============================================================================
;; IWirelessReceiver capability (matching original TileReceiverBase)
;; ============================================================================

(defn create-interferer-wireless-receiver
  "Create IWirelessReceiver implementation for the ability interferer.
   Wireless energy goes into the same :energy pool as battery pull,
   matching original AcademyCraft TileReceiverBase behavior."
  [be]
  (wireless-impl/create-wireless-receiver
    be
    (fn [] (machine-runtime/state-or-default be interferer-default-state))
    (fn [st] (machine-runtime/commit-from-tile! be interferer-default-state st))
    {:max-energy (fn [] (interferer-config/max-energy))
     :bandwidth (fn [] (interferer-config/battery-pull-per-tick))}))

;; ============================================================================
;; World-tick cleanup registration
;; ============================================================================

(def ^:private world-tick-cleanup-registered? (atom false))

(defn ensure-world-tick-cleanup!
  "Register the stale interferer cleanup handler if not already registered."
  []
  (when (compare-and-set! world-tick-cleanup-registered? false true)
    (world-lifecycle/register-world-lifecycle-handler!
      {:on-tick (fn [world]
                  (cleanup-stale-interferers! world))})
    (log/info "Ability Interferer world-tick cleanup handler registered")))
