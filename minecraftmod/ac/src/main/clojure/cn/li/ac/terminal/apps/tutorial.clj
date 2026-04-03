(ns cn.li.ac.terminal.apps.tutorial
  "Tutorial app - Learn how to use abilities."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn- create-tutorial-gui
  "Create the tutorial GUI."
  [_player]
  (let [root (cgui/create-widget :size [500 400])

        ;; Background
        bg (cgui/create-widget :pos [0 0] :size [500 400])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)

        ;; Title
        title (cgui/create-widget :pos [0 20] :size [500 30])
        title-text (comp/text-box :text "Academy Craft Tutorial" :color 0xFFFFFFFF :scale 1.5)
        _ (comp/add-component! title title-text)

        ;; Tutorial content
        content-y 70
        tutorial-lines ["Welcome to Academy Craft!"
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
                       "- Connect nodes to share energy"]

        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui/create-widget :pos [30 (+ content-y (* idx 14))] :size [440 14])
                                 t (comp/text-box :text line :color 0xFFFFFFFF :scale 0.75)]
                             (comp/add-component! w t)
                             w))
                         tutorial-lines)]

    (cgui/add-widget! root bg)
    (cgui/add-widget! root title)
    (doseq [w content-widgets]
      (cgui/add-widget! root w))

    root))

(defn open-tutorial-gui
  "Open the tutorial GUI for a player."
  [player]
  (log/info "Opening tutorial for player:" player)
  (let [gui (create-tutorial-gui player)]
    ;; Open via platform bridge
    (when-let [open-fn (requiring-resolve 'cn.li.forge1201.client.terminal-screen-bridge/open-simple-gui!)]
      (open-fn gui "Tutorial"))))

;; ============================================================================
;; App Registration
;; ============================================================================

(def tutorial-app
  {:id :tutorial
   :name "Tutorial"
   :icon "academy:textures/guis/apps/tutorial/icon.png"
   :description "Learn how to use your abilities"
   :gui-fn 'cn.li.ac.terminal.apps.tutorial/open-tutorial-gui
   :category :help})

(reg/register-app! tutorial-app)
