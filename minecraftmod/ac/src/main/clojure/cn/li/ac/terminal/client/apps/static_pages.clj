(ns cn.li.ac.terminal.client.apps.static-pages
  "CLIENT-ONLY: data-driven static text terminal apps."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.util.log :as log]))

(defn- create-text-page-gui
  [{:keys [title size lines title-font-size line-font-size]}]
  (let [[w h] size
        root (cgui-core/create-widget :size size)
        bg (cgui-core/create-widget :pos [0 0] :size size)
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title-w (cgui-core/create-widget :pos [0 20] :size [w 30])
        _ (comp/add-component! title-w (comp/text-box :text title :font :ac-normal :font-size (or title-font-size 12) :color 0xFFFFFFFF))
        content-y 70
        line-h (if (< w 420) 13 15)
        content-widgets (map-indexed
                         (fn [idx line]
                           (let [widget (cgui-core/create-widget
                                         :pos [30 (+ content-y (* idx line-h))]
                                         :size [(- w 60) line-h])]
                             (comp/add-component! widget
                                                (comp/text-box :text line
                                                               :font :ac-normal
                                                               :font-size (or line-font-size 8)
                                                               :color 0xFFFFFFFF))
                             widget))
                         lines)]
    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title-w)
    (doseq [widget content-widgets]
      (cgui-core/add-widget! root widget))
    root))

(defn open-about!
  [player]
  (log/info "Opening about screen for player:" player)
  (client-bridge/open-simple-gui!
    (create-text-page-gui
      {:title "Academy Craft"
       :size [400 300]
       :title-font-size 42
       :lines ["Version: 2.0.0"
               ""
               "Created by: WeAthFolD"
               "Ported to 1.20.1 by: Community"
               ""
               "Special Thanks:"
               "- All contributors"
               "- The Minecraft modding community"
               ""
               "Visit: github.com/LambdaInnovation/AcademyCraft"]})
    "About"))

(defn open-settings!
  [player]
  (log/info "Opening settings for player:" player)
  (client-bridge/open-simple-gui!
    (create-text-page-gui
      {:title "Settings"
       :size [400 350]
       :lines ["Game Settings"
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
               "in Minecraft's Options menu."]})
    "Settings"))

(defn open-tutorial!
  [player]
  (log/info "Opening tutorial for player:" player)
  (client-bridge/open-simple-gui!
    (create-text-page-gui
      {:title "Academy Craft Tutorial"
       :size [500 400]
       :lines ["Welcome to Academy Craft!"
               ""
               "Getting Started:"
               "1. Open your Skill Tree (default: K key)"
               "2. Learn abilities by clicking on skill nodes"
               "3. Assign skills to hotkeys (Z, X, C, V)"
               ""
               "Using Abilities:"
               "- Press assigned hotkey to activate"
               "- Hold key for continuous abilities"
               "- Watch your CP (Compute Power) bar"
               ""
               "Managing Presets:"
               "- Create multiple skill loadouts"
               "- Switch between presets quickly"
               "- Customize for different situations"
               ""
               "Energy System:"
               "- Use generators to produce energy"
               "- Build wireless networks for power"
               "- Connect nodes to share energy"]})
    "Tutorial"))
