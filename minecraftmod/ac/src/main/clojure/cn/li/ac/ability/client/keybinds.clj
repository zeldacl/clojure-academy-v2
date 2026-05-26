(ns cn.li.ac.ability.client.keybinds
  "Key binding state tracking and event handling (AC layer - no Minecraft imports).

  Provides:
  - Activate handler registry (V key stack)
  - Key group system with delegates
  - Pre-check logic (cooldown, resource) before dispatching"
  (:require [cn.li.ac.ability.client.runtime :as runtime]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.service.dispatcher :as ctx]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.model.preset :as preset-data]
            [cn.li.ac.ability.util.resource-check :as resource-check]
            [cn.li.ac.ability.registry.skill-query :as skill]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.util.log :as log]))

;; Dynamic var for getting player UUID (set by forge layer)
(def ^:dynamic *get-player-uuid-fn* nil)

(def ^:dynamic *client-session-id* nil)

;; ============================================================================
;; Activate Handler Registry (V key stack)
;; ============================================================================
;; Each handler: {:id kw :priority int :handles-fn (fn [uuid]) :on-key-down-fn (fn [uuid]) :hint-fn (fn [uuid])}
;; Higher priority wins. First handler where handles-fn returns true is active.

(defonce ^:private activate-handlers (atom (sorted-map)))

(defonce ^:private keybind-registries-frozen? (atom false))

(defn- assert-keybind-registries-open!
  []
  (when @keybind-registries-frozen?
    (throw (ex-info "Keybind registries are frozen" {}))))

(defn- find-activate-handler-entry
  [id]
  (first (filter (fn [[[_ handler-id] _handler]] (= id handler-id)) @activate-handlers)))

(defn add-activate-handler!
  "Register an activate handler. Higher priority = checked first."
  [{:keys [id priority] :as handler}]
  (assert-keybind-registries-open!)
  (when-not (keyword? id)
    (throw (ex-info "Activate handler id must be a keyword" {:id id})))
  (let [priority (int (or priority 0))]
    (if-let [[[existing-priority _] _existing-handler] (find-activate-handler-entry id)]
      (when (not= existing-priority priority)
        (throw (ex-info "Conflicting activate handler id"
                        {:id id
                         :existing-priority existing-priority
                         :new-priority priority})))
      (swap! activate-handlers assoc [priority id] (assoc handler :priority priority)))))

(defn remove-activate-handler!
  "Remove an activate handler by id."
  [id]
  (assert-keybind-registries-open!)
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
  (assert-keybind-registries-open!)
  (swap! key-groups assoc-in [group key-idx] delegate-map))

(defn clear-key-group!
  "Remove all delegates in a group."
  [group]
  (assert-keybind-registries-open!)
  (swap! key-groups dissoc group))

(defn clear-all-key-groups!
  "Remove all delegates from all groups."
  []
  (assert-keybind-registries-open!)
  (reset! key-groups {}))

(defn freeze-keybind-registries!
  []
  (reset! keybind-registries-frozen? true)
  nil)

(defn keybind-registries-snapshot
  []
  {:activate-handlers @activate-handlers
   :key-groups @key-groups
   :frozen? @keybind-registries-frozen?})

(defn reset-keybind-registries-for-test!
  []
  (reset! activate-handlers (sorted-map))
  (reset! key-groups {})
  (reset! keybind-registries-frozen? false)
  nil)

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
              s)))))))

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
(def ^:private default-key-state
  {:skill-keys [false false false false]
   :movement-keys {:forward false
                   :back false
                   :left false
                   :right false}
   :gui-keys {:skill-tree false
              :preset-editor false}})

(defonce ^:private key-states (atom {}))

(def ^:private default-preset-switch-state
  {:current-preset 0
   :show-until-ms  0})

(defonce ^:private preset-switch-state (atom {}))

(def ^:private PRESET-COUNT 4)
(def ^:private PRESET-INDICATOR-DURATION-MS 2000)

