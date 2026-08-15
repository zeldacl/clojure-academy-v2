(ns cn.li.ac.client.combat-vfx-adapter
  "AC's only bridge from neutral CombatResult VFX signals to vfx-core."
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]))

(defonce ^:private dispatch* (atom nil))
(defonce ^:private missing-dispatch* (atom 0))

(defn install-dispatch! [f]
  (when-not (ifn? f)
    (throw (ex-info "VFX signal dispatcher must be callable" {:value f})))
  (reset! dispatch* f)
  f)

(defn dispatch-signal! [signal]
  (let [signal (contract/signal signal)]
    (if-let [dispatch @dispatch*]
      (dispatch signal)
      (do
        ;; A client bootstrap bug must be observable; silently dropping a
        ;; server-confirmed VFX signal makes combat state and presentation
        ;; diverge and is especially hard to diagnose on dedicated clients.
        (swap! missing-dispatch* inc)
        {:status :unhandled :reason :vfx-dispatch-not-installed
         :signal signal}))))

(defn dispatch-result! [result]
  (doseq [signal (:vfx-signals result)]
    (dispatch-signal! signal))
  result)

(defn reset-for-test! []
  (reset! dispatch* nil)
  (reset! missing-dispatch* 0)
  nil)

(defn missing-dispatch-count [] @missing-dispatch*)
