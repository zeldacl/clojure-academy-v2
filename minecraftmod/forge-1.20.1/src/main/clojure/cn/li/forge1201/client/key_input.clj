(ns cn.li.forge1201.client.key-input
  "CLIENT-ONLY key binding registration and polling (Forge layer)."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.forge1201.client.overlay-state :as overlay-state]
            [cn.li.forge1201.client.overlay-renderer :as overlay-renderer]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client KeyMapping Minecraft]
           [com.mojang.blaze3d.platform InputConstants$Type]
           [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.eventbus.api EventPriority]
           [net.minecraftforge.client.event InputEvent$Key]
           [org.lwjgl.glfw GLFW]))

(defonce ^:private skill-keys (atom []))
(defonce ^:private gui-keys (atom {}))
(defonce ^:private mode-switch-state (atom {:was-down false :down-at-ns nil :pending-clicks 0}))
(defonce ^:private raw-v-state (atom {:down-at-ns nil}))
(defonce ^:private raw-v-listener-registered? (atom false))

(def ^:private mode-switch-short-press-threshold-ns (* 300 1000 1000))

(declare get-player-uuid)

(defn- current-screen-open? []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (some? (.screen mc))))

(defn- on-raw-key-input! [^InputEvent$Key event]
  (let [key (.getKey event)
        action (.getAction event)
        now (System/nanoTime)]
    (when (= key GLFW/GLFW_KEY_V)
      (cond
        (= action GLFW/GLFW_PRESS)
        (do
          (swap! raw-v-state assoc :down-at-ns now)
          (overlay-renderer/on-mode-switch-key-state! true))

        (= action GLFW/GLFW_RELEASE)
        (let [down-at (:down-at-ns @raw-v-state)
              held-ns (if down-at (- now down-at) Long/MAX_VALUE)
              short-press? (< held-ns mode-switch-short-press-threshold-ns)
              screen-open? (boolean (current-screen-open?))]
          (swap! raw-v-state assoc :down-at-ns nil)
          (overlay-renderer/on-mode-switch-key-state! false)
          (when (and (not screen-open?) short-press?)
            (when-let [uuid (get-player-uuid)]
              (let [cur-activated (boolean (get-in (ability-runtime/get-player-state uuid) [:resource-data :activated]))]
                (overlay-state/set-client-activated! (not cur-activated))
                (ability-runtime/client-trigger-mode-switch! uuid)))))
        :else nil))))

(defn- consume-click-count [^KeyMapping key]
  (loop [n 0]
    (if (.consumeClick key)
      (recur (inc n))
      n)))

(defn- create-key-mapping [^String translation-key key-code ^String category]
  (KeyMapping. translation-key InputConstants$Type/KEYSYM (int key-code) category))

(defn register-keybinds! []
  (let [category "key.categories.ac.ability"]
    (reset! skill-keys
            [(create-key-mapping "key.ac.skill.0" GLFW/GLFW_KEY_Z category)
             (create-key-mapping "key.ac.skill.1" GLFW/GLFW_KEY_X category)
             (create-key-mapping "key.ac.skill.2" GLFW/GLFW_KEY_C category)
             (create-key-mapping "key.ac.skill.3" GLFW/GLFW_KEY_B category)])
    (reset! gui-keys
            {:skill-tree (create-key-mapping "key.ac.open_skill_tree" GLFW/GLFW_KEY_GRAVE_ACCENT category)
             :preset-editor (create-key-mapping "key.ac.open_preset_editor" GLFW/GLFW_KEY_G category)
             :mode-switch (create-key-mapping "key.ac.mode_switch" GLFW/GLFW_KEY_V category)})
    (log/info "Client key bindings created" {:skill-count (count @skill-keys)})))

(defn get-skill-keys [] @skill-keys)
(defn get-gui-keys [] (vals @gui-keys))

(defn- get-player-uuid []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (str (.getUUID player)))))

(defn tick-input! []
  (ability-runtime/client-tick-keys!
    (fn [key-id]
      (case (first key-id)
        :skill (when-let [^KeyMapping key (nth @skill-keys (second key-id) nil)] (.isDown key))
        :gui (when-let [^KeyMapping key (get @gui-keys (second key-id))] (.isDown key))
        false))
    get-player-uuid))

(defn init! []
  (register-keybinds!)
  (when (compare-and-set! raw-v-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false InputEvent$Key
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-raw-key-input! evt)))))
  (log/info "Client key input initialized"))