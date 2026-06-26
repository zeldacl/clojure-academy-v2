(ns cn.li.forge1201.client.key-input
  "CLIENT-ONLY key binding registration and polling (Forge layer).
  Supports two key schemes:
    :original     — LMB, RMB, R, F  (requires control-override when active)
    :alternative  — Z, X, C, B      (no conflict with vanilla)"
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mc1201.client.input.mode-switch :as mode-switch]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mcmod.client.content-actions :as content-actions]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client KeyMapping Minecraft Options]
           [com.mojang.blaze3d.platform InputConstants$Type]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.client.event InputEvent$Key InputEvent$MouseScrollingEvent]
           [org.lwjgl.glfw GLFW]))

(defn default-key-input-runtime-state
  []
  {:slot-keys []
   :screen-keys {}
   :raw-v-state {}
   :key-scheme :alternative
   :override-active? {}})

(defn create-key-input-runtime
  ([]
   (create-key-input-runtime {}))
  ([initial-state]
   {:state (atom (merge (default-key-input-runtime-state)
                        initial-state))}))

(defonce ^:private installed-key-input-runtime
  (create-key-input-runtime))

(def ^:dynamic *key-input-runtime*
  installed-key-input-runtime)

(defn current-key-input-runtime
  []
  *key-input-runtime*)

