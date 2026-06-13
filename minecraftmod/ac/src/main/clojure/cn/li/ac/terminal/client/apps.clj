(ns cn.li.ac.terminal.client.apps
  "CLIENT-ONLY: terminal app launchers keyed by catalog app id."
  (:require [cn.li.ac.terminal.client.apps.freq :as freq]
            [cn.li.ac.terminal.client.apps.media :as media]
            [cn.li.ac.terminal.client.apps.skill-tree :as skill-tree]
            [cn.li.ac.terminal.client.apps.static-pages :as static-pages]
            [cn.li.ac.terminal.client.apps.tutorial :as tutorial-app]
            [cn.li.mcmod.util.log :as log]))

(def launchers
  {:about static-pages/open-about!
   :settings static-pages/open-settings!
   :tutorial tutorial-app/open!
   :freq-transmitter freq/open!
   :media-player media/open!
   :skill-tree skill-tree/open!})

(defn launch!
  [app-id player]
  (if-let [launcher (get launchers app-id)]
    (do
      (log/info "Launching terminal app:" app-id)
      (launcher player)
      true)
    (do
      (log/error "No client launcher for app:" app-id)
      false)))
