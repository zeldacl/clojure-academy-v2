(ns cn.li.ac.content.ability.meltdowner.damage-helper
  "Combat Core adapter for Meltdowner radiation marks.

   Marks are domain state now. This namespace keeps the content-facing API
   used by projectile abilities, but only emits Combat Core domain events; it
   never creates Context state, player commands, or mutable damage handlers."
  (:require [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.mcmod.hooks.core :as hooks]
            [cn.li.ac.content.ability.meltdowner.rad-intensify :as rad]))

(def ^:private mark-sequence* (atom 0))

(defn- normalize-id [id]
  (when (some? id) (str id)))

(defn- current-server-tick []
  (long (or (:server-tick-id (hooks/player-state-owner)) 0)))

(defn- event-id [kind source target tick]
  [kind source target tick (swap! mark-sequence* inc)])

(defn- learned-rad-intensify? [player-id]
  (boolean
   (when-let [state (skill-effects/get-player-state player-id)]
     (adata/is-learned? (:ability-data state) :rad-intensify))))

(defn clear-all-marks! []
  (combat-runtime/dispatch-domain-event!
   {:type :radiation-marks-clear-all
    :owner :system
    :event-id (event-id :radiation-marks-clear-all :system :all
                        (current-server-tick))})
  nil)

(defn on-server-stop! [_session-id]
  (clear-all-marks!)
  nil)

(defn reset-marks-for-test!
  ([] (reset-marks-for-test! {}))
  ([snapshot]
   (clear-all-marks!)
   (doseq [[target-id mark] snapshot]
     (combat-runtime/dispatch-domain-event!
      (merge mark
             {:type :radiation-mark
              :target-id (normalize-id target-id)
              :event-id (event-id :radiation-mark
                                  (:source-player-id mark)
                                  target-id
                                  (current-server-tick))})))
   nil))

(defn marks-snapshot []
  (or (:radiation-marks (combat-runtime/domain-state)) {}))

(defn clear-mark!
  ([target-id]
   (when-let [target (normalize-id target-id)]
     (combat-runtime/dispatch-domain-event!
      {:type :radiation-mark-clear
       :target-id target
       :event-id (event-id :radiation-mark-clear :system target
                           (current-server-tick))}))
   nil)
  ([_source-player-id target-id] (clear-mark! target-id)))

(defn clear-target-mark! [target-id] (clear-mark! target-id))
(defn clear-target-marks! [target-id] (clear-target-mark! target-id))

(defn clear-source-marks! [source-player-id]
  (when source-player-id
    (combat-runtime/dispatch-domain-event!
     {:type :combat-owner-clear
      :owner (normalize-id source-player-id)
      :event-id (event-id :combat-owner-clear source-player-id :all
                          (current-server-tick))}))
  nil)

(defn clear-expired-marks! []
  ;; Expiration is a normal Combat Core tick, not a second per-player scan.
  (combat-runtime/dispatch-domain-event!
   {:type :combat-tick
    :tick (current-server-tick)
    :event-id (event-id :combat-tick :system :marks (current-server-tick))})
  nil)

(defn tick-marks! []
  (clear-expired-marks!))

(defn ensure-damage-handler!
  "Compatibility-shaped no-op for content tests during source migration.

   Damage is already compiled into Combat Core's deterministic pipeline; no
   mutable registry is installed here."
  []
  nil)

(defn mark-target!
  ([attacker-id target-id] (mark-target! attacker-id target-id nil))
  ([attacker-id target-id _fx-context]
   (let [source (normalize-id attacker-id)
         target (normalize-id target-id)
         tick (current-server-tick)]
     (when (and source target (learned-rad-intensify? source))
       (let [existing (get (marks-snapshot) target)
             duration (max 60 (long (or (:ticks-left existing) 0)))
             rate (double (rad/rate source))]
         (combat-runtime/dispatch-domain-event!
          {:type :radiation-mark
           :source-player-id source
           :target-id target
           :duration duration
           :rate rate
           :tick tick
           :event-id (event-id :radiation-mark source target tick)})))))
   nil))

(defn init! []
  ;; Keep the content exp accessor, but deliberately do not register a
  ;; mutable damage handler: damage amplification is a Combat Core provider.
  (skill-effects/register-custom-skill-exp! :rad-intensify rad/skill-exp)
  nil)
