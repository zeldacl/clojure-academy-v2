(ns cn.li.ac.terminal.client.apps.media-reactive
  "Reactive Media Player app — signal-driven progress/controls.
   Migration stub for media.clj (XML layout + signal bindings)."
  (:require [cn.li.ac.terminal.client.apps.reactive-helpers :as h]))

(defn create-runtime []
  (h/load-app "guis/media_player.xml"))

(defn open! []
  (let [r (create-runtime)]
    (h/open-app! r "Media Player")))
