(ns cn.li.forge1201.client.key-input
  "CLIENT-ONLY key binding registration and polling (Forge layer).
  Supports two key schemes:
    :original     — LMB, RMB, R, F  (requires control-override when active)
    :alternative  — Z, X, C, B      (no conflict with vanilla)"
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mc1201.client.input.mode-switch :as mode-switch]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client KeyMapping Minecraft Options]
           [com.mojang.blaze3d.platform InputConstants$Type]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.client.event InputEvent$Key InputEvent$MouseScrollingEvent]
           [org.lwjgl.glfw GLFW]))

(defonce ^:private slot-keys (atom []))
(defonce ^:private screen-keys (atom {}))
(defonce ^:private mode-switch-state (atom {:was-down false :down-at-ns nil :pending-clicks 0}))
(defonce ^:private raw-v-state (atom (mode-switch/initial-state)))
(defonce ^:private raw-v-listener-registered? (atom false))
(defonce ^:private mouse-scroll-listener-registered? (atom false))
(def ^:private toggle-primary-state-input-id :content/toggle-primary-state)
(def ^:private cycle-selection-input-id :content/cycle-selection)

;; Key scheme: :original (LMB/RMB/R/F) or :alternative (Z/X/C/B)
(defonce ^:private key-scheme (atom :alternative))

;; Track whether vanilla keys are currently overridden
(defonce ^:private override-active? (atom false))

(declare get-player-uuid)

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
    (when (= key GLFW/GLFW_KEY_V)
      (mode-switch/handle-button-state!
       raw-v-state
       (= action GLFW/GLFW_PRESS)
       {:now-ns now
        :screen-open? (current-screen-open?)
        :on-down #(overlay-renderer/on-mode-switch-key-state! true)
        :on-up #(overlay-renderer/on-mode-switch-key-state! false)
        :on-short-up
        (fn []
          (when-let [uuid (get-player-uuid)]
            (let [cur-activated (power-runtime/runtime-activated? uuid)]
              (overlay-state/set-client-activated! (not cur-activated))
              (emit-keyboard-input! toggle-primary-state-input-id uuid :short-press))))}))))

(defn- consume-click-count [^KeyMapping key]
  (loop [n 0]
    (if (.consumeClick key)
      (recur (inc n))
      n)))

(defn- create-key-mapping [^String translation-key key-code ^String category]
  (KeyMapping. translation-key InputConstants$Type/KEYSYM (int key-code) category))

(defn set-key-scheme!
  "Set the key scheme. :original (LMB/RMB/R/F) or :alternative (Z/X/C/B)."
  [scheme]
  (reset! key-scheme scheme)
  (log/info "Key scheme set" {:scheme scheme}))

(defn get-key-scheme [] @key-scheme)

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
        scheme @key-scheme]
    (reset! slot-keys
            (if (= scheme :original)
              (create-original-slot-keys category)
              (create-alternative-slot-keys category)))
    (reset! screen-keys
            (merge
             {:primary (create-key-mapping "key.content.open_primary_screen" GLFW/GLFW_KEY_GRAVE_ACCENT category)
              :secondary (create-key-mapping "key.content.open_secondary_screen" GLFW/GLFW_KEY_G category)
              :mode-toggle (create-key-mapping "key.content.mode_toggle" GLFW/GLFW_KEY_V category)}
             ;; Cycle key: C if original scheme (slot keys don't use C), N if alternative
             (if (= scheme :original)
               {:cycle-selection (create-key-mapping "key.content.cycle_selection" GLFW/GLFW_KEY_C category)}
               {:cycle-selection (create-key-mapping "key.content.cycle_selection" GLFW/GLFW_KEY_N category)})))
    (log/info "Client key bindings created" {:scheme scheme :slot-count (count @slot-keys)})))

(defn get-slot-keys [] @slot-keys)
(defn get-screen-keys [] (vals @screen-keys))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn- client-session-id []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    [:client (System/identityHashCode mc)]))

(defn- with-client-session [f]
  (binding [power-runtime/*client-session-id* (client-session-id)]
    (f)))

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
  [activated?]
  (let [need-override? (and (= @key-scheme :original) activated?)]
    (when (not= @override-active? need-override?)
      (reset! override-active? need-override?)
      (log/info "Control override" {:active need-override?}))))

(defn- original-scheme-slot-down?
  "Poll raw GLFW state for original key scheme (LMB/RMB/R/F)."
  [key-idx]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (case (int key-idx)
        0 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_LEFT))
        1 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_RIGHT))
        2 (when-let [^KeyMapping key (nth @slot-keys 2 nil)] (.isDown key))
        3 (when-let [^KeyMapping key (nth @slot-keys 3 nil)] (.isDown key))
        false))))

(defn- slot-key-down?
  [scheme key-idx]
  (if (= scheme :original)
    (boolean (original-scheme-slot-down? key-idx))
    (when-let [^KeyMapping key (nth @slot-keys key-idx nil)]
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
  (with-client-session
    (fn []
      (let [delta (double (.getScrollDelta event))
            scheme @key-scheme
            activated? (boolean @overlay-state/client-activated-overlay)]
        (when (and activated?
                   (not (current-screen-open?))
                   (not (zero? delta)))
          (when-let [uuid (get-player-uuid)]
            (doseq [idx (range (count @slot-keys))]
              (when (slot-key-down? scheme idx)
                (power-runtime/client-on-slot-wheel! uuid idx delta)))))))))

(defn tick-input! []
  (with-client-session
    (fn []
      (let [scheme @key-scheme
            activated? (boolean @overlay-state/client-activated-overlay)]
        ;; Update control override state
        (update-control-override! activated?)
        ;; Suppress vanilla keys when override is active
        (when @override-active?
          (suppress-vanilla-keys!))
        ;; Handle cycle-selection key
        (when-let [^KeyMapping cycle-key (get @screen-keys :cycle-selection)]
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
              :screen (when-let [^KeyMapping key (get @screen-keys (second key-id))] (.isDown key))
              false))
          get-player-uuid)))))

(defn init! []
  (register-keybinds!)
  (when (compare-and-set! raw-v-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false InputEvent$Key
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-raw-key-input! evt)))))
  (when (compare-and-set! mouse-scroll-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false InputEvent$MouseScrollingEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-mouse-scroll! evt)))))
  (log/info "Client key input initialized"))