(ns cn.li.ac.terminal.apps.media-player
  "Media Player app - browse AcademyCraft media tracks."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

(defn- create-media-player-gui
  [_player]
  (let [root (cgui/create-widget :size [450 360])
        bg (cgui/create-widget :pos [0 0] :size [450 360])
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title (cgui/create-widget :pos [0 20] :size [450 30])
        _ (comp/add-component! title (comp/text-box :text "Media Player" :color 0xFFFFFFFF :scale 1.5))
        content-lines ["AcademyCraft Media Library"
                       ""
                       "Available tracks:"
                       "- Sisters' Noise"
                       "- Only My Railgun"
                       "- Level5 Judgelight"
                       ""
                       "Media disks can be obtained as items."
                       "Use this app to browse and manage tracks."
                       ""
                       "Playback integration is under migration"
                       "for Minecraft 1.20 architecture."]
        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui/create-widget :pos [30 (+ 70 (* idx 15))] :size [390 15])]
                             (comp/add-component! w (comp/text-box :text line :color 0xFFFFFFFF :scale 0.75))
                             w))
                         content-lines)]
    (cgui/add-widget! root bg)
    (cgui/add-widget! root title)
    (doseq [w content-widgets]
      (cgui/add-widget! root w))
    root))

(defn open-media-player-gui
  [player]
  (log/info "Opening media player for player:" player)
  (let [gui (create-media-player-gui player)]
    (client-bridge/open-simple-gui! gui "Media Player")))

(def media-player-app
  {:id :media-player
   :name "Media Player"
   :icon "academy:textures/guis/apps/media_player/icon.png"
   :description "Browse AcademyCraft media tracks"
   :gui-fn 'cn.li.ac.terminal.apps.media-player/open-media-player-gui
   :category :media})

(defonce ^:private media-player-installed? (atom false))

(defn init-media-player-app!
  []
  (when (compare-and-set! media-player-installed? false true)
    (reg/register-app! media-player-app)))