(defmacro with-key-input-runtime
  [runtime & body]
  `(binding [*key-input-runtime* ~runtime]
     ~@body))

(defn call-with-key-input-runtime
  [runtime f]
  (binding [*key-input-runtime* runtime]
    (f)))

(defn key-input-runtime-state-atom
  []
  (:state (current-key-input-runtime)))

(defn key-input-runtime-state-snapshot
  []
  @(key-input-runtime-state-atom))

(defn update-key-input-runtime!
  [f & args]
  (apply swap! (key-input-runtime-state-atom) f args))

(def ^:private listener-guard-lock
  (Object.))

(def ^:private ^:dynamic *raw-v-listener-registered?*
  false)

(def ^:private ^:dynamic *mouse-scroll-listener-registered?*
  false)
(def ^:private toggle-primary-state-input-id :content/toggle-primary-state)
(def ^:private cycle-selection-input-id :content/cycle-selection)

(declare get-player-uuid
         update-owner-mode-switch-state!)

(defn- current-screen-open? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.screen mc))))

(defn- emit-keyboard-input!
  [input-id player-uuid event]
  (power-runtime/emit-client-input! input-id
                                    {:player-uuid player-uuid}
                                    {:source :keyboard
                                     :event event}))

(defn- on-raw-key-input! [^InputEvent$Key event]
  (let [key (.getKey event)
        action (.getAction event)
        now (System/nanoTime)]
    ;; Diagnostic: log Enter key events to help trace why chat won't open
    (when (and (= key GLFW/GLFW_KEY_ENTER)
               (= action GLFW/GLFW_PRESS))
      (let [screen-open? (current-screen-open?)
            ^Minecraft mc (Minecraft/getInstance)
            screen-class (when screen-open? (some-> (.screen mc) .getClass .getName))]
        (log/info "[KEY-TRACE] Enter pressed"
                  {:screen-open? screen-open?
                   :event-canceled? (.isCanceled event)
                   :screen-class screen-class
                   :tick (System/currentTimeMillis)})))
    (when (= key GLFW/GLFW_KEY_V)
      (when-let [owner (client-session/current-local-player-owner)]
        (update-owner-mode-switch-state!
         owner
         (= action GLFW/GLFW_PRESS)
         {:now-ns now
          :screen-open? (current-screen-open?)
          :on-down #(overlay-renderer/on-mode-switch-key-state! owner true)
          :on-up #(overlay-renderer/on-mode-switch-key-state! owner false)
          :on-short-up
          (fn []
            ;; Bind client-session-id so the AC layer's handler chain
            ;; (trigger-mode-switch! → has-category? → get-client-player-state)
            ;; can resolve the session-id dynamic var. Mirrors tick-input! and
            ;; on-mouse-scroll! which both wrap in with-current-client-session.
            (client-session/with-current-client-session
              (fn []
                (let [uuid (:player-uuid owner)]
                  ;; Emit the keyboard event. The AC layer's activate handler stack
                  ;; (trigger-mode-switch!) will determine whether to toggle or abort,
                  ;; and will update overlay state via the set-client-overlay-activated! hook.
                  (emit-keyboard-input! toggle-primary-state-input-id uuid :short-press)))))})))))

(defn- create-key-mapping [^String translation-key key-code ^String category]
  (KeyMapping. translation-key InputConstants$Type/KEYSYM (int key-code) category))

(defn set-key-scheme!
  "Set the key scheme. :original (LMB/RMB/R/F) or :alternative (Z/X/C/B)."
  [scheme]
  (update-key-input-runtime! assoc :key-scheme scheme)
  (log/info "Key scheme set" {:scheme scheme}))

(defn get-key-scheme [] (:key-scheme (key-input-runtime-state-snapshot)))

(defn- create-original-slot-keys [category]
  ;; Original scheme uses raw GLFW polling for mouse/keyboard,
  ;; not KeyMappings, because LMB/RMB conflict with vanilla.
  ;; We still create KeyMappings for R and F but use raw polling for mouse.
  [(create-key-mapping "key.content.slot.0" GLFW/GLFW_KEY_UNKNOWN category)  ;; LMB - polled via GLFW
   (create-key-mapping "key.content.slot.1" GLFW/GLFW_KEY_UNKNOWN category)  ;; RMB - polled via GLFW
   (create-key-mapping "key.content.slot.2" GLFW/GLFW_KEY_R category)
   (create-key-mapping "key.content.slot.3" GLFW/GLFW_KEY_F category)])

(defn- create-alternative-slot-keys [category]
  [(create-key-mapping "key.content.slot.0" GLFW/GLFW_KEY_Z category)
   (create-key-mapping "key.content.slot.1" GLFW/GLFW_KEY_X category)
   (create-key-mapping "key.content.slot.2" GLFW/GLFW_KEY_C category)
   (create-key-mapping "key.content.slot.3" GLFW/GLFW_KEY_B category)])

(defn register-keybinds! []
  (let [category "key.categories.content.actions"
        scheme (get-key-scheme)
        slot-key-mappings (if (= scheme :original)
                            (create-original-slot-keys category)
                            (create-alternative-slot-keys category))
        screen-key-mappings (merge
                              {:primary (create-key-mapping "key.content.open_primary_screen" GLFW/GLFW_KEY_GRAVE_ACCENT category)
                               :secondary (create-key-mapping "key.content.open_secondary_screen" GLFW/GLFW_KEY_G category)
                               :mode-toggle (create-key-mapping "key.content.mode_toggle" GLFW/GLFW_KEY_V category)
                               ;; Terminal open/close key (matching original @RegACKeyHandler "open_data_terminal" KEY_LMENU)
                               :terminal (create-key-mapping "settings.my_mod.prop.open_data_terminal" GLFW/GLFW_KEY_LEFT_ALT category)
                               ;; Debug overlay toggle (matching original DebugConsole F4)
                               :debug-overlay (create-key-mapping "key.content.debug_overlay" GLFW/GLFW_KEY_F4 category)}
                              ;; Cycle key: C if original scheme (slot keys don't use C), N if alternative
                              (if (= scheme :original)
                                {:cycle-selection (create-key-mapping "key.content.cycle_selection" GLFW/GLFW_KEY_C category)}
                                {:cycle-selection (create-key-mapping "key.content.cycle_selection" GLFW/GLFW_KEY_N category)}))]
    (update-key-input-runtime! assoc
                               :slot-keys slot-key-mappings
                               :screen-keys screen-key-mappings)
    (log/info "Client key bindings created" {:scheme scheme :slot-count (count slot-key-mappings)})))

(defn get-slot-keys [] (:slot-keys (key-input-runtime-state-snapshot)))
(defn get-screen-keys [] (vals (:screen-keys (key-input-runtime-state-snapshot))))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn- client-owner-key
  [owner]
  (client-session/owner-key owner))

(defn- owner-mode-switch-state
  [owner]
  (get-in (key-input-runtime-state-snapshot) [:raw-v-state (client-owner-key owner)] (mode-switch/initial-state)))

(defn- owner-override-active?
  [owner]
  (boolean (get-in (key-input-runtime-state-snapshot) [:override-active? (client-owner-key owner)] false)))

(defn input-state-snapshot
  []
  (let [{:keys [raw-v-state override-active?]} (key-input-runtime-state-snapshot)]
    {:raw-v-state raw-v-state
     :override-active? override-active?}))

(defn reset-input-state-for-test!
  ([]
   (reset-input-state-for-test! {}))
  ([{:keys [raw-v-state-map override-active-map key-scheme-value slot-keys-list screen-keys-map]
     :or {raw-v-state-map {}
          override-active-map {}
          key-scheme-value :alternative
          slot-keys-list []
          screen-keys-map {}}}]
   (update-key-input-runtime! merge
                              {:raw-v-state raw-v-state-map
                               :override-active? override-active-map
                               :key-scheme key-scheme-value
                               :slot-keys slot-keys-list
                               :screen-keys screen-keys-map})
   nil))

(defn clear-owner-input-state!
  [owner]
  (let [owner-key (client-owner-key owner)]
    (update-key-input-runtime! update :raw-v-state dissoc owner-key)
    (update-key-input-runtime! update :override-active? dissoc owner-key))
  nil)

(defn clear-client-input-session!
  [client-session-id]
  (let [clear-session (fn [states]
                        (into {}
                              (remove (fn [[[entry-session-id _player-uuid] _value]]
                                        (= client-session-id entry-session-id)))
                              states))]
    (update-key-input-runtime! update :raw-v-state clear-session)
    (update-key-input-runtime! update :override-active? clear-session))
  nil)

(defn- update-owner-mode-switch-state!
  [owner is-down opts]
  (let [state-atom (atom (owner-mode-switch-state owner))]
    (mode-switch/handle-button-state! state-atom is-down opts)
    (update-key-input-runtime! update :raw-v-state assoc (client-owner-key owner) @state-atom)
    nil))

(defn- suppress-vanilla-keys!
  "When using :original key scheme and runtime mode active, consume vanilla attack/use clicks."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [^Options opts (.options mc)]
      ;; Consume all pending clicks from keyAttack and keyUse
      (while (.consumeClick (.keyAttack opts)))
      (while (.consumeClick (.keyUse opts)))
      ;; Force them to not-down state
      (.setDown (.keyAttack opts) false)
      (.setDown (.keyUse opts) false))))

(defn- update-control-override!
  "Enable/disable vanilla key suppression based on activation state."
  [owner activated?]
  (let [need-override? (and (= (get-key-scheme) :original) activated?)
        owner-key (client-owner-key owner)]
    (when (not= (get-in (key-input-runtime-state-snapshot) [:override-active? owner-key] false) need-override?)
      (update-key-input-runtime! update :override-active? assoc owner-key need-override?)
      (log/info "Control override" {:active need-override?}))))

(defn- original-scheme-slot-down?
  "Poll raw GLFW state for original key scheme (LMB/RMB/R/F)."
  [key-idx]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (case (int key-idx)
        0 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_LEFT))
        1 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_RIGHT))
        2 (when-let [^KeyMapping key (nth (get-slot-keys) 2 nil)] (.isDown key))
        3 (when-let [^KeyMapping key (nth (get-slot-keys) 3 nil)] (.isDown key))
        false))))

(defn- slot-key-down?
  [scheme key-idx]
  (if (= scheme :original)
    (boolean (original-scheme-slot-down? key-idx))
    (when-let [^KeyMapping key (nth (get-slot-keys) key-idx nil)]
      (.isDown key))))

(defn- movement-key-down?
  [movement-key]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [^Options opts (.options mc)]
      (case movement-key
        :forward (.isDown (.keyUp opts))
        :back (.isDown (.keyDown opts))
        :left (.isDown (.keyLeft opts))
        :right (.isDown (.keyRight opts))
        false))))

(defn- on-mouse-scroll! [^InputEvent$MouseScrollingEvent event]
  (client-session/with-current-client-session
    (fn []
      (let [delta (double (.getScrollDelta event))
            scheme (get-key-scheme)
            owner (client-session/current-local-player-owner)
            activated? (boolean (and owner (overlay-state/get-client-activated owner)))]
        (when (and activated?
                   (not (current-screen-open?))
                   (not (zero? delta)))
          (when-let [uuid (get-player-uuid)]
            (doseq [idx (range (count (get-slot-keys)))]
              (when (slot-key-down? scheme idx)
                (power-runtime/client-on-slot-wheel! uuid idx delta)))))))))

(defn tick-input! []
  (client-session/with-current-client-session
    (fn []
      ;; Handle terminal toggle key (matching original @RegACKeyHandler "open_data_terminal" KEY_LMENU).
      ;; Uses the registered :terminal KeyMapping -- player can rebind in Controls settings.
      (when-let [^KeyMapping terminal-key (get-in (key-input-runtime-state-snapshot) [:screen-keys :terminal])]
        (when (.consumeClick terminal-key)
          (when-not (current-screen-open?)
            (when-let [^Minecraft mc (Minecraft/getInstance)]
              (when-let [player (.player mc)]
                (content-actions/toggle-terminal! player))))))
      ;; Handle F4 debug overlay toggle
      (when-let [^KeyMapping debug-key (get-in (key-input-runtime-state-snapshot) [:screen-keys :debug-overlay])]
        (when (.consumeClick debug-key)
          (power-runtime/toggle-debug-overlay-state!)))
      (if-let [owner (client-session/current-local-player-owner)]
        (let [scheme (get-key-scheme)
              activated? (boolean (overlay-state/get-client-activated owner))]
          ;; Update control override state
          (update-control-override! owner activated?)
          ;; Suppress vanilla keys when override is active
          (when (owner-override-active? owner)
            (suppress-vanilla-keys!))
          ;; Handle cycle-selection key
          (when-let [^KeyMapping cycle-key (get-in (key-input-runtime-state-snapshot) [:screen-keys :cycle-selection])]
            (when (.consumeClick cycle-key)
              (when-let [uuid (get-player-uuid)]
                (emit-keyboard-input! cycle-selection-input-id uuid :press))))
          ;; Tick content slot keys
          (power-runtime/client-tick-keys!
            (fn [key-id]
              (case (first key-id)
                :slot (let [idx (second key-id)]
                        (slot-key-down? scheme idx))
                :movement (movement-key-down? (second key-id))
                :screen (when-let [^KeyMapping key (get-in (key-input-runtime-state-snapshot) [:screen-keys (second key-id)])] (.isDown key))
                false))
            get-player-uuid))
        (when-let [session-id (client-session/client-session-id)]
          (clear-client-input-session! session-id))))))

(defn init! []
  ;; NOTE: register-keybinds! must NOT be called here.  It is already invoked from
  ;; register-key-mappings! on the RegisterKeyMappingsEvent, which fires before
  ;; FMLClientSetupEvent.  Calling it again creates duplicate KeyMapping instances
  ;; in the global KeyMapping.ALL list, which can interfere with vanilla key
  ;; handling (e.g. preventing Enter from opening the chat).
  ;; Register the overlay activation hook: called by AC layer after activate handler
  ;; stack resolves to provide immediate client-side HUD feedback.
  (power-runtime/register-power-runtime-hooks!
   {:set-client-overlay-activated!
    (fn [player-uuid activated]
      (when-let [owner (client-session/current-local-player-owner)]
        (overlay-state/set-client-activated! owner activated)))})
  (when-not (var-get #'*raw-v-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*raw-v-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false InputEvent$Key
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-raw-key-input! evt))))
        (alter-var-root #'*raw-v-listener-registered?* (constantly true)))))
  (when-not (var-get #'*mouse-scroll-listener-registered?*)
    (locking listener-guard-lock
      (when-not (var-get #'*mouse-scroll-listener-registered?*)
        (.addListener (MinecraftForge/EVENT_BUS)
                      EventPriority/NORMAL false InputEvent$MouseScrollingEvent
                      (reify java.util.function.Consumer
                        (accept [_ evt] (on-mouse-scroll! evt))))
        (alter-var-root #'*mouse-scroll-listener-registered?* (constantly true)))))
  (log/info "Client key input initialized"))
