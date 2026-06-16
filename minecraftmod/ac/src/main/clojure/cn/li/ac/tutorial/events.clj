(ns cn.li.ac.tutorial.events
  "Server-side tutorial item event handlers.

  Platform glue dispatches through cn.li.mcmod.platform.tutorial-events.
  When a player crafts, picks up, or smelts an item, this namespace checks
  whether any tutorial conditions are now met and activates those tutorials.

  Pure business logic — no platform imports."
  (:require [cn.li.ac.tutorial.player :as tut-player]
            [cn.li.ac.tutorial.registry :as tut-registry]
            [cn.li.ac.tutorial.model :as model]
            [cn.li.ac.tutorial.conditions :as conds]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.platform.tutorial-events :as tutorial-platform]
            [cn.li.mcmod.util.log :as log]))

(defn install-tutorial-activated-hook!
  "Register a callback that fires on tutorial activation.
  Callback receives [player-uuid-string tut-id-keyword]."
  [hook-fn]
  (tutorial-platform/register-tutorial-activated-hook! hook-fn))

;; Cache the tutorial-condition-map after first build
(def ^:private tutorial-cond-map*
  (atom nil))

(defn- ensure-tutorial-cond-map!
  []
  (when-not @tutorial-cond-map*
    (reset! tutorial-cond-map*
            (-> (tut-registry/all-tutorials)
                (conds/extend-terminal-conditions)
                (conds/build-tutorial-condition-map))))
  @tutorial-cond-map*)

(defn on-item-event!
  "Called when a player acquires an item (craft/smelt/pickup).

  Args:
    player-uuid — player UUID string
    item-id     — runtime item id (e.g. \"my_mod:constrained_ore\")
    event-type  — :item-crafted | :item-smelted | :item-pickup

  Marks matching condition flags and sets the dirty flag.  Actual
  activation checking is deferred to process-pending-activations!,
  called by a 3-tick interval server tick handler (matching upstream
  TutorialData TickScheduler)."
  [player-uuid item-id event-type]
  (let [session-id (runtime-hooks/require-player-state-session-id "tutorial.events")
        matching (conds/find-matching-conditions item-id event-type)]
    (when (seq matching)
      ;; Mark all matching conditions as fulfilled + set dirty flag
      (tut-player/update-state! session-id player-uuid
                                (fn [state]
                                  (-> state
                                      (reduce model/mark-condition! matching)
                                      (model/mark-dirty!)))))))

(defn process-pending-activations!
  "Called periodically (every 3 server ticks) to check whether any
  dirty player state has newly-satisfied tutorial conditions.

  Args:
    player-uuid — player UUID string

  Reads the player's tutorial state.  If dirty, checks for new
  activations, activates any newly-qualifying tutorials, and clears
  the dirty flag.  Idempotent when state is clean."
  [player-uuid]
  (let [session-id (runtime-hooks/require-player-state-session-id "tutorial.events")
        state (tut-player/state session-id player-uuid)]
    (when (model/dirty? state)
      (let [cond-map (ensure-tutorial-cond-map!)
            new-acts (conds/check-new-activations state cond-map)]
        (doseq [tut-id new-acts]
          (tut-player/activate-tutorial! session-id player-uuid tut-id)
          (log/info "Tutorial activated by condition (batched)"
                    {:player player-uuid
                     :tutorial (name tut-id)})
          (try (tutorial-platform/notify-tutorial-activated! player-uuid tut-id)
               (catch Throwable _))))
      (tut-player/update-state! session-id player-uuid model/clear-dirty!))))

(defn register-platform-handlers!
  "Register tutorial business handlers with the mcmod platform bridge."
  []
  (tutorial-platform/register-tutorial-handlers!
   {:on-item-event! on-item-event!
    :process-pending-activations! process-pending-activations!})
  nil)
