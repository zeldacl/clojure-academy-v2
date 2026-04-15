(ns cn.li.forge1201.client.ability-input
  "CLIENT-ONLY key binding registration and polling (Forge layer)."
  (:require [cn.li.ac.ability.client.keybinds :as ac-keybinds]
            [cn.li.ac.ability.player-state :as ac-ps]
            [cn.li.forge1201.client.ability-client-state :as client-state]
            [cn.li.forge1201.client.ability-hud-bridge :as hud-bridge]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client KeyMapping Minecraft]
           [com.mojang.blaze3d.platform InputConstants$Type]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.client.event InputEvent$Key]
           [org.lwjgl.glfw GLFW]))

;; Minecraft KeyMapping instances
(defonce ^:private skill-keys (atom []))
(defonce ^:private gui-keys (atom {}))
(defonce ^:private mode-switch-state
  (atom {:was-down false
         :down-at-ns nil
         :pending-clicks 0}))
(defonce ^:private raw-v-state
  (atom {:down-at-ns nil}))
(defonce ^:private raw-v-listener-registered? (atom false))

(def ^:private mode-switch-short-press-threshold-ns
  (* 300 1000 1000))

(declare get-player-uuid)

(defn- current-screen-open?
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.screen mc))))

(defn- on-raw-key-input!
  [^InputEvent$Key event]
  (let [key (.getKey event)
        action (.getAction event)
        now (System/nanoTime)]
    (when (= key GLFW/GLFW_KEY_V)
      (log/info "[V-TRACE][CLIENT][RAW]"
                {:key key
                 :action action
                 :screen-open (boolean (current-screen-open?))
                 :down-at-ns (:down-at-ns @raw-v-state)})
      (cond
        (= action GLFW/GLFW_PRESS)
        (do
          (swap! raw-v-state assoc :down-at-ns now)
          (log/info "[V-TRACE][CLIENT][PRESS]" {:down-at-ns now})
          (hud-bridge/on-mode-switch-key-state! true))

        (= action GLFW/GLFW_RELEASE)
        (let [down-at (:down-at-ns @raw-v-state)
              held-ns (if down-at (- now down-at) Long/MAX_VALUE)
              short-press? (< held-ns mode-switch-short-press-threshold-ns)
              screen-open? (boolean (current-screen-open?))]
          (swap! raw-v-state assoc :down-at-ns nil)
          (hud-bridge/on-mode-switch-key-state! false)
          (log/info "[V-TRACE][CLIENT][RELEASE]"
                    {:held-ns held-ns
                     :short-press short-press?
                     :screen-open screen-open?})
          (when (and (not screen-open?) short-press?)
            (if-let [uuid (get-player-uuid)]
              (do
                (log/info "[V-TRACE][CLIENT][TRIGGER]" {:uuid (str uuid)})
                ;; Compute next-activated for immediate HUD overlay (must read before trigger-mode-switch! clears nothing)
                (let [cur-activated (boolean (get-in (ac-ps/get-player-state uuid) [:resource-data :activated]))]
                  (client-state/set-client-activated! (not cur-activated))
                  (ac-keybinds/trigger-mode-switch! uuid)))
              (log/warn "[V-TRACE][CLIENT][TRIGGER-SKIP] no player uuid"))))

        :else nil))))

(defn- consume-click-count
  [^KeyMapping key]
  (loop [n 0]
    (if (.consumeClick key)
      (recur (inc n))
      n)))

