(ns cn.li.forge1201.client.ability-input
  "CLIENT-ONLY key binding registration and polling (Forge layer)."
  (:require [cn.li.ac.ability.client.keybinds :as ac-keybinds]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client KeyMapping Minecraft]
           [com.mojang.blaze3d.platform InputConstants$Type]
           [org.lwjgl.glfw GLFW]))

;; Minecraft KeyMapping instances
(defonce ^:private skill-keys (atom []))
(defonce ^:private gui-keys (atom {}))

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

    ;; Register with Minecraft (done via FMLClientSetupEvent in init.clj)
    (log/info "Ability key bindings created")))

(defn get-skill-keys
  "Get all skill key mappings for registration."
  []
  @skill-keys)

(defn get-gui-keys
  "Get all GUI key mappings for registration."
  []
  (vals @gui-keys))

(defn- get-player-uuid
  "Get current client player UUID."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (.getUUID player))))

(defn tick-input!
  "Poll key states and delegate to AC layer. Called every client tick."
  []
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
  (log/info "Ability input system initialized"))
