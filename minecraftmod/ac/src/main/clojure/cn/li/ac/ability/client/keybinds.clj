(ns cn.li.ac.ability.client.keybinds
  "Key binding state tracking and event handling (AC layer - no Minecraft imports).

  Provides:
  - Activate handler registry (V key stack)
  - Key group system with delegates
  - Pre-check logic (cooldown, resource) before dispatching"
  (:require [cn.li.ac.ability.client.runtime :as runtime]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

;; Dynamic var for getting player UUID (set by forge layer)
(def ^:dynamic *get-player-uuid-fn* nil)

;; ============================================================================
;; Activate Handler Registry (V key stack)
;; ============================================================================
;; Each handler: {:id kw :priority int :handles-fn (fn [uuid]) :on-key-down-fn (fn [uuid]) :hint-fn (fn [uuid])}
;; Higher priority wins. First handler where handles-fn returns true is active.

(defonce ^:private activate-handlers (atom (sorted-map)))

(defn add-activate-handler!
  "Register an activate handler. Higher priority = checked first."
  [{:keys [id priority] :as handler}]
  (swap! activate-handlers assoc [priority id] handler))

(defn remove-activate-handler!
  "Remove an activate handler by id."
  [id]
  (swap! activate-handlers
         (fn [m]
           (into (sorted-map)
                 (remove (fn [[[_ hid] _]] (= hid id)) m)))))

(defn get-active-handler
  "Walk handlers from highest priority down. Return first where handles-fn returns true."
  [player-uuid]
  (let [handlers (reverse (vals @activate-handlers))]
    (first (filter #((:handles-fn %) player-uuid) handlers))))

(defn get-activate-hint
  "Get the hint string from the active handler, or nil."
  [player-uuid]
  (when-let [h (get-active-handler player-uuid)]
    (when-let [hint-fn (:hint-fn h)]
      (hint-fn player-uuid))))

;; ============================================================================
;; Key Group System
;; ============================================================================
;; Groups: {:default {key-idx delegate-map}, :custom-group {key-idx delegate-map}}
;; delegate-map: {:skill-id kw :on-key-down fn :on-key-tick fn :on-key-up fn :on-key-abort fn}

(defonce ^:private key-groups (atom {}))

(defn register-key-delegate!
  "Register a delegate for a key index in a named group."
  [group key-idx delegate-map]
  (swap! key-groups assoc-in [group key-idx] delegate-map))

(defn clear-key-group!
  "Remove all delegates in a group."
  [group]
  (swap! key-groups dissoc group))

(defn clear-all-key-groups!
  "Remove all delegates from all groups."
  []
  (reset! key-groups {}))

(defn get-delegate-for-key
  "Get the effective delegate for a key index. Custom groups override :default."
  [key-idx]
  ;; Walk non-default groups first (any custom override), then default
  (let [groups @key-groups
        custom-groups (dissoc groups :default)]
    (or (some #(get % key-idx) (vals custom-groups))
        (get-in groups [:default key-idx]))))

(defn- get-skill-id-for-slot
  "Get the skill-id bound to a slot from the current preset."
  [player-uuid key-idx]
  (when-let [state (ps/get-player-state player-uuid)]
    (let [pd (:preset-data state)
          slots (preset-data/get-active-slots pd)]
      (when-let [slot (nth slots key-idx nil)]
        (when (and (vector? slot) (= 2 (count slot)))
          (let [[cat-id ctrl-id] slot]
            (when-let [s (skill/get-skill-by-controllable cat-id ctrl-id)]
              (:id s))))))))

(defn get-skill-id-for-slot-public
  "Public accessor for skill-id at a slot index."
  [player-uuid key-idx]
  (get-skill-id-for-slot player-uuid key-idx))

(defn update-default-group!
  "Rebuild default key group from current preset. Called on preset switch/activate."
  [player-uuid]
  (clear-key-group! :default)
  (doseq [idx (range 4)]
    (when-let [skill-id (get-skill-id-for-slot player-uuid idx)]
      (register-key-delegate!
       :default idx
       {:skill-id  skill-id
        :on-key-down  (fn [uuid] (runtime/on-slot-key-down! uuid idx))
        :on-key-tick  (fn [uuid] (runtime/on-slot-key-tick! uuid idx))
        :on-key-up    (fn [uuid] (runtime/on-slot-key-up! uuid idx))
        :on-key-abort (fn [uuid] (runtime/on-slot-key-up! uuid idx))}))))

;; State tracking for key transitions
(defonce ^:private key-states
  (atom {:skill-keys [false false false false]
         :gui-keys {:skill-tree false
                    :preset-editor false}}))

(defn- activated?
  [player-uuid]
  (boolean (get-in (ps/get-player-state player-uuid) [:resource-data :activated])))

(defn- can-use-ability?
  "Check if player can use abilities (not overloaded, not interfered)."
  [player-uuid]
  (when-let [state (ps/get-player-state player-uuid)]
    (let [rd (:resource-data state)]
      (and (:activated rd)
           (:overload-fine rd true)
           (empty? (:interferences rd #{}))))))

(defn- skill-on-cooldown?
  "Check if the skill bound to a delegate is on cooldown."
  [player-uuid delegate]
  (when-let [state (ps/get-player-state player-uuid)]
    (let [cd (:cooldown-data state)
          ctrl-id (or (:ctrl-id delegate) (:skill-id delegate))]
      (when ctrl-id
        (cd-data/in-cooldown? cd ctrl-id :main)))))

(defn- should-abort-key?
  "Pre-check: returns true if we should abort instead of dispatching normally."
  [player-uuid delegate]
  (or (not (can-use-ability? player-uuid))
      (skill-on-cooldown? player-uuid delegate)))

(defn- get-client-player-uuid
  "Get current client player UUID. Must be provided by forge layer."
  []
  (when *get-player-uuid-fn*
    (*get-player-uuid-fn*)))

(defn on-skill-key-event
  "Handle skill key state change. Uses delegate system with pre-checks."
  [key-idx is-down]
  (let [was-down (get-in @key-states [:skill-keys key-idx])]
    (when-let [player-uuid (get-client-player-uuid)]
      (if (activated? player-uuid)
        (when-let [delegate (get-delegate-for-key key-idx)]
          (let [abort? (should-abort-key? player-uuid delegate)]
            (cond
              ;; Key pressed (transition from up to down)
              (and (not was-down) is-down)
              (if abort?
                (when-let [f (:on-key-abort delegate)] (f player-uuid))
                (when-let [f (:on-key-down delegate)] (f player-uuid)))

              ;; Key held (still down)
              (and was-down is-down)
              (if abort?
                (when-let [f (:on-key-abort delegate)] (f player-uuid))
                (when-let [f (:on-key-tick delegate)] (f player-uuid)))

              ;; Key released (transition from down to up)
              (and was-down (not is-down))
              (when-let [f (:on-key-up delegate)] (f player-uuid)))))
        ;; Ability mode disabled: abort local held state cleanly.
        (when was-down
          (when-let [delegate (get-delegate-for-key key-idx)]
            (when-let [f (:on-key-up delegate)] (f player-uuid))))))

    ;; Update state
    (swap! key-states assoc-in [:skill-keys key-idx] is-down)))


(defn on-gui-key-event
  "Handle GUI key state change. Opens screens or toggles mode on key press."
  [gui-type is-down]
  (let [was-down (get-in @key-states [:gui-keys gui-type])]
    (when-let [player-uuid (get-client-player-uuid)]
      ;; GUI keys trigger on key press only.
      (when (and (not was-down) is-down)
        (case gui-type
          :skill-tree
          (client-bridge/open-skill-tree-screen! player-uuid)

          :preset-editor
          (client-bridge/open-preset-editor-screen! player-uuid)

          nil)))

    ;; Update state
    (swap! key-states assoc-in [:gui-keys gui-type] is-down)))

(defn trigger-mode-switch!
  "V key short-press handler. Delegates to the active activate handler.
  If no handler matches, falls through to default toggle."
  ([]
   (trigger-mode-switch! nil))
  ([player-uuid]
   (when-let [player-uuid (or player-uuid (get-client-player-uuid))]
     (if-let [handler (get-active-handler player-uuid)]
       (do
         (log/info "[V-TRACE][AC][CLIENT][HANDLER]"
                   {:handler-id (:id handler)
                    :uuid (str player-uuid)})
         ((:on-key-down-fn handler) player-uuid))
       ;; Fallback: simple toggle
       (let [state   (ps/get-player-state player-uuid)
             current (boolean (get-in state [:resource-data :activated]))
             next-state (not current)]
         (log/info "[V-TRACE][AC][CLIENT][TOGGLE]"
                   {:uuid (str player-uuid)
                    :current current
                    :next next-state})
         (api/req-set-activated! next-state nil))))))

(defn tick-keys!
  "Main tick function called by forge layer. key-state-fn returns boolean for each key."
  [key-state-fn]
  ;; Poll skill keys (Z, X, C, V)
  (doseq [idx (range 4)]
    (on-skill-key-event idx (key-state-fn [:skill idx])))

  ;; Poll GUI keys
  (on-gui-key-event :skill-tree (key-state-fn [:gui :skill-tree]))
  (on-gui-key-event :preset-editor (key-state-fn [:gui :preset-editor])))

(defn reset-all-keys!
  "Reset all key states. Called on disconnect or dimension change."
  []
  (reset! key-states {:skill-keys [false false false false]
                      :gui-keys {:skill-tree false
                                 :preset-editor false}})
  (clear-all-key-groups!))

;; ============================================================================
;; Preset Switch
;; ============================================================================

(defonce ^:private preset-switch-state
  (atom {:current-preset 0
         :show-until-ms  0}))

(def ^:private PRESET-COUNT 4)
(def ^:private PRESET-INDICATOR-DURATION-MS 2000)

(defn switch-preset!
  "Cycle to next preset. Called by platform key handler."
  [player-uuid]
  (when (activated? player-uuid)
    (let [next-idx (mod (inc (:current-preset @preset-switch-state)) PRESET-COUNT)]
      (swap! preset-switch-state assoc
             :current-preset next-idx
             :show-until-ms (+ (System/currentTimeMillis) PRESET-INDICATOR-DURATION-MS))
      (api/req-switch-preset! next-idx nil)
      (update-default-group! player-uuid)
      (log/info "[PRESET-SWITCH]" {:preset next-idx}))))

(defn get-preset-switch-state
  "Get current preset switch state for HUD rendering."
  []
  @preset-switch-state)

;; ============================================================================
;; Default Activate Handlers
;; ============================================================================

(defn- has-active-contexts?
  "Check if the player has any non-terminated contexts."
  [player-uuid]
  (seq (filter #(not= (:status %) :terminated)
               (ctx/get-all-contexts-for-player player-uuid))))

(defn install-default-handlers!
  "Register default activate handlers. Call once at init."
  []
  ;; Priority 0: Default toggle (lowest priority)
  (add-activate-handler!
   {:id              :default-toggle
    :priority        0
    :handles-fn      (fn [_uuid] true)
    :on-key-down-fn  (fn [uuid]
                       (let [state   (ps/get-player-state uuid)
                             current (boolean (get-in state [:resource-data :activated]))]
                         (api/req-set-activated! (not current) nil)))
    :hint-fn         (fn [uuid]
                       (if (activated? uuid)
                         "ac.activate.hint.deactivate"
                         "ac.activate.hint.activate"))})

  ;; Priority 10: Abort active contexts (higher priority than toggle)
  (add-activate-handler!
   {:id              :abort-delegates
    :priority        10
    :handles-fn      (fn [uuid] (boolean (has-active-contexts? uuid)))
    :on-key-down-fn  (fn [uuid]
                       (ctx/abort-all-contexts-for-player! uuid nil)
                       (log/info "[V-TRACE] Aborted all contexts for" uuid))
    :hint-fn         (fn [_uuid] "ac.activate.hint.abort")}))
