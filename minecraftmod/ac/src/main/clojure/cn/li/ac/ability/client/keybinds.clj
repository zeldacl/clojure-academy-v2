(ns cn.li.ac.ability.client.keybinds
  "Key binding state tracking and event handling (AC layer - no Minecraft imports).

  Provides:
  - Activate handler registry (V key stack)
  - Key group system with delegates
  - Pre-check logic (cooldown, resource) before dispatching"
  (:require 
            [cn.li.ac.ability.client.read-model :as read-model]
[cn.li.ac.ability.client.runtime :as runtime]
            [cn.li.ac.ability.client.api :as api]
            [cn.li.ac.ability.client.input-sampling :as sampling]
            [cn.li.ac.ability.client.input-state-machine :as sm]
            [cn.li.ac.ability.client.input-command-builder :as cmd-builder]
            [cn.li.ac.ability.client.input-processor :as processor]
            [cn.li.ac.ability.model.preset :as preset-data]
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

(defn default-keybind-registry-runtime-state
  []
  {:activate-handlers (sorted-map)
   :key-groups {}
   :frozen? false})

(defn create-keybind-registry-runtime
  ([]
   (create-keybind-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-keybind-registry-runtime-state))}}]
   {::runtime ::keybind-registry-runtime
    :state* state*}))

(defonce ^:private installed-keybind-registry-runtime
  (create-keybind-registry-runtime))

(def ^:dynamic *keybind-registry-runtime*
  installed-keybind-registry-runtime)

(defn- keybind-registry-runtime?
  [runtime]
  (and (map? runtime)
       (= ::keybind-registry-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-keybind-registry-runtime
  [runtime f]
  (when-not (keybind-registry-runtime? runtime)
    (throw (ex-info "Expected keybind registry runtime"
                    {:runtime runtime})))
  (binding [*keybind-registry-runtime* runtime]
    (f)))

(defmacro with-keybind-registry-runtime
  [runtime & body]
  `(call-with-keybind-registry-runtime ~runtime (fn [] ~@body)))

(defn- current-keybind-registry-runtime
  []
  *keybind-registry-runtime*)

(defn- keybind-registry-state-atom
  []
  (:state* (current-keybind-registry-runtime)))

(defn- keybind-registry-state-snapshot
  []
  @(keybind-registry-state-atom))

(defn- update-keybind-registry-state!
  [f & args]
  (apply swap! (keybind-registry-state-atom) f args))

(defn- assert-keybind-registries-open!
  []
  (when (:frozen? (keybind-registry-state-snapshot))
    (throw (ex-info "Keybind registries are frozen" {}))))

(defn- find-activate-handler-entry
  [id]
  (first (filter (fn [[[_ handler-id] _handler]] (= id handler-id))
                 (:activate-handlers (keybind-registry-state-snapshot)))))

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
      (update-keybind-registry-state! assoc-in [:activate-handlers [priority id]]
                                    (assoc handler :priority priority)))))

(defn remove-activate-handler!
  "Remove an activate handler by id."
  [id]
  (assert-keybind-registries-open!)
  (update-keybind-registry-state!
    update :activate-handlers
    (fn [m]
      (into (sorted-map)
            (remove (fn [[[_ hid] _]] (= hid id)) m)))))

(defn get-active-handler
  "Walk handlers from highest priority down. Return first where handles-fn returns true."
  [player-uuid]
  (let [handlers (reverse (vals (:activate-handlers (keybind-registry-state-snapshot))))]
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

(defn register-key-delegate!
  "Register a delegate for a key index in a named group."
  [group key-idx delegate-map]
  (assert-keybind-registries-open!)
  (update-keybind-registry-state! assoc-in [:key-groups group key-idx] delegate-map))

(defn clear-key-group!
  "Remove all delegates in a group."
  [group]
  (assert-keybind-registries-open!)
  (update-keybind-registry-state! update :key-groups dissoc group))

(defn clear-all-key-groups!
  "Remove all delegates from all groups."
  []
  (assert-keybind-registries-open!)
  (update-keybind-registry-state! assoc :key-groups {}))

(defn freeze-keybind-registries!
  []
  (update-keybind-registry-state! assoc :frozen? true)
  nil)

(defn keybind-registries-snapshot
  []
  (keybind-registry-state-snapshot))

(defn reset-keybind-registries-for-test!
  []
  (reset! (keybind-registry-state-atom) (default-keybind-registry-runtime-state))
  nil)

(declare get-client-player-state)

(defn get-delegate-for-key
  "Get the effective delegate for a key index. Custom groups override :default."
  [key-idx]
  ;; Walk non-default groups first (any custom override), then default
  (let [groups (:key-groups (keybind-registry-state-snapshot))
        custom-groups (dissoc groups :default)]
    (or (some #(get % key-idx) (vals custom-groups))
        (get-in groups [:default key-idx]))))

(defn- get-skill-id-for-slot
  "Get the skill-id bound to a slot from the current preset."
  [player-uuid key-idx]
  (when-let [state (get-client-player-state player-uuid)]
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
        :on-key-abort (fn [uuid] (runtime/on-slot-key-abort! uuid idx))}))))

;; State tracking for key transitions
(def ^:private default-key-state
  {:skill-keys [false false false false]
   :movement-keys {:forward false
                   :back false
                   :left false
                   :right false}
   :gui-keys {:skill-tree false
              :preset-editor false}})

(defn create-client-keybind-runtime
  []
  {::runtime ::client-keybind-runtime
   :key-states* (atom {})
   :preset-switch-states* (atom {})})

(defonce ^:private installed-client-keybind-runtime
  (create-client-keybind-runtime))

(def ^:dynamic *client-keybind-runtime*
  installed-client-keybind-runtime)

(defn- client-keybind-runtime?
  [runtime]
  (and (map? runtime)
       (= ::client-keybind-runtime (::runtime runtime))
       (some? (:key-states* runtime))
       (some? (:preset-switch-states* runtime))))

(defn call-with-client-keybind-runtime
  [runtime f]
  (when-not (client-keybind-runtime? runtime)
    (throw (ex-info "Expected client keybind runtime"
                    {:runtime runtime})))
  (binding [*client-keybind-runtime* runtime]
    (f)))

(defmacro with-client-keybind-runtime
  [runtime & body]
  `(call-with-client-keybind-runtime ~runtime (fn [] ~@body)))

(defn- current-client-keybind-runtime
  []
  *client-keybind-runtime*)

(defn- key-states-atom
  []
  (:key-states* (current-client-keybind-runtime)))

(def ^:private default-preset-switch-state
  {:current-preset 0
   :previous-preset 0
   :show-until-ms  0})

(defn- preset-switch-states-atom
  []
  (:preset-switch-states* (current-client-keybind-runtime)))

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
                                       (current-client-session-id)))
       (require-client-owner-value owner ":player-uuid"
                                   (some-> (or (:player-uuid owner-map)
                                               (:uuid owner-map))
                           str))])))

