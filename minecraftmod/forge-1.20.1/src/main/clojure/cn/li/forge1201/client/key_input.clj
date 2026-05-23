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

(defonce ^:private skill-keys (atom []))
(defonce ^:private gui-keys (atom {}))
(defonce ^:private mode-switch-state (atom {:was-down false :down-at-ns nil :pending-clicks 0}))
(defonce ^:private raw-v-state (atom (mode-switch/initial-state)))
(defonce ^:private raw-v-listener-registered? (atom false))
(defonce ^:private mouse-scroll-listener-registered? (atom false))

;; Key scheme: :original (LMB/RMB/R/F) or :alternative (Z/X/C/B)
(defonce ^:private key-scheme (atom :alternative))

;; Track whether vanilla keys are currently overridden
(defonce ^:private override-active? (atom false))

(declare get-player-uuid)

(defn- current-screen-open? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.screen mc))))

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
            (let [cur-activated (boolean (get-in (power-runtime/get-player-state uuid) [:resource-data :activated]))]
              (overlay-state/set-client-activated! (not cur-activated))
              (power-runtime/client-trigger-mode-switch! uuid))))}))))

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

(defn- create-original-skill-keys [category]
  ;; Original scheme uses raw GLFW polling for mouse/keyboard,
  ;; not KeyMappings, because LMB/RMB conflict with vanilla.
  ;; We still create KeyMappings for R and F but use raw polling for mouse.
  [(create-key-mapping "key.ac.skill.0" GLFW/GLFW_KEY_UNKNOWN category)  ;; LMB - polled via GLFW
   (create-key-mapping "key.ac.skill.1" GLFW/GLFW_KEY_UNKNOWN category)  ;; RMB - polled via GLFW
   (create-key-mapping "key.ac.skill.2" GLFW/GLFW_KEY_R category)
   (create-key-mapping "key.ac.skill.3" GLFW/GLFW_KEY_F category)])

(defn- create-alternative-skill-keys [category]
  [(create-key-mapping "key.ac.skill.0" GLFW/GLFW_KEY_Z category)
   (create-key-mapping "key.ac.skill.1" GLFW/GLFW_KEY_X category)
   (create-key-mapping "key.ac.skill.2" GLFW/GLFW_KEY_C category)
   (create-key-mapping "key.ac.skill.3" GLFW/GLFW_KEY_B category)])

(defn register-keybinds! []
  (let [category "key.categories.ac.ability"
        scheme @key-scheme]
    (reset! skill-keys
            (if (= scheme :original)
              (create-original-skill-keys category)
              (create-alternative-skill-keys category)))
    (reset! gui-keys
            (merge
             {:skill-tree (create-key-mapping "key.ac.open_skill_tree" GLFW/GLFW_KEY_GRAVE_ACCENT category)
              :preset-editor (create-key-mapping "key.ac.open_preset_editor" GLFW/GLFW_KEY_G category)
              :mode-switch (create-key-mapping "key.ac.mode_switch" GLFW/GLFW_KEY_V category)}
             ;; Preset switch key: C if original scheme (slot keys don't use C), N if alternative
             (if (= scheme :original)
               {:preset-switch (create-key-mapping "key.ac.preset_switch" GLFW/GLFW_KEY_C category)}
               {:preset-switch (create-key-mapping "key.ac.preset_switch" GLFW/GLFW_KEY_N category)})))
    (log/info "Client key bindings created" {:scheme scheme :slot-count (count @skill-keys)})))

(defn get-skill-keys [] @skill-keys)
(defn get-gui-keys [] (vals @gui-keys))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

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

(defn- original-scheme-skill-down?
  "Poll raw GLFW state for original key scheme (LMB/RMB/R/F)."
  [key-idx]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (let [window (.getWindow (.getWindow mc))]
      (case (int key-idx)
        0 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_LEFT))
        1 (= GLFW/GLFW_PRESS (GLFW/glfwGetMouseButton window GLFW/GLFW_MOUSE_BUTTON_RIGHT))
        2 (when-let [^KeyMapping key (nth @skill-keys 2 nil)] (.isDown key))
        3 (when-let [^KeyMapping key (nth @skill-keys 3 nil)] (.isDown key))
        false))))

(defn- skill-key-down?
  [scheme key-idx]
  (if (= scheme :original)
    (boolean (original-scheme-skill-down? key-idx))
    (when-let [^KeyMapping key (nth @skill-keys key-idx nil)]
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
  (let [delta (double (.getScrollDelta event))
        scheme @key-scheme
        activated? (boolean @overlay-state/client-activated-overlay)]
    (when (and activated?
               (not (current-screen-open?))
               (not (zero? delta)))
      (when-let [uuid (get-player-uuid)]
        (doseq [idx (range (count @skill-keys))]
          (when (skill-key-down? scheme idx)
            (power-runtime/client-on-slot-wheel! uuid idx delta)))))))

(defn tick-input! []
  (let [scheme @key-scheme
        activated? (boolean @overlay-state/client-activated-overlay)]
    ;; Update control override state
    (update-control-override! activated?)
    ;; Suppress vanilla keys when override is active
    (when @override-active?
      (suppress-vanilla-keys!))
    ;; Handle preset switch key
    (when-let [^KeyMapping preset-key (get @gui-keys :preset-switch)]
      (when (.consumeClick preset-key)
        (when-let [uuid (get-player-uuid)]
          (power-runtime/client-trigger-preset-switch! uuid))))
    ;; Tick skill keys
    (power-runtime/client-tick-keys!
      (fn [key-id]
        (case (first key-id)
          :skill (let [idx (second key-id)]
                   (skill-key-down? scheme idx))
          :movement (movement-key-down? (second key-id))
          :gui (when-let [^KeyMapping key (get @gui-keys (second key-id))] (.isDown key))
          false))
      get-player-uuid)))

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