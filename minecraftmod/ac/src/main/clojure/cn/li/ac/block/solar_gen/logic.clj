(ns cn.li.ac.block.solar-gen.logic
  (:require [cn.li.ac.block.machine.runtime :as machine-runtime]
            [cn.li.ac.block.solar-gen.config :as solar-config]
            [cn.li.ac.block.solar-gen.schema :as solar-schema]
            [cn.li.ac.wireless.api :as wireless-api]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world])
  (:import [cn.li.acapi.wireless IWirelessNode]))

(def ^:private solar-rt
  (machine-runtime/schema-runtime solar-schema/unified-solar-schema))

(def solar-default-state (:default-state solar-rt))
(def solar-scripted-load-fn (:load-fn solar-rt))
(def solar-scripted-save-fn (:save-fn solar-rt))

(defn- can-generate? [level pos]
  (when (and level pos)
    (let [time (rem (long (world/world-get-day-time* level)) 24000)
          day? (<= time (solar-config/daytime-threshold-ticks))]
      (and day?
           (world/world-can-see-sky* level
             (pos/create-block-pos (pos/pos-x pos) (inc (pos/pos-y pos)) (pos/pos-z pos)))))))

(defn solar-tick-state
  [state {:keys [level pos]}]
  (let [generating? (can-generate? level pos)
        raining? (world/world-is-raining* level)
        status (cond (not generating?) "STOPPED"
                     raining? "WEAK"
                     :else "STRONG")
        bright (if generating? 1.0 0.0)
        bright* (if (and (> bright 0) raining?) (* bright (solar-config/rain-multiplier)) bright)
        gen (* bright* (solar-config/generation-rate))
        current (double (get state :energy 0.0))
        max-energy (solar-config/max-energy)
        new-energy (min max-energy (+ current gen))
        changed? (and (> gen 0) (not= new-energy current))]
    (cond-> (assoc state :status status :max-energy max-energy :gen-speed (double gen))
      changed? (assoc :energy new-energy))))

(def solar-tick-fn
  (machine-runtime/make-tick-fn
    {:default-state solar-default-state
     :tick-state solar-tick-state
     :mark-changed? (fn [old-state new-state]
                      (and (pos? (get new-state :gen-speed 0.0))
                           (not= (:energy old-state) (:energy new-state))))}))

(def open-solar-gui!
  (machine-runtime/make-open-gui-handler :solar))

(defn get-linked-node ^IWirelessNode [tile]
  (when-let [conn (try (wireless-api/get-node-conn-by-generator tile) (catch Exception _ nil))]
    (try (node-conn/get-node conn) (catch Exception _ nil))))