(defn- get-client-player-state
  [player-uuid]
  (let [session-id (require-client-owner-value {:player-uuid player-uuid}
                                               ":client-session-id"
                                               (current-client-session-id))
        player-uuid* (require-client-owner-value {:player-uuid player-uuid}
                                                 ":player-uuid"
                                                 (some-> player-uuid str))]
    (read-model/get-player-state [session-id :keybinds player-uuid*])))

(defn key-state-snapshot
  ([]
  @(key-states-atom))
  ([owner]
  (get @(key-states-atom) (client-owner-key owner) default-key-state)))

(defn preset-switch-state-snapshot
  ([]
  @(preset-switch-states-atom))
  ([owner]
  (get @(preset-switch-states-atom) (client-owner-key owner) default-preset-switch-state)))

(defn- swap-key-state!
  [owner f & args]
  (let [owner-key (client-owner-key owner)]
		(swap! (key-states-atom)
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-key-state) args))))))

(defn- swap-preset-switch-state!
  [owner f & args]
  (let [owner-key (client-owner-key owner)]
		(swap! (preset-switch-states-atom)
           (fn [states]
             (assoc states owner-key
                    (apply f (get states owner-key default-preset-switch-state) args))))))

(defn clear-client-keybind-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (swap! (key-states-atom) dissoc owner-key)
    (swap! (preset-switch-states-atom) dissoc owner-key))
  nil)

(defn reset-client-keybind-state-for-test!
  []
  (reset! (key-states-atom) {})
  (reset! (preset-switch-states-atom) {})
  nil)

(def ^:private movement-keys
  [:forward :back :left :right])

(defn- activated?
  [player-uuid]
  (boolean (get-in (get-client-player-state player-uuid) [:resource-data :activated])))

(defn- has-category?
  "Check if player has learned a category (original AcademyCraft: aData.hasCategory())."
  [player-uuid]
  (some? (get-in (get-client-player-state player-uuid) [:ability-data :category-id])))

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

(defn- client-context-query-owner
  [player-uuid]
  (when-let [session-id (current-client-session-id)]
    {:logical-side :client
     :client-session-id session-id
     :player-uuid (str player-uuid)}))

(defn- player-contexts
  [player-uuid]
  (read-model/get-player-contexts-for-player (str player-uuid)
                                             (current-client-session-id)
                                             :keybinds))

(defn- has-active-contexts?
  "Check if the player has any non-terminated contexts.
  Used by activate handler stack: if true, V-key short-press aborts
  delegates instead of toggling activation."
  [player-uuid]
  (seq (filter #(not= (:status %) :terminated)
               (player-contexts player-uuid))))

