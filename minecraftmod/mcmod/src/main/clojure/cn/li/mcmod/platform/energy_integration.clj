(ns cn.li.mcmod.platform.energy-integration
  "Platform-neutral bridge for energy integration settings.

  State stored in Framework [:registry :integrations :energy]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn- default-state []
  {:forge-energy-conversion-rate (fn [] 1.0)
   :ic2-energy-conversion-rate (fn [] 1.0)})

(def ^:private energy-path [:registry :integrations :energy])

(defn- energy-hooks-snapshot []
  (if-let [fw-atom fw/*framework*]
    (get-in @fw-atom energy-path (default-state))
    (default-state)))

(defn register-energy-integration-hooks!
  [hooks]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in energy-path
           (fn [current]
             (let [base (or current (default-state))]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting energy integration hook" {:key k}))
                              (assoc m k v)))
                          base hooks)))))
  nil)

(defn reset-energy-integration-hooks-for-test!
  []
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in energy-path (default-state)))
  nil)

(defn forge-energy-conversion-rate []
  ((:forge-energy-conversion-rate (energy-hooks-snapshot))))

(defn ic2-energy-conversion-rate []
  ((:ic2-energy-conversion-rate (energy-hooks-snapshot))))
