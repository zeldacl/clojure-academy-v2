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
            [cn.li.ac.block.machine.container :as machine-container]
            [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.block.state-schema :as state-schema]
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

(def interferer-blockstate-fields
	(filterv :block-state interferer-schema/ability-interferer-schema))

(def interferer-block-state-properties
	(state-schema/extract-block-state-properties interferer-blockstate-fields))

(def interferer-blockstate-updater
	(state-schema/build-block-state-updater interferer-blockstate-fields))

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

(defn interferer-tick-state
  [base-state {:keys [level pos]}]
  (let [ticker (inc (long (get base-state :update-ticker 0)))
        state0 (assoc base-state
                      :update-ticker ticker
                      :max-energy (double (interferer-config/max-energy))
                      :range (clamp-range (:range base-state)))
        src-id (source-id level pos)
        prev-uuids (set (:affected-player-uuids state0 []))
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
        state2 (if (and (not (:enabled state1 false)) (seq prev-uuids))
                 (do
                   (clear-interference-by-uuids! prev-uuids src-id)
                   (assoc state1 :affected-player-count 0 :affected-player-uuids []))
                 state1)
        state3 (if (and (:enabled state2 false)
                        (zero? (mod ticker (interferer-config/check-interval))))
                 (let [range (clamp-range (:range state2 (interferer-config/default-range)))
                       players (find-players-in-range level pos range)
                       whitelist (set (map str (:whitelist state2 [])))
                       affected-players (remove #(contains? whitelist (player-name %)) players)
                       affected-uuids (set (keep uuid/player-uuid affected-players))
                       player-count (count affected-uuids)
                       energy-cost (interferer-config/calculate-energy-cost range)
                       current-energy (double (:energy state2 0.0))]
                   (if (>= current-energy energy-cost)
                     (do
                       (doseq [u (set/difference prev-uuids affected-uuids)]
                         (remove-interference-effect-by-uuid! u src-id))
                       (doseq [player affected-players]
                         (apply-interference-effect! player src-id))
                       (assoc state2
                              :range range
                              :energy (- current-energy energy-cost)
                              :affected-player-count player-count
                              :affected-player-uuids (vec affected-uuids)))
                     (do
                       (clear-interference-by-uuids! prev-uuids src-id)
                       (assoc state2
                              :enabled false
                              :affected-player-count 0
                              :affected-player-uuids []))))
                 state2)]
    state3))

(def interferer-tick-fn
  (machine-runtime/make-tick-fn
    {:default-state interferer-default-state
     :tick-state interferer-tick-state
     :blockstate-updater interferer-blockstate-updater}))

(defn- can-place-battery? [_be slot item _face]
  (and (= slot battery-slot) (energy/is-energy-item-supported? item)))

(defn- can-take-battery? [_be slot _item _face]
  (= slot battery-slot))

(def interferer-container-fns
  (machine-container/make-inventory-container-fns
    {:default-state interferer-default-state
     :slot-count total-slots
     :can-place? can-place-battery?
     :can-take? can-take-battery?}))

(def open-interferer-gui!
  (machine-runtime/make-open-gui-handler :ability-interferer))

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
						(clear-interference-by-uuids! uuids src-id)))))))

