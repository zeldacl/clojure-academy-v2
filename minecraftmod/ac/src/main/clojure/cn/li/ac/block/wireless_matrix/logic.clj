(ns cn.li.ac.block.wireless-matrix.logic
	"Wireless Matrix block business logic."
	(:require [cn.li.mcmod.block.state-schema :as schema]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.entity :as entity]
						[cn.li.mcmod.platform.item :as pitem]
						[cn.li.mcmod.gui.slot-schema :as slot-schema]
						[cn.li.mcmod.platform.world :as world]
						[cn.li.ac.item.constraint-plate :as plate]
						[cn.li.ac.item.mat-core :as core]
						[cn.li.ac.wireless.config :as matrix-config]
						[cn.li.ac.block.wireless-matrix.stats :as matrix-stats]
						[cn.li.ac.block.wireless-matrix.schema :as matrix-schema]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessMatrix]))

(def matrix-default-state
	(schema/schema->default-state matrix-schema/unified-matrix-schema))

(def matrix-scripted-load-fn
	(schema/schema->load-fn matrix-schema/unified-matrix-schema))

(def matrix-scripted-save-fn
	(schema/schema->save-fn matrix-schema/unified-matrix-schema))

(defn safe-state [be]
	(or (platform-be/get-custom-state be) matrix-default-state))

(defn resolve-controller-be [be]
	(if-not be
		nil
		(let [state (safe-state be)]
			(if (zero? (long (:sub-id state 0)))
				be
				(let [world-obj (platform-be/be-get-world-safe be)
							cx (:controller-pos-x state)
							cy (:controller-pos-y state)
							cz (:controller-pos-z state)]
					(if (and world-obj (number? cx) (number? cy) (number? cz))
						(or (world/world-get-tile-entity* world-obj (pos/create-block-pos (long cx) (long cy) (long cz)))
								be)
						be))))))

(def ^:private matrix-slot-schema-id :wireless-matrix)

(defn ensure-matrix-slot-schema! []
	(slot-schema/register-slot-schema!
		{:schema-id matrix-slot-schema-id
		 :slots [{:id :plate-a :type :plate :x 78 :y 11}
						 {:id :plate-b :type :plate :x 53 :y 60}
						 {:id :plate-c :type :plate :x 104 :y 60}
						 {:id :core :type :core :x 78 :y 36}]})
	matrix-slot-schema-id)

(def ^:private matrix-plate-slot-indexes
	(delay
		(ensure-matrix-slot-schema!)
		(slot-schema/slot-indexes-by-type matrix-slot-schema-id :plate)))

(def ^:private matrix-core-slot-index
	(delay
		(ensure-matrix-slot-schema!)
		(slot-schema/slot-index matrix-slot-schema-id :core)))

(def ^:private matrix-slot-indexes
	(delay
		(ensure-matrix-slot-schema!)
		(slot-schema/all-slot-indexes matrix-slot-schema-id)))

(def ^:private matrix-slot-count
	(delay
		(ensure-matrix-slot-schema!)
		(slot-schema/tile-slot-count matrix-slot-schema-id)))

(defn- slot-has-stack? [stk]
	(and stk (try (pos? (long (pitem/item-get-count stk))) (catch Exception _ true))))

(defn recalculate-counts [state]
	(let [plate-count (count (for [slot @matrix-plate-slot-indexes
																 :let [stk (get-in state [:inventory slot])]
																 :when (slot-has-stack? stk)]
														 slot))
				core-stack (get-in state [:inventory @matrix-core-slot-index])
				core-level (if (slot-has-stack? core-stack)
										 (inc (int (max 0 (pitem/item-get-damage core-stack))))
										 0)]
		(assoc state :plate-count plate-count :core-level core-level)))

(defn is-working? [state]
	(and (> (:core-level state 0) 0)
			 (= (:plate-count state 0) (count @matrix-plate-slot-indexes))))

(defn get-plate-count [be]
	(if-let [ctrl (resolve-controller-be be)]
		(get (safe-state ctrl) :plate-count 0)
		0))

(defn get-core-level [be]
	(if-let [ctrl (resolve-controller-be be)]
		(get (safe-state ctrl) :core-level 0)
		0))

(defn required-plate-count []
	(count @matrix-plate-slot-indexes))

(defn matrix-stats-for-counts
	"Pure Matrix capacity/bandwidth/range formula shared by capability and GUI preview."
	[core-level plate-count]
	(matrix-stats/stats-for-counts (required-plate-count) core-level plate-count))

(defn- matrix-params [be]
	(let [state (safe-state be)
				core-lv (int (schema/get-field matrix-schema/unified-matrix-schema state :core-level))]
		(matrix-stats-for-counts core-lv (:plate-count state 0))))

(defn- be-str-field [be k]
	(str (schema/get-field matrix-schema/unified-matrix-schema (safe-state be) k)))

