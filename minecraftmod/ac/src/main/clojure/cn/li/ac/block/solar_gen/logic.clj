(ns cn.li.ac.block.solar-gen.logic
	(:require [cn.li.mcmod.platform.be :as platform-be]
						[cn.li.mcmod.platform.nbt :as nbt]
						[cn.li.mcmod.platform.item :as item]
						[cn.li.mcmod.platform.position :as pos]
						[cn.li.mcmod.platform.world :as world]
						[cn.li.ac.block.solar-gen.config :as solar-config]
						[cn.li.ac.wireless.api :as wireless-api]
						[cn.li.ac.wireless.service.node-connection :as node-connection]
						[cn.li.mcmod.util.log :as log])
	(:import [cn.li.acapi.wireless IWirelessNode]))

(defn- can-generate? [level pos]
	(when (and level pos)
		(let [time (rem (long (world/world-get-day-time* level)) 24000)
					day? (<= time (solar-config/daytime-threshold-ticks))]
			(and day?
					 (world/world-can-see-sky* level
						 (pos/create-block-pos (pos/pos-x pos) (inc (pos/pos-y pos)) (pos/pos-z pos)))))))

(defn solar-tick-fn [level pos _block-state be]
	(when (and level (not (world/world-is-client-side* level)))
		(let [state       (or (platform-be/get-custom-state be) {})
					generating? (can-generate? level pos)
					raining?    (world/world-is-raining* level)
					status      (cond (not generating?) "STOPPED"
														raining?          "WEAK"
														:else             "STRONG")
					bright      (if generating? 1.0 0.0)
					bright*     (if (and (> bright 0) raining?) (* bright (solar-config/rain-multiplier)) bright)
					gen         (* bright* (solar-config/generation-rate))
					current     (double (get state :energy 0.0))
					max-energy  (solar-config/max-energy)
					new-energy  (min max-energy (+ current gen))
					changed?    (and (> gen 0) (not= new-energy current))
					new-state   (cond-> (assoc state :status status :max-energy max-energy :gen-speed (double gen))
												changed? (assoc :energy new-energy))]
			(when (not= new-state state)
				(platform-be/set-custom-state! be new-state)
				(when changed? (platform-be/set-changed! be))))))

(defn solar-read-nbt-fn [tag]
	{:energy     (if (nbt/nbt-has-key-safe? tag "Energy") (nbt/nbt-get-double tag "Energy") 0.0)
	 :max-energy (solar-config/max-energy)
	 :status     "STOPPED"
	 :battery    (when (nbt/nbt-has-key-safe? tag "Battery")
								 (item/create-item-from-nbt (nbt/nbt-get-compound tag "Battery")))})

(defn solar-write-nbt-fn [be tag]
	(let [state (or (platform-be/get-custom-state be) {})]
		(nbt/nbt-set-double! tag "Energy" (double (get state :energy 0.0)))
		(let [stack (:battery state)]
			(when (and stack (not (item/item-is-empty? stack)))
				(let [sub (nbt/create-nbt-compound)]
					(item/item-save-to-nbt stack sub)
					(nbt/nbt-set-tag! tag "Battery" sub))))))

(defn open-solar-gui! [{:keys [player world pos sneaking] :as _ctx}]
	(when (and player world pos (not sneaking))
		(try
			(if-let [open-gui-by-type (requiring-resolve 'cn.li.ac.gui.open/open-gui-by-type)]
				(open-gui-by-type player :solar world pos)
				(do (log/error "SolarGen GUI open fn not found: cn.li.ac.gui.open/open-gui-by-type") nil))
			(catch Exception e
				(log/error "Failed to open SolarGen GUI:" (ex-message e))
				nil))))

(defn node->info [^IWirelessNode node]
	(when node
		(let [p  (.getBlockPos node)
					pw (try (str (.getPassword node)) (catch Exception _ ""))]
			{:node-name     (try (str (.getNodeName node)) (catch Exception _ "Node"))
			 :pos-x         (when p (pos/pos-x p))
			 :pos-y         (when p (pos/pos-y p))
			 :pos-z         (when p (pos/pos-z p))
			 :is-encrypted? (not (empty? pw))})))

(defn get-linked-node ^IWirelessNode [tile]
	(when-let [conn (try (wireless-api/get-node-conn-by-generator tile) (catch Exception _ nil))]
		(try (node-connection/get-node conn) (catch Exception _ nil))))