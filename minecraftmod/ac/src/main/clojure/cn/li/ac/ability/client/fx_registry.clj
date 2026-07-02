(ns cn.li.ac.ability.client.fx-registry
  "Client-side FX channel registry.

  Skills register FX channel handlers at load time.  The platform bridge
  delegates all incoming context-channel pushes to `dispatch-fx-channel!`
  instead of hard-coding a case statement per skill.

  State stored in Framework [:service :fx-registry]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(defn default-fx-registry-runtime-state []
  {:handlers {} :frozen? false})

(def ^:private fxr-path [:service :fx-registry])

(defn- fx-registry-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom fxr-path)
        (let [a (atom (default-fx-registry-runtime-state))]
          (swap! fw-atom assoc-in fxr-path a) a))
    (atom (default-fx-registry-runtime-state))))

(defn create-fx-registry-runtime
  ([]
   (create-fx-registry-runtime {}))
  ([{:keys [state*] :or {state* (fx-registry-state-atom)}}]
   {::runtime ::fx-registry-runtime :state* state*}))

(defn- fx-registry-state-snapshot [] @(fx-registry-state-atom))
(defn- update-fx-registry-state! [f & args] (apply swap! (fx-registry-state-atom) f args))
(defn- assert-registry-open! [] (when (:frozen? (fx-registry-state-snapshot)) (throw (ex-info "FX channel registry is frozen" {}))))

(defn register-fx-channel! [channel-key handler-fn]
  (when-not (and (keyword? channel-key) (fn? handler-fn))
    (throw (IllegalArgumentException. "register-fx-channel!: channel-key must be keyword, handler-fn must be fn")))
  (assert-registry-open!)
  (update-fx-registry-state! update :handlers (fn [handlers] (if (contains? handlers channel-key) handlers (assoc handlers channel-key handler-fn)))) nil)

(defn register-fx-channels! [channel-keys handler-fn]
  (when-not (and (every? keyword? channel-keys) (fn? handler-fn))
    (throw (IllegalArgumentException. "register-fx-channels!: channel-keys must be keywords, handler-fn must be fn")))
  (doseq [channel-key channel-keys] (register-fx-channel! channel-key handler-fn)) nil)

(defn freeze-fx-registry! [] (update-fx-registry-state! assoc :frozen? true) nil)
(defn fx-registry-snapshot [] (fx-registry-state-snapshot))
(defn reset-fx-registry-for-test! [] (reset! (fx-registry-state-atom) (default-fx-registry-runtime-state)) nil)

(defn dispatch-fx-channel! [ctx-id channel payload]
  (if-let [handler-fn (get (:handlers (fx-registry-state-snapshot)) channel)]
    (do (handler-fn ctx-id channel payload) true)
    (do (log/debug "No FX handler for channel" channel) false)))

(defn registered-channels [] (set (keys (:handlers (fx-registry-state-snapshot)))))