(deftype WirelessMatrixImpl [be]
	IWirelessMatrix
	(getMatrixCapacity [_] (:capacity (matrix-params be)))
	(getMatrixBandwidth [_] (:bandwidth (matrix-params be)))
	(getMatrixRange [_] (:range (matrix-params be)))
	(getSsid [_] (be-str-field be :ssid))
	(getPassword [_] (be-str-field be :password))
	(getPlacerName [_] (be-str-field be :placer-name)))

(definterface IMatrixJavaProxy
	(^String getPlacerName [])
	(^long getMatrixCapacity [])
	(^long getMatrixBandwidth [])
	(^double getMatrixRange [])
	(^long getLoad [])
	(^Object getPos []))

(deftype MatrixJavaProxy [be]
	IMatrixJavaProxy
	(getPlacerName [_] (be-str-field be :placer-name))
	(getMatrixCapacity [_] (long (:capacity (matrix-params be))))
	(getMatrixBandwidth [_] (long (:bandwidth (matrix-params be))))
	(getMatrixRange [_] (double (:range (matrix-params be))))
	(getLoad [_] 0)
	(getPos [_] (pos/position-get-block-pos be)))

(defn matrix-scripted-tick-fn [level pos _state be]
	(let [state0 (safe-state be)
				ticker (inc (get state0 :update-ticker 0))
				state1 (assoc state0 :update-ticker ticker)
				state1 (if (and (zero? (:sub-id state1 0))
												(zero? (mod ticker (matrix-config/gui-sync-interval))))
							(try
								(let [impl ^IWirelessMatrix (->WirelessMatrixImpl be)
											payload (-> (schema/schema->sync-payload matrix-schema/unified-matrix-schema state1 pos)
																	(assoc :is-working (is-working? state1)
																				 :capacity (.getMatrixCapacity impl)
																				 :bandwidth (.getMatrixBandwidth impl)
																				 :range (.getMatrixRange impl)))
											old-payload (::last-broadcast-state state1)]
									(when (not= payload old-payload)
										(when-let [broadcast-fn (requiring-resolve 'cn.li.ac.block.wireless-matrix.gui/broadcast-matrix-state)]
											(broadcast-fn level pos payload)))
									(assoc state1 ::last-broadcast-state payload))
								(catch Exception e
									(log/debug "Matrix sync skipped:" (ex-message e))
									state1))
							state1)]
		(platform-be/set-custom-state! be state1)))

(def matrix-container-fns
	{:get-size (fn [_be] @matrix-slot-count)
	 :get-item (fn [be slot] (get-in (safe-state be) [:inventory slot]))
	 :set-item! (fn [be slot item]
								(let [state (safe-state be)]
									(platform-be/set-custom-state!
										be
										(recalculate-counts (assoc-in state [:inventory slot] item)))))
	 :remove-item (fn [be slot amount]
									(let [state (safe-state be)
												item (get-in state [:inventory slot])]
										(when item
											(let [cnt (pitem/item-get-count item)]
												(if (<= cnt amount)
													(do (platform-be/set-custom-state! be (recalculate-counts (assoc-in state [:inventory slot] nil)))
															item)
													(pitem/item-split item amount))))))
	 :remove-item-no-update (fn [be slot]
														(let [state (safe-state be)
																	item (get-in state [:inventory slot])]
															(platform-be/set-custom-state! be (recalculate-counts (assoc-in state [:inventory slot] nil)))
															item))
	 :clear! (fn [be]
						 (platform-be/set-custom-state!
							 be
							 (assoc (safe-state be)
											:inventory (vec (repeat @matrix-slot-count nil))
											:plate-count 0
											:core-level 0)))
	 :still-valid? (fn [_be _player] true)
	 :slots-for-face (fn [_be _face] (int-array @matrix-slot-indexes))
	 :can-place-through-face? (fn [_be slot item _face]
															(cond
																(contains? (set @matrix-plate-slot-indexes) slot) (plate/is-constraint-plate? item)
																(= slot @matrix-core-slot-index) (core/is-mat-core? item)
																:else false))
	 :can-take-through-face? (fn [_be _slot _item _face] true)})

(defn handle-matrix-right-click []
	(fn [{:keys [player world pos sneaking]}]
		(when-not sneaking
			(try
				(if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.gui.open/open-gui-by-type)]
					(open-gui-by-type player :matrix world pos)
					nil)
				(catch Exception e
					(log/error "Failed to open Matrix GUI:" (ex-message e))
					nil)))))

(defn handle-matrix-place []
	(fn [{:keys [player world pos]}]
		(when-let [be (world/world-get-tile-entity* world pos)]
			(let [player-name (try (entity/player-get-name player)
													(catch Exception _ (str player)))]
				(platform-be/set-custom-state! be (assoc (safe-state be) :placer-name (str player-name)))))))

(defn handle-matrix-break []
	(fn [{:keys [world pos]}]
		(when-let [be (world/world-get-tile-entity* world pos)]
			(doseq [[idx item] (map-indexed vector (:inventory (safe-state be) []))]
				(when item
					(log/info "Dropping item from slot" idx ":" item))))))