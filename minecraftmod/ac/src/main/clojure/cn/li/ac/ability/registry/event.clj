(ns cn.li.ac.ability.registry.event
  "Ability domain events.

  Events are plain maps dispatched through the platform event bus.
  Listeners subscribe in game content namespaces (no Minecraft imports here).

  Convention:
    {:event/type   :ability/<name>
     :event/side   :server | :client | :both
     ... payload fields}

  To fire an event call `fire-ability-event!`.
  To listen register a subscriber via `subscribe-ability-event!`."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Subscriber Registry (lightweight, no Forge EventBus dependency)
;; ============================================================================

(defonce ^:private subscribers (atom {}))

(defn subscribe-ability-event!
  "Register a subscriber fn for event-type keyword.
  Multiple subscribers per event-type are supported.

  Args:
    event-type: keyword  e.g. :ability/skill-learn
    handler-fn: (fn [event-map]) → any"
  [event-type handler-fn]
  (swap! subscribers update event-type (fnil conj []) handler-fn)
  nil)

(defn fire-ability-event!
  "Dispatch an ability event map to all registered subscribers.
  Always returns the original event map (useful for pipeline patterns)."
  [event]
  (let [event-type (:event/type event)]
    (doseq [h (get @subscribers event-type [])]
      (try
        (h event)
        (catch Exception e
          (log/warn "Ability event subscriber threw" event-type (ex-message e)))))
    event))

(defn fire-calc-event!
  "Dispatch a mutable calc event. Subscribers may update fields via returned atom.
  Returns final computed value.

  Args:
    event-type: keyword  e.g. :calc/skill-attack
    base-value: number
    extra:      map of extra context fields

  Returns:
    Final number after all subscribers have adjusted it."
  [event-type base-value extra]
  (let [state (atom (merge extra {:event/type event-type :value base-value}))]
    (doseq [h (get @subscribers event-type [])]
      (try
        (let [result (h @state)]
          (when (number? result)
            (swap! state assoc :value result)))
        (catch Exception e
          (log/warn "Calc event subscriber threw" event-type (ex-message e)))))
    (:value @state)))

;; ============================================================================
;; Event Type Constants
;; ============================================================================

;; -- Lifecycle --
(def EVT-ABILITY-ACTIVATE    :ability/activate)
(def EVT-ABILITY-DEACTIVATE  :ability/deactivate)

;; -- Learning --
(def EVT-SKILL-LEARN         :ability/skill-learn)
(def EVT-SKILL-EXP-ADDED     :ability/skill-exp-added)
(def EVT-SKILL-EXP-CHANGED   :ability/skill-exp-changed)
(def EVT-LEVEL-CHANGE        :ability/level-change)
(def EVT-CATEGORY-CHANGE     :ability/category-change)

;; -- Resource --
(def EVT-OVERLOAD            :ability/overload)

;; -- Input --
(def EVT-PRESET-UPDATE       :ability/preset-update)
(def EVT-PRESET-SWITCH       :ability/preset-switch)
(def EVT-CONTEXT-KEY-DOWN    :ability/context-key-down)
(def EVT-CONTEXT-KEY-TICK    :ability/context-key-tick)
(def EVT-CONTEXT-KEY-UP      :ability/context-key-up)
(def EVT-CONTEXT-KEY-ABORT   :ability/context-key-abort)

;; -- Calc (modifiable) --
(def CALC-SKILL-ATTACK       :calc/skill-attack)
(def CALC-MAX-CP             :calc/max-cp)
(def CALC-MAX-OVERLOAD       :calc/max-overload)
(def CALC-CP-RECOVER-SPEED   :calc/cp-recover-speed)
(def CALC-OVERLOAD-RECOVER-SPEED :calc/overload-recover-speed)

;; ============================================================================
;; Helper Constructors
;; ============================================================================

(defn make-skill-learn-event [uuid skill-id]
  {:event/type EVT-SKILL-LEARN :event/side :both :uuid uuid :skill-id skill-id})

(defn make-level-change-event [uuid old-level new-level]
  {:event/type EVT-LEVEL-CHANGE :event/side :server
   :uuid uuid :old-level old-level :new-level new-level})

(defn make-category-change-event [uuid old-cat new-cat]
  {:event/type EVT-CATEGORY-CHANGE :event/side :both
   :uuid uuid :old-cat old-cat :new-cat new-cat})

(defn make-overload-event [uuid]
  {:event/type EVT-OVERLOAD :event/side :server :uuid uuid})

(defn make-activate-event [uuid]
  {:event/type EVT-ABILITY-ACTIVATE :event/side :server :uuid uuid})

(defn make-deactivate-event [uuid]
  {:event/type EVT-ABILITY-DEACTIVATE :event/side :server :uuid uuid})

(defn make-preset-switch-event [uuid old-idx new-idx]
  {:event/type EVT-PRESET-SWITCH :event/side :both
   :uuid uuid :old-preset old-idx :new-preset new-idx})
