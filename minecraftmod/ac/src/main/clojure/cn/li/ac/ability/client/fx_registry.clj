(ns cn.li.ac.ability.client.fx-registry
  "Client-side FX channel registry.

  Skills register FX channel handlers at load time.  The platform bridge
  delegates all incoming context-channel pushes to `dispatch-fx-channel!`
  instead of hard-coding a case statement per skill."
  (:require [cn.li.mcmod.util.log :as log]))

(defn default-fx-registry-runtime-state
  []
  {:handlers {}
   :frozen? false})

(defn create-fx-registry-runtime
  ([]
   (create-fx-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-fx-registry-runtime-state))}}]
   {::runtime ::fx-registry-runtime
    :state* state*}))

(def ^:dynamic *fx-registry-runtime* nil)

(def ^:private _fx-registry-runtime (delay (create-fx-registry-runtime)))

(defn- fx-registry-runtime?
  [runtime]
  (and (map? runtime)
       (= ::fx-registry-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-fx-registry-runtime
  [runtime f]
  (when-not (fx-registry-runtime? runtime)
    (throw (ex-info "Expected FX registry runtime"
                    {:value runtime})))
  (binding [*fx-registry-runtime* runtime]
    (f)))

(defmacro with-fx-registry-runtime
  [runtime & body]
  `(call-with-fx-registry-runtime ~runtime (fn [] ~@body)))

(defn- current-fx-registry-runtime
  []
  (or *fx-registry-runtime*
      @_fx-registry-runtime))

(defn- fx-registry-state-atom
  []
  (:state* (current-fx-registry-runtime)))

(defn- fx-registry-state-snapshot
  []
  @(fx-registry-state-atom))

(defn- update-fx-registry-state!
  [f & args]
  (apply swap! (fx-registry-state-atom) f args))

(defn- assert-registry-open!
  []
  (when (:frozen? (fx-registry-state-snapshot))
    (throw (ex-info "FX channel registry is frozen" {}))))

(defn register-fx-channel!
  "Register a handler for a context-channel key.
  `channel-key` is a namespaced keyword like `:railgun/fx-shot`.
  `handler-fn`  is `(fn [ctx-id channel payload])`.
  Multiple channels may share the same handler-fn."
  [channel-key handler-fn]
  (when-not (and (keyword? channel-key) (fn? handler-fn))
    (throw (IllegalArgumentException. "register-fx-channel!: channel-key must be keyword, handler-fn must be fn")))
  (assert-registry-open!)
  (update-fx-registry-state!
    update :handlers
    (fn [handlers]
      (if (contains? handlers channel-key)
        handlers
        (assoc handlers channel-key handler-fn))))
  nil)

(defn register-fx-channels!
  "Convenience — register the same handler for multiple channel keys."
  [channel-keys handler-fn]
  (when-not (and (every? keyword? channel-keys) (fn? handler-fn))
    (throw (IllegalArgumentException. "register-fx-channels!: channel-keys must be keywords, handler-fn must be fn")))
  (doseq [channel-key channel-keys]
    (register-fx-channel! channel-key handler-fn))
  nil)

(defn freeze-fx-registry!
  []
  (update-fx-registry-state! assoc :frozen? true)
  nil)

(defn fx-registry-snapshot
  []
  (fx-registry-state-snapshot))

(defn reset-fx-registry-for-test!
  []
  (reset! (fx-registry-state-atom) (default-fx-registry-runtime-state))
  nil)

(defn dispatch-fx-channel!
  "Dispatch a context-channel push to its registered handler.
  Returns true when a handler was found and called, false otherwise."
  [ctx-id channel payload]
  (if-let [handler-fn (get (:handlers (fx-registry-state-snapshot)) channel)]
    (do (handler-fn ctx-id channel payload)
        true)
    (do (log/debug "No FX handler for channel" channel)
        false)))

(defn registered-channels
  "Return the set of currently-registered channel keys (for diagnostics)."
  []
  (set (keys (:handlers (fx-registry-state-snapshot)))))
