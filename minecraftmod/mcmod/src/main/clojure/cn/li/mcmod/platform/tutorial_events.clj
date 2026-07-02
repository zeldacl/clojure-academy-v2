(ns cn.li.mcmod.platform.tutorial-events
  "Platform-neutral tutorial item-event and activation hooks.

  State stored in Framework [:service :tutorial-events]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn- default-state []
  {:on-item-event! (fn [_ _ _] nil)
   :process-pending-activations! (fn [_] nil)
   :tutorial-activated-hook (fn [_ _] nil)})

;; ============================================================================
;; State access — Framework [:service :tutorial-events]
;; ============================================================================

(def ^:private hooks-path [:service :tutorial-events])

(defn- hooks-snapshot []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom hooks-path) (default-state))
    (default-state)))

(defn register-tutorial-handlers!
  "Register tutorial event handler functions. Duplicate keys must have same value."
  [handlers]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in hooks-path
           (fn [current]
             (let [base (or current (default-state))]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting tutorial handler"
                                              {:key k :existing (get m k) :new v}))
                              (assoc m k v)))
                          base handlers)))))
  nil)

(defn register-tutorial-activated-hook!
  [hook-fn]
  (register-tutorial-handlers! {:tutorial-activated-hook hook-fn}))

(defn reset-tutorial-events-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in hooks-path (default-state)))
  nil)

(defn on-item-event!
  [player item-id event-type]
  ((:on-item-event! (hooks-snapshot)) player item-id event-type))

(defn process-pending-activations!
  [player]
  ((:process-pending-activations! (hooks-snapshot)) player))

(defn notify-tutorial-activated!
  [player-uuid tut-id]
  ((:tutorial-activated-hook (hooks-snapshot)) player-uuid tut-id))
