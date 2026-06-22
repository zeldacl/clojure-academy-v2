(ns cn.li.ac.tutorial.player
  "Server-side tutorial player state — persisted directly to player NBT.

  Mirrors upstream AcademyCraft TutorialData: each domain writes its own NBT key
  independently, instead of going through a centralized runtime-store.

  Uses player.getPersistentData() for storage — Minecraft handles save/load
  automatically with the player entity."
  (:require [cn.li.ac.tutorial.model :as model]
            [cn.li.mcmod.platform.player-persistent-data :as player-pd]
            [cn.li.mcmod.util.log :as log]))

(def ^:private nbt-key "academy_tutorial")

;; --- NBT helpers ---

(defn- load-state
  [tag]
  (when (.contains tag nbt-key)
    (try (clojure.edn/read-string (.getString tag nbt-key))
         (catch Exception e (log/warn "Failed to load tutorial NBT state:" (ex-message e)) nil))))

(defn- save-state!
  [tag state]
  (.putString tag nbt-key (pr-str state)))

;; --- Public API ---

(defn state
  "Read tutorial state for a player. Returns fresh-state when not yet initialized."
  [player]
  (or (load-state (player-pd/get-persistent-data! player))
      (model/fresh-state)))

(defn update-state!
  "Apply f to the player's tutorial state and write back to NBT.
  f receives the current state (or fresh-state) and should return the new state."
  [player f & args]
  (let [tag (player-pd/get-persistent-data! player)
        current (or (load-state tag) (model/fresh-state))
        new-state (apply f current args)]
    (save-state! tag new-state)
    new-state))

;; --- Activation ---

(defn is-activated?
  "True when `tut-id` has been explicitly activated (or is default-installed)."
  [player tut-id]
  (model/is-activated? (state player) (keyword tut-id)))

(defn activate-tutorial!
  "Mark a tutorial as activated. Idempotent."
  [player tut-id]
  (log/info "Activating tutorial" (name tut-id) "for" (str (.getUUID player)))
  (update-state! player model/activate-tutorial (keyword tut-id)))

;; --- Misaka ID ---

(defn get-misaka-id
  "Return the player's random Misaka No. (1000–19000). Lazy-initializes on first access."
  [player]
  (let [current (:misaka-id (state player))]
    (if current
      current
      (do (update-state! player model/ensure-misaka-id)
          (:misaka-id (state player))))))

;; --- First-open flag ---

(defn first-open?
  "True when the first-open animation has not been played yet."
  [player]
  (model/first-open? (state player)))

(defn mark-first-open-done!
  "Record that the first-open animation has played."
  [player]
  (update-state! player model/mark-first-open-done!))

;; --- Condition checking (called by events.clj) ---

(defn mark-conditions-and-set-dirty!
  "Mark condition flags and set dirty flag in one update."
  [player matching-conditions]
  (update-state! player
                 (fn [s]
                   (-> s
                       (reduce model/mark-condition! matching-conditions)
                       (model/mark-dirty!)))))

(defn process-pending!
  "If state is dirty, check for new activations and clear dirty flag.
  Returns [new-activations]."
  [player cond-map]
  (let [s (state player)]
    (when (model/dirty? s)
      (let [new-acts (resolve 'cn.li.ac.tutorial.conditions/check-new-activations)
            check-fn (requiring-resolve 'cn.li.ac.tutorial.conditions/check-new-activations)]
        (when check-fn
          (let [acts (check-fn s cond-map)
                _ (doseq [tut-id acts]
                    (activate-tutorial! player tut-id))]
            (update-state! player model/clear-dirty!)
            acts))))))
