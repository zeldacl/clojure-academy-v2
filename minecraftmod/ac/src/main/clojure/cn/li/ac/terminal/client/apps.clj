(ns cn.li.ac.terminal.client.apps
  "CLIENT-ONLY: terminal app launchers keyed by catalog app id.
   Presentation Runtime migration: applications launch through the typed
   application ViewModel helper. Their open! fns take no player arg (unused
   in old impls too, aside from settings which ignored it) so launch! wraps them to match the
   (fn [player]) launcher contract. skill-tree stays on the old path."
  (:require [cn.li.ac.terminal.client.apps.about-reactive :as about]
            [cn.li.ac.terminal.client.apps.freq-transmitter-reactive :as freq-transmitter]
            [cn.li.ac.terminal.client.apps.media-reactive :as media]
            [cn.li.ac.terminal.client.apps.settings-reactive :as settings]
            [cn.li.ac.terminal.client.apps.skill-tree :as skill-tree]
            [cn.li.ac.terminal.client.apps.tutorial-reactive :as tutorial-app]
            [cn.li.mcmod.util.log :as log]))

(def launchers
  {:about (fn [_player] (about/open!))
   :settings (fn [_player] (settings/open!))
   :tutorial (fn [player] (tutorial-app/open! player))
   :freq-transmitter (fn [player] (freq-transmitter/open! player))
   :media-player (fn [_player] (media/open!))
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
