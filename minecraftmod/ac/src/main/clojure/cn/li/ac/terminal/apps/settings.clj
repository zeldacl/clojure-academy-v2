(ns cn.li.ac.terminal.apps.settings
  "Settings app - Configure game settings."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn- create-settings-gui
  "Create the settings GUI."
  [_player]
  (let [root (cgui-core/create-widget :size [400 350])

        ;; Background
        bg (cgui-core/create-widget :pos [0 0] :size [400 350])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)

        ;; Title
        title (cgui-core/create-widget :pos [0 20] :size [400 30])
        title-text (comp/text-box :text "Settings" :color 0xFFFFFFFF :scale 1.5)
        _ (comp/add-component! title title-text)

        ;; Settings content
        content-y 70
        settings-lines ["Game Settings"
                       ""
                       "Key Bindings:"
                       "- Skill Tree: K"
                       "- Preset Editor: P"
                       "- Ability Slot 1: Z"
                       "- Ability Slot 2: X"
                       "- Ability Slot 3: C"
                       "- Ability Slot 4: V"
                       ""
                       "Display Settings:"
                       "- HUD Position: Customizable"
                       "- Ability Indicators: Enabled"
                       "- Particle Effects: Enabled"
                       ""
                       "Gameplay:"
                       "- Auto-regenerate CP: Enabled"
                       "- Ability Cooldown Display: Enabled"
                       ""
                       "Note: Some settings can be changed"
                       "in Minecraft's Options menu."]

        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui-core/create-widget :pos [30 (+ content-y (* idx 13))] :size [340 13])
                                 t (comp/text-box :text line :color 0xFFFFFFFF :scale 0.75)]
                             (comp/add-component! w t)
                             w))
                         settings-lines)]

    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title)
    (doseq [w content-widgets]
      (cgui-core/add-widget! root w))

    root))

(defn open-settings-gui
  "Open the settings GUI for a player."
  [player]
  (log/info "Opening settings for player:" player)
  (let [gui (create-settings-gui player)]
    (client-bridge/open-simple-gui! gui "Settings")))

;; ============================================================================
;; App Registration
;; ============================================================================

(def settings-app
  {:id :settings
   :name "Settings"
  :icon "my_mod:textures/guis/apps/settings/icon.png"
   :description "Configure game settings"
   :gui-fn 'cn.li.ac.terminal.apps.settings/open-settings-gui
   :category :system})

(defonce-guard settings-app-installed?)

(defn init-settings-app!
  []
  (with-init-guard settings-app-installed?
    (reg/register-app! settings-app)))
