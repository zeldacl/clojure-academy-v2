(ns cn.li.ac.block.cat-engine.logic
	(:require [cn.li.mcmod.block.state-schema :as state-schema]
						[cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.world :as world]
						[cn.li.ac.block.cat-engine.config :as cat-config]
						[cn.li.ac.block.cat-engine.schema :as cat-schema]
						[cn.li.ac.wireless.api :as wireless-api]
						[cn.li.ac.wireless.service.node-connection :as node-connection]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessNode]))

(def cat-state-schema (state-schema/filter-server-fields cat-schema/cat-engine-schema))
(def cat-default-state (state-schema/schema->default-state cat-state-schema))
(def cat-scripted-load-fn (state-schema/schema->load-fn cat-state-schema))
(def cat-scripted-save-fn (state-schema/schema->save-fn cat-state-schema))

(defn- find-nearby-nodes [level block-pos]
	(try (vec (wireless-api/get-nodes-in-range level block-pos))
			 (catch Exception e
				 (log/error "Cat Engine node search failed:" (ex-message e))
				 [])))

(defn get-linked-node ^IWirelessNode [be]
	(when-let [conn (try (wireless-api/get-node-conn-by-generator be) (catch Exception _ nil))]
		(try (node-connection/get-node conn) (catch Exception _ nil))))

(defn sync-link-state [be state]
	(if-let [node (get-linked-node be)]
		(let [p (.getBlockPos node)
					node-name (try (str (.getNodeName node)) (catch Exception _ ""))]
			(assoc state
						 :has-link true
						 :linked-node-name node-name
						 :linked-node-x (if p (pos/pos-x p) 0)
						 :linked-node-y (if p (pos/pos-y p) 0)
						 :linked-node-z (if p (pos/pos-z p) 0)))
		(assoc state :has-link false :linked-node-name "" :linked-node-x 0 :linked-node-y 0 :linked-node-z 0)))

(defn refresh-link-state! [be]
	(let [state0 (or (platform-be/get-custom-state be) cat-default-state)
				state1 (sync-link-state be state0)]
		(when (not= state1 state0)
			(platform-be/set-custom-state! be state1)
			(platform-be/set-changed! be))))

(defn- right-click-result [message-key & args]
	{:consume? true
	 :messages [{:type :translatable :key message-key :args (vec args)}]})

(defn cat-tick-fn [level _pos _block-state be]
	(when (and level (not (world/world-is-client-side* level)))
		(let [state0 (or (platform-be/get-custom-state be) cat-default-state)
					ticker (inc (long (get state0 :update-ticker 0)))
					energy (double (get state0 :energy 0.0))
					max-energy (double (cat-config/max-energy))
					generated (min (double (cat-config/generation-per-tick))
												 (max 0.0 (- max-energy energy)))
					energy* (+ energy generated)
					state1 (-> state0
										 (assoc :update-ticker ticker :energy energy* :max-energy max-energy)
										 (assoc :this-tick-gen 0.0 :gen-speed 0.0))
					state2 (sync-link-state be state1)]
			(when (not= state2 state0)
				(platform-be/set-custom-state! be state2)
				(platform-be/set-changed! be)))))

(defn cat-right-click! [{:keys [world pos] :as _ctx}]
	(let [be (and world pos (world/world-get-tile-entity* world pos))]
		(cond
			(nil? be) {:consume? true}
			(world/world-is-client-side* world) {:consume? true}
			(wireless-api/is-generator-linked? be)
			(do (wireless-api/unlink-generator-from-node! be)
					(refresh-link-state! be)
					(right-click-result "ac.cat_engine.unlink"))
			:else
			(let [nodes (find-nearby-nodes world pos)]
				(if (empty? nodes)
					(right-click-result "ac.cat_engine.notfound")
					(let [target-node (nth nodes (rand-int (count nodes)))
								linked? (try (boolean (wireless-api/link-generator-to-node! be target-node "" false))
														 (catch Exception _ false))]
						(if linked?
							(do (refresh-link-state! be)
									(right-click-result "ac.cat_engine.linked"
																			(try (str (.getNodeName ^IWirelessNode target-node))
																					 (catch Exception _ "Node"))))
							(right-click-result "ac.cat_engine.notfound"))))))))