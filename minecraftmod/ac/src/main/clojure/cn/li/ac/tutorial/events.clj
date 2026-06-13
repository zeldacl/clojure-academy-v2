(ns cn.li.ac.tutorial.events
  "Server-side tutorial item event handlers.

  Called from platform glue (Forge event listeners) via requiring-resolve.
  When a player crafts, picks up, or smelts an item, this namespace checks
  whether any tutorial conditions are now met and activates those tutorials.

  Pure business logic — no platform imports."
  (:require [cn.li.ac.tutorial.player :as tut-player]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.model :as model]
            [cn.li.ac.tutorial.conditions :as conds]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

;; Cache the tutorial-condition-map after first build
(def ^:private tutorial-cond-map*
  (atom nil))

(defn- ensure-tutorial-cond-map!
  []
  (when-not @tutorial-cond-map*
    (reset! tutorial-cond-map*
            (conds/build-tutorial-condition-map (tut-registry/all-tutorials))))
  @tutorial-cond-map*)

(defn on-item-event!
  "Called when a player acquires an item (craft/smelt/pickup).

  Args:
    player-uuid — player UUID string
    item-id     — runtime item id (e.g. \"my_mod:constrained_ore\")
    event-type  — :item-crafted | :item-smelted | :item-pickup

  Looks up matching condition indices, marks them in player state,
  then checks which tutorials should now be activated."
  [player-uuid item-id event-type]
  (let [session-id (runtime-hooks/require-player-state-session-id "tutorial.events")
        matching (conds/find-matching-conditions item-id event-type)]
    (when (seq matching)
      ;; Mark all matching conditions as fulfilled
      (tut-player/update-state! session-id player-uuid
                                (fn [state]
                                  (reduce model/mark-condition! state matching)))
      ;; Check for new activations
      (let [state (tut-player/state session-id player-uuid)
            cond-map (ensure-tutorial-cond-map!)
            new-acts (conds/check-new-activations state cond-map)]
        (doseq [tut-id new-acts]
          (tut-player/activate-tutorial! session-id player-uuid tut-id)
          (log/info "Tutorial activated by condition"
                    {:player player-uuid
                     :tutorial (name tut-id)
                     :item item-id
                     :event event-type}))))))
