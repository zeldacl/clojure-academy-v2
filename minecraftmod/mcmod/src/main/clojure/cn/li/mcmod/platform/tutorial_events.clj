(ns cn.li.mcmod.platform.tutorial-events
  "Platform-neutral tutorial item-event and activation hooks."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defn- default-tutorial-events-state []
  {:on-item-event! (fn [_ _ _] nil)
   :process-pending-activations! (fn [_] nil)
   :tutorial-activated-hook (fn [_ _] nil)})

(defn create-tutorial-events-runtime
  ([] (create-tutorial-events-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.platform.tutorial-events/runtime ::tutorial-events-runtime
    :state* (or state* (atom (default-tutorial-events-state)))}))

(def ^:private _tutorial-events-runtime (delay (create-tutorial-events-runtime)))

(def ^:dynamic *tutorial-events-runtime* nil)

(defn- tutorial-events-atom []
  (:state* (or *tutorial-events-runtime*
                  @_tutorial-events-runtime)))

(defn- tutorial-events-snapshot []
  @(tutorial-events-atom))

(defn register-tutorial-handlers!
  [handlers]
  (doseq [[k v] handlers]
    (prt/register-hook! (tutorial-events-atom) k v
                        :duplicate-policy :same-value-idempotent
                        :label "tutorial-events"))
  nil)

(defn register-tutorial-activated-hook!
  [hook-fn]
  (register-tutorial-handlers! {:tutorial-activated-hook hook-fn}))

(defn reset-tutorial-events-for-test!
  []
  (reset! (tutorial-events-atom) (default-tutorial-events-state))
  nil)

(defn on-item-event!
  [player item-id event-type]
  ((:on-item-event! (tutorial-events-snapshot)) player item-id event-type))

(defn process-pending-activations!
  [player]
  ((:process-pending-activations! (tutorial-events-snapshot)) player))

(defn notify-tutorial-activated!
  [player-uuid tut-id]
  ((:tutorial-activated-hook (tutorial-events-snapshot)) player-uuid tut-id))
