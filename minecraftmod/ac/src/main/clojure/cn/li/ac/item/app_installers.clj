(ns cn.li.ac.item.app-installers
  "Terminal app installer items migrated from original AcademyCraft."
  (:require [cn.li.mcmod.item.dsl :as idsl]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.terminal.player-data :as term-data]
            [cn.li.ac.terminal.app-registry :as app-reg]
            [cn.li.mcmod.util.log :as log]))

(defonce ^:private app-installers-installed? (atom false))

(defn- install-app-for-player!
  [player app-id]
  (let [uuid-str (str (entity/player-get-uuid player))]
    (cond
      (not (term-data/terminal-installed? uuid-str))
      (do
        (log/info "Skip app install: terminal not installed" {:player uuid-str :app app-id})
        {:consume? true})

      (not (app-reg/get-app app-id))
      (do
        (log/warn "Skip app install: app not registered" {:player uuid-str :app app-id})
        {:consume? true})

      :else
      (do
        (term-data/install-app! uuid-str app-id)
        (log/info "Installed terminal app from installer item" {:player uuid-str :app app-id})
        {:consume? true}))))

(defn- app-installer-handler
  [app-id]
  (fn [{:keys [player side]}]
    ;; App state lives on the server side.
    (when (= side :server)
      (install-app-for-player! player app-id))
    {:consume? true}))

(defn init-app-installers!
  []
  (when (compare-and-set! app-installers-installed? false true)
    (idsl/register-item!
      (idsl/create-item-spec
        "app_freq_transmitter"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["安装终端应用: 频率发射器"
                                "需要先安装终端"]
                      :model-texture "app_freq_transmitter"}
         :on-right-click (app-installer-handler :freq-transmitter)}))
    (idsl/register-item!
      (idsl/create-item-spec
        "app_media_player"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["安装终端应用: 媒体播放器"
                                "需要先安装终端"]
                      :model-texture "app_media_player"}
         :on-right-click (app-installer-handler :media-player)}))
    (idsl/register-item!
      (idsl/create-item-spec
        "app_skill_tree"
        {:max-stack-size 1
         :creative-tab :tools
         :properties {:tooltip ["安装终端应用: 技能树"
                                "需要先安装终端"]
                      :model-texture "app_skill_tree"}
         :on-right-click (app-installer-handler :skill-tree)}))
    (log/info "App installer items initialized: app_freq_transmitter, app_media_player, app_skill_tree")))
