(ns cn.li.ac.terminal.apps.about
  "About app - Credits and information."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; App Implementation
;; ============================================================================

(defn- create-about-gui
  "Create the about/credits GUI."
  [_player]
  (let [root (cgui/create-widget :size [400 300])

        ;; Background
        bg (cgui/create-widget :pos [0 0] :size [400 300])
        bg-texture (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png"))
        _ (comp/add-component! bg bg-texture)

        ;; Title
        title (cgui/create-widget :pos [0 20] :size [400 30])
        title-text (comp/text-box :text "Academy Craft" :color 0xFFFFFFFF :scale 2.0)
        _ (comp/add-component! title title-text)

        ;; Version info
        version (cgui/create-widget :pos [0 60] :size [400 20])
        version-text (comp/text-box :text "Version: 2.0.0" :color 0xFFFFFFFF :scale 1.0)
        _ (comp/add-component! version version-text)

        ;; Credits
        credits-y 100
        credits-lines ["Created by: WeAthFolD"
                      "Ported to 1.20.1 by: Community"
                      ""
                      "Special Thanks:"
                      "- All contributors"
                      "- The Minecraft modding community"
                      ""
                      "Visit: github.com/LambdaInnovation/AcademyCraft"]

        credits-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui/create-widget :pos [20 (+ credits-y (* idx 15))] :size [360 15])
                                 t (comp/text-box :text line :color 0xFFCCCCCC :scale 0.8)]
                             (comp/add-component! w t)
                             w))
                         credits-lines)]

    (cgui/add-widget! root bg)
    (cgui/add-widget! root title)
    (cgui/add-widget! root version)
    (doseq [w credits-widgets]
      (cgui/add-widget! root w))

    root))

(defn open-about-gui
  "Open the about GUI for a player."
  [player]
  (log/info "Opening about screen for player:" player)
  (let [gui (create-about-gui player)]
    (client-bridge/open-simple-gui! gui "About")))

;; ============================================================================
;; App Registration
;; ============================================================================

(def about-app
  {:id :about
   :name "About"
   :icon "academy:textures/guis/apps/about/icon.png"
   :description "Credits and information"
   :gui-fn 'cn.li.ac.terminal.apps.about/open-about-gui
   :category :help})

(defonce ^:private about-app-installed? (atom false))

(defn init-about-app!
  []
  (when (compare-and-set! about-app-installed? false true)
    (reg/register-app! about-app)))
