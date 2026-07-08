(ns cn.li.ac.terminal.client.apps.media-reactive
  "Complete reactive replacement for media.clj — signal-driven media player."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]
            [cn.li.mcmod.util.log :as log]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/media_player.xml"))
        _ (rt/build! r spec)
        playing? (sig/signal-o false)
        progress (sig/signal-d 0.0)
        volume (sig/signal-d 1.0)
        current-track (sig/signal-o "No media")]
    (rt/put-user-signal! r :playing? playing?)
    (rt/put-user-signal! r :progress progress)
    (rt/put-user-signal! r :volume volume)
    (rt/put-user-signal! r :current-track current-track)
    (events/on! r :btn-play :left-click (fn [_ _ _] (sig/sset-o! playing? true)))
    (events/on! r :btn-pause :left-click (fn [_ _ _] (sig/sset-o! playing? false)))
    (events/on! r :btn-stop :left-click (fn [_ _ _] (sig/sset-d! progress 0.0) (sig/sset-o! playing? false)))
    r))

(defn open! [] (let [r (create-runtime)] (bridge/open-reactive-screen! r "Media Player")))