(defn- current-client-session-id
  []
  (or *client-session-id* runtime-hooks/*client-session-id*))

(defn- require-client-owner-value
  [owner label value]
  (if (some? value)
    value
    (throw (ex-info (format "Client keybind owner requires %s" label)
                    {:owner owner
                     :required label}))))

(defn client-owner-key
  [owner]
  (let [owner-map (cond
                    (vector? owner) owner
                    (map? owner) owner
                    (some? owner) {:player-uuid owner}
                    :else {})]
    (if (vector? owner-map)
      owner-map
      [(require-client-owner-value owner ":client-session-id"
                                   (or (:client-session-id owner-map)
                                       (:session-id owner-map)
                                       (current-client-session-id)))
       (require-client-owner-value owner ":player-uuid"
                                   (or (:player-uuid owner-map)
                                       (:uuid owner-map)))])))

(defn key-state-snapshot
  ([]
   @key-states)
  ([owner]
   (get @key-states (client-owner-key owner) default-key-state)))

(defn preset-switch-state-snapshot
  ([]
   @preset-switch-state)
  ([owner]
   (get @preset-switch-state (client-owner-key owner) default-preset-switch-state)))

(defn- swap-key-state!
  [owner f & args]
  (let [owner-key (client-owner-key owner)]
    (swap! key-states
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-key-state) args))))))

(defn- swap-preset-switch-state!
  [owner f & args]
  (let [owner-key (client-owner-key owner)]
    (swap! preset-switch-state
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-preset-switch-state) args))))))

(defn clear-client-keybind-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (swap! key-states dissoc owner-key)
    (swap! preset-switch-state dissoc owner-key))
  nil)

(defn reset-client-keybind-state-for-test!
  []
  (reset! key-states {})
  (reset! preset-switch-state {})
  nil)

(def ^:private movement-keys
  [:forward :back :left :right])

(defn- activated?
  [player-uuid]
  (boolean (get-in (ps/get-player-state player-uuid) [:resource-data :activated])))

(defn- can-use-ability?
  "Check if player can use abilities (not overloaded, not interfered)."
  [player-uuid]
  (when-let [state (ps/get-player-state player-uuid)]
    (resource-check/can-use-resource-data? (:resource-data state))))

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

(defn- current-client-owner
  [player-uuid]
  {:client-session-id (require-client-owner-value {:player-uuid player-uuid}
                                                  ":client-session-id"
                                                  (current-client-session-id))
   :player-uuid player-uuid})

(defn on-skill-key-event
  "Handle skill key state change. Uses delegate system with pre-checks."
  [key-idx is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner (current-client-owner player-uuid)
          was-down (get-in (key-state-snapshot owner) [:skill-keys key-idx])]
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
            (when-let [f (:on-key-up delegate)] (f player-uuid)))))

      ;; Update state
      (swap-key-state! owner assoc-in [:skill-keys key-idx] is-down))))


(defn on-gui-key-event
  "Handle GUI key state change. Opens screens or toggles mode on key press."
  [gui-type is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner (current-client-owner player-uuid)
          was-down (get-in (key-state-snapshot owner) [:gui-keys gui-type])]
      ;; GUI keys trigger on key press only.
      (when (and (not was-down) is-down)
        (case gui-type
          :skill-tree
          (client-bridge/open-screen! :ac/skill-tree {:player-uuid player-uuid})

          :preset-editor
          (client-bridge/open-screen! :ac/preset-editor {:player-uuid player-uuid})

          nil))

      ;; Update state
      (swap-key-state! owner assoc-in [:gui-keys gui-type] is-down))))

(defn on-movement-key-event
  "Handle movement key state transitions and forward them to runtime bridge."
  [movement-key is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner (current-client-owner player-uuid)
          was-down (get-in (key-state-snapshot owner) [:movement-keys movement-key])]
      (cond
        (and (not was-down) is-down)
        (runtime/on-movement-key-down! player-uuid movement-key)

        (and was-down is-down)
        (runtime/on-movement-key-tick! player-uuid movement-key)

        (and was-down (not is-down))
        (runtime/on-movement-key-up! player-uuid movement-key)

        :else nil)
      (swap-key-state! owner assoc-in [:movement-keys movement-key] is-down))))

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
    (on-skill-key-event idx (key-state-fn [:slot idx])))

  ;; Poll movement keys (W/A/S/D)
  (doseq [movement-key movement-keys]
    (on-movement-key-event movement-key (key-state-fn [:movement movement-key])))

  ;; Poll GUI keys
  (on-gui-key-event :skill-tree (key-state-fn [:screen :primary]))
  (on-gui-key-event :preset-editor (key-state-fn [:screen :secondary])))

(defn reset-all-keys!
  "Reset all key states. Called on disconnect or dimension change."
  ([]
   (reset-client-keybind-state-for-test!)
   (clear-all-key-groups!))
  ([owner]
   (clear-client-keybind-state! owner)))

;; ============================================================================
;; Preset Switch
;; ============================================================================

(defn switch-preset!
  "Cycle to next preset. Called by platform key handler."
  ([]
   (when-let [player-uuid (get-client-player-uuid)]
     (switch-preset! player-uuid)))
  ([player-uuid]
   (when (activated? player-uuid)
     (let [owner (current-client-owner player-uuid)
           current-state (preset-switch-state-snapshot owner)
           next-idx (mod (inc (:current-preset current-state)) PRESET-COUNT)]
       (swap-preset-switch-state! owner assoc
                                  :current-preset next-idx
                                  :show-until-ms (+ (System/currentTimeMillis) PRESET-INDICATOR-DURATION-MS))
       (api/req-switch-preset! next-idx nil)
       (update-default-group! player-uuid)
       (log/info "[PRESET-SWITCH]" {:preset next-idx})))))

(defn get-preset-switch-state
  "Get current preset switch state for HUD rendering."
  ([]
   (if-let [player-uuid (get-client-player-uuid)]
     (get-preset-switch-state player-uuid)
     default-preset-switch-state))
  ([owner]
   (preset-switch-state-snapshot owner)))

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
