(ns cn.li.ac.ability.client.fx-registry
  "Client-side FX channel registry.

  Skills register FX channel handlers at load time.  The platform bridge
  delegates all incoming context-channel pushes to `dispatch-fx-channel!`
  instead of hard-coding a case statement per skill."
  (:require [cn.li.mcmod.util.log :as log]))

;; channel-key → handler-fn
;; handler-fn signature: (fn [ctx-id channel payload])
(defonce ^:private fx-handlers (atom {}))
(defonce ^:private fx-handlers-frozen? (atom false))

(defn- assert-registry-open!
  []
  (when @fx-handlers-frozen?
    (throw (ex-info "FX channel registry is frozen" {}))))

(defn register-fx-channel!
  "Register a handler for a context-channel key.
  `channel-key` is a namespaced keyword like `:railgun/fx-shot`.
  `handler-fn`  is `(fn [ctx-id channel payload])`.
  Multiple channels may share the same handler-fn."
  [channel-key handler-fn]
  {:pre [(keyword? channel-key) (fn? handler-fn)]}
  (assert-registry-open!)
  (swap! fx-handlers
         (fn [handlers]
           (if (contains? handlers channel-key)
             handlers
             (assoc handlers channel-key handler-fn))))
  nil)

(defn register-fx-channels!
  "Convenience — register the same handler for multiple channel keys."
  [channel-keys handler-fn]
  {:pre [(every? keyword? channel-keys) (fn? handler-fn)]}
  (doseq [channel-key channel-keys]
    (register-fx-channel! channel-key handler-fn))
  nil)

(defn freeze-fx-registry!
  []
  (reset! fx-handlers-frozen? true)
  nil)

(defn fx-registry-snapshot
  []
  {:handlers @fx-handlers
   :frozen? @fx-handlers-frozen?})

(defn reset-fx-registry-for-test!
  []
  (reset! fx-handlers {})
  (reset! fx-handlers-frozen? false)
  nil)

(defn dispatch-fx-channel!
  "Dispatch a context-channel push to its registered handler.
  Returns true when a handler was found and called, false otherwise."
  [ctx-id channel payload]
  (if-let [handler-fn (get @fx-handlers channel)]
    (do (handler-fn ctx-id channel payload)
        true)
    (do (log/debug "No FX handler for channel" channel)
        false)))

(defn registered-channels
  "Return the set of currently-registered channel keys (for diagnostics)."
  []
  (set (keys @fx-handlers)))