(defn- tick-mode-switch!
  [^KeyMapping key]
  (let [is-down (.isDown key)
        now (System/nanoTime)
        click-count (consume-click-count key)
        {:keys [was-down down-at-ns pending-clicks]} @mode-switch-state
        pending (+ pending-clicks click-count)]
    ;; Always feed visual feedback state.
    (hud-bridge/on-mode-switch-key-state! is-down)

    (cond
      ;; Down edge: begin hold timing.
      (and (not was-down) is-down)
      (swap! mode-switch-state assoc
             :was-down true
             :down-at-ns now
             :pending-clicks pending)

      ;; Up edge: perform short-press check.
      (and was-down (not is-down))
      (let [held-ns (if down-at-ns (- now down-at-ns) Long/MAX_VALUE)]
        (when (and (pos? pending)
                   (< held-ns mode-switch-short-press-threshold-ns))
          (when-let [uuid (get-player-uuid)]
            (ac-keybinds/trigger-mode-switch! uuid)))
        (swap! mode-switch-state assoc
               :was-down false
               :down-at-ns nil
               :pending-clicks 0))

      ;; Fallback: ultra-short tap happened entirely between ticks.
      (and (not is-down) (not was-down) (pos? pending))
      (do
        (when-let [uuid (get-player-uuid)]
          (ac-keybinds/trigger-mode-switch! uuid))
        (swap! mode-switch-state assoc :pending-clicks 0))

      ;; Keep state fresh in all other cases.
      :else
      (swap! mode-switch-state assoc :pending-clicks pending))))

(defn- create-key-mapping
  "Create a KeyMapping with proper type hints to avoid reflection."
  [^String translation-key key-code ^String category]
  (KeyMapping. translation-key
               InputConstants$Type/KEYSYM
               (int key-code)
               category))

(defn register-keybinds!
  "Register all ability key bindings with Minecraft."
  []
  (let [category "key.categories.ac.ability"]

        ;; Create skill slot keys (Z, X, C, B)
        (reset! skill-keys
          [(create-key-mapping "key.ac.skill.0" GLFW/GLFW_KEY_Z category)
           (create-key-mapping "key.ac.skill.1" GLFW/GLFW_KEY_X category)
           (create-key-mapping "key.ac.skill.2" GLFW/GLFW_KEY_C category)
           (create-key-mapping "key.ac.skill.3" GLFW/GLFW_KEY_B category)])

        ;; Create GUI keys
        (reset! gui-keys
          {:skill-tree (create-key-mapping "key.ac.open_skill_tree"
            GLFW/GLFW_KEY_GRAVE_ACCENT
            category)
           :preset-editor (create-key-mapping "key.ac.open_preset_editor"
               GLFW/GLFW_KEY_G
               category)
           :mode-switch (create-key-mapping "key.ac.mode_switch"
             GLFW/GLFW_KEY_V
             category)})

    ;; Register with Minecraft (done via RegisterKeyMappingsEvent in mod/client init)
    (log/info "Ability key bindings created"
          {:skill-count (count @skill-keys)
           :gui-keys (keys @gui-keys)
           :mode-switch-key GLFW/GLFW_KEY_V})))

(defn get-skill-keys
  "Get all skill key mappings for registration."
  []
  @skill-keys)

(defn get-gui-keys
  "Get all GUI key mappings for registration."
  []
  (vals @gui-keys))

(defn- get-player-uuid
  "Get current client player UUID as string (consistent with server-sync key format)."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn tick-input!
  "Poll key states and delegate to AC layer. Called every client tick."
  []
  ;; Mode switch is handled by raw key event fallback to guarantee V responsiveness.

  ;; Provide key state function to AC layer with player UUID in binding
  (binding [cn.li.ac.ability.client.keybinds/*get-player-uuid-fn* get-player-uuid]
    (ac-keybinds/tick-keys!
      (fn [key-id]
        (case (first key-id)
          :skill
          (when-let [^KeyMapping key (nth @skill-keys (second key-id) nil)]
            (.isDown key))

          :gui
          (when-let [^KeyMapping key (get @gui-keys (second key-id))]
            (.isDown key))

          false)))))

(defn init!
  "Initialize key binding system."
  []
  (register-keybinds!)
  (when (compare-and-set! raw-v-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false InputEvent$Key
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-raw-key-input! evt)))))
  (log/info "[V-TRACE][CLIENT] raw V listener registered?" @raw-v-listener-registered?)
  (log/info "Ability input system initialized"))
