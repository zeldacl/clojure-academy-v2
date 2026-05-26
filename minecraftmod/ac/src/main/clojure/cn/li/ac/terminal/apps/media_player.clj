(ns cn.li.ac.terminal.apps.media-player
  "Media Player app - browse AcademyCraft media tracks."
  (:require [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.util.init-guard :refer [defonce-guard with-init-guard]]
            [cn.li.ac.terminal.apps.media-backend :as media-backend]
            [cn.li.ac.ability.util.uuid :as player-uuid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.util.log :as log]))

(defn- media-owner
  [player]
  (let [player-id (or (player-uuid/player-uuid player) (str player))]
    {:client-session-id (or runtime-hooks/*client-session-id*
                            [:media-client player-id])
     :screen-id :media-player
     :player-uuid player-id
     :profile-id player-id}))

(defn- create-media-player-gui
  [player]
  (let [root (cgui-core/create-widget :size [450 360])
        bg (cgui-core/create-widget :pos [0 0] :size [450 360])
        _ (comp/add-component! bg (comp/draw-texture (modid/asset-path "textures" "guis/data_terminal/app_back.png")))
        title (cgui-core/create-widget :pos [0 20] :size [450 30])
        _ (comp/add-component! title (comp/text-box :text "Media Player" :color 0xFFFFFFFF :scale 1.5))
        content-lines (into ["AcademyCraft Media Library"
                             ""
                             "Media disks can be obtained as items."
                             "Use this app to browse and manage tracks."
                             ""]
                            (media-backend/status-lines (media-owner player)))
        content-widgets (map-indexed
                         (fn [idx line]
                           (let [w (cgui-core/create-widget :pos [30 (+ 70 (* idx 15))] :size [390 15])]
                             (comp/add-component! w (comp/text-box :text line :color 0xFFFFFFFF :scale 0.75))
                             w))
                         content-lines)]
    (cgui-core/add-widget! root bg)
    (cgui-core/add-widget! root title)
    (doseq [w content-widgets]
      (cgui-core/add-widget! root w))
    root))

(defn open-media-player-gui
  [player]
  (log/info "Opening media player for player:" player)
  ;; Keep old app behavior of audible confirmation while backend controls are migrating.
  (media-backend/play-current! (media-owner player))
  (let [gui (create-media-player-gui player)]
    (client-bridge/open-simple-gui! gui "Media Player")))

(def media-player-app
  {:id :media-player
   :name "Media Player"
  :icon "my_mod:textures/guis/apps/media_player/icon.png"
   :description "Browse AcademyCraft media tracks"
   :gui-fn 'cn.li.ac.terminal.apps.media-player/open-media-player-gui
   :category :media})

(defonce-guard media-player-installed?)

(defn init-media-player-app!
  []
  (with-init-guard media-player-installed?
    (reg/register-app! media-player-app)))