(defn on-skill-key-event
  "Handle skill key state change. Uses delegate system with pre-checks."
  [key-idx is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner        (current-client-owner player-uuid)
          key-state    (key-state-snapshot owner)
          player-state (get-client-player-state player-uuid)
          delegate     (get-delegate-for-key key-idx)
          event        (sm/compute-skill-key-event key-state player-state key-idx is-down delegate)]
      (processor/execute-skill-key-event! event player-uuid)
      (swap-key-state! owner sm/next-skill-key-state key-idx is-down))))


(defn on-gui-key-event
  "Handle GUI key state change. Opens screens or toggles mode on key press."
  [gui-type is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner     (current-client-owner player-uuid)
          key-state (key-state-snapshot owner)
          event     (sm/compute-gui-key-event key-state gui-type is-down)]
      (processor/execute-gui-key-event! event player-uuid)
      (swap-key-state! owner sm/next-gui-key-state gui-type is-down))))

(defn on-movement-key-event
  "Handle movement key state transitions and forward them to runtime bridge."
  [movement-key is-down]
  (when-let [player-uuid (get-client-player-uuid)]
    (let [owner     (current-client-owner player-uuid)
          key-state (key-state-snapshot owner)
          event     (sm/compute-movement-key-event key-state movement-key is-down)]
      (processor/execute-movement-key-event! event player-uuid)
      (swap-key-state! owner sm/next-movement-key-state movement-key is-down))))

(defn trigger-mode-switch!
  "V key short-press handler. Delegates to the active activate handler.
  If no handler matches, falls through to default toggle.

  Mirrors original AcademyCraft ClientHandler + ClientRuntime.getActivateHandler():
   1. hasCategory? guard — only toggle if player has learned a category
   2. If has active delegates → abort them (keep activation state unchanged)
   3. Otherwise → toggle activation state"
  ([]
   (trigger-mode-switch! nil))
  ([player-uuid]
   (when-let [player-uuid (or player-uuid (get-client-player-uuid))]
     ;; hasCategory check matching original: aData.hasCategory()
     (when (has-category? player-uuid)
       ;; Determine whether abort handler will match BEFORE running the stack.
       ;; When has-active-contexts? is true, the priority-10 abort-delegates
       ;; handler will fire → aborts contexts WITHOUT toggling activation.
       ;; When false, the priority-0 default-toggle handler fires → toggles.
       (let [will-abort? (boolean (has-active-contexts? player-uuid))]
         (if-let [handler (get-active-handler player-uuid)]
           (do
             (log/info "[V-TRACE][AC][CLIENT][HANDLER]"
                       {:handler-id (:id handler) :uuid (str player-uuid)})
             ((:on-key-down-fn handler) player-uuid)
             ;; After handler runs, update client overlay state for
             ;; immediate HUD feedback (no server round-trip delay).
             ;; - Abort: activation unchanged → leave overlay state alone
             ;; - Toggle: activation toggled → update overlay immediately
             (when-not will-abort?
               (let [state (get-client-player-state player-uuid)
                     current (boolean (get-in state [:resource-data :activated]))]
                 (runtime-hooks/set-client-overlay-activated! player-uuid (not current)))))
           ;; Fallback: no handler matched → build and execute toggle command
           (let [state   (get-client-player-state player-uuid)
                 current (boolean (get-in state [:resource-data :activated]))]
             (log/info "[V-TRACE][AC][CLIENT][TOGGLE]"
                       {:uuid (str player-uuid) :current current :next (not current)})
             (processor/execute-input-command! (current-client-owner player-uuid)
                                               (cmd-builder/toggle-activated-command current))
             (runtime-hooks/set-client-overlay-activated! player-uuid (not current)))))))))

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
     (let [owner         (current-client-owner player-uuid)
           current-state (preset-switch-state-snapshot owner)
           switch-cmd    (cmd-builder/preset-switch-command
                           (:current-preset current-state) PRESET-COUNT)]
       (swap-preset-switch-state! owner assoc
                                  :previous-preset (:current-preset current-state)
                                  :current-preset (:preset-idx switch-cmd)
                                  :show-until-ms (+ (client-bridge/game-time-ms)
                                                    PRESET-INDICATOR-DURATION-MS))
       (processor/execute-input-command! owner switch-cmd)
       (update-default-group! player-uuid)
       (log/info "[PRESET-SWITCH]" {:preset (:preset-idx switch-cmd)})))))

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

(defn install-default-handlers!
  "Register default activate handlers. Call once at init."
  []
  ;; Priority 0: Default toggle (lowest priority)
  (add-activate-handler!
   {:id              :default-toggle
    :priority        0
    :handles-fn      (fn [_uuid] true)
    :on-key-down-fn  (fn [uuid]
               (let [state   (get-client-player-state uuid)
                             current (boolean (get-in state [:resource-data :activated]))]
                         (api/req-set-activated! (current-client-owner uuid) (not current) nil)))
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
                       (runtime-hooks/client-abort-all!)
                       (log/info "[V-TRACE] Aborted all contexts for" uuid))
    :hint-fn         (fn [_uuid] "ac.activate.hint.abort")}))

