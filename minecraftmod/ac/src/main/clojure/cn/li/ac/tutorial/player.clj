(ns cn.li.ac.tutorial.player
  "Session-scoped tutorial state in the ability runtime store.

  Follows the exact same pattern as ac.terminal.player for the
  ac.ability.service.runtime-store S2 storage layer.

  Tutorial state is stored under the :tutorial-data key (defined by
  tutorial.model/state-key) alongside :terminal-data, :ability-data, etc."
  (:require [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.tutorial.model :as model]
            [cn.li.mcmod.util.log :as log]))

;; --- State read/write ---

(defn state
  "Read tutorial state for a player.  Returns fresh-state when not yet
  initialized."
  [session-id uuid-str]
  (let [player-state (store/get-player-state* session-id uuid-str)]
    (or (get player-state model/state-key)
        (model/fresh-state))))

(defn update-state!
  "Apply f to the player's tutorial state, persisting the :tutorial-data
  key back into the store.  Marks the player dirty for persistence."
  [session-id uuid-str f & args]
  (store/update-player-state!* session-id
                               uuid-str
                               #(assoc % model/state-key
                                       (apply f (or (get % model/state-key)
                                                      (model/fresh-state))
                                              args)))
  (store/mark-player-dirty! session-id uuid-str))

;; --- Activation ---

(defn is-activated?
  "True when `tut-id` has been explicitly activated (or is default-installed,
  which is checked client-side in registry/group-by-activated)."
  [session-id uuid-str tut-id]
  (model/is-activated? (state session-id uuid-str) (keyword tut-id)))

(defn activate-tutorial!
  "Mark a tutorial as activated.  Idempotent.
  Returns the updated state map."
  [session-id uuid-str tut-id]
  (log/info "Activating tutorial" (name tut-id) "for" uuid-str)
  (update-state! session-id uuid-str model/activate-tutorial (keyword tut-id)))

;; --- Misaka ID ---

(defn get-misaka-id
  "Return the player's random Misaka No. (1000–19000).  Lazy-initializes on
  first access."
  [session-id uuid-str]
  (let [current (:misaka-id (state session-id uuid-str))]
    (if current
      current
      (let [updated (update-state! session-id uuid-str model/ensure-misaka-id)]
        (:misaka-id updated)))))

;; --- Auto-give item flag ---

(defn tutorial-acquired?
  "True if the tutorial item has already been auto-given to this player."
  [session-id uuid-str]
  (model/tutorial-acquired? (state session-id uuid-str)))

(defn mark-tutorial-acquired!
  "Record that the auto-give tutorial item has been granted."
  [session-id uuid-str]
  (update-state! session-id uuid-str model/mark-tutorial-acquired!))

;; --- First-open flag ---

(defn first-open?
  "True when the first-open animation has not been played yet."
  [session-id uuid-str]
  (model/first-open? (state session-id uuid-str)))

(defn mark-first-open-done!
  "Record that the first-open animation has played."
  [session-id uuid-str]
  (update-state! session-id uuid-str model/mark-first-open-done!))

;; --- Ensure initialized ---

(defn ensure-state!
  "Lazy-initialize tutorial state if it doesn't exist yet.
  Safe to call multiple times."
  [session-id uuid-str]
  (when-not (get (store/get-player-state* session-id uuid-str) model/state-key)
    (store/update-player-state!* session-id
                                 uuid-str
                                 #(assoc % model/state-key (model/fresh-state)))))
