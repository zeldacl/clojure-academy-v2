(ns cn.li.ac.terminal.client.apps.freq-transmitter-reactive
  "Reactive Frequency Transmitter app.
   Migration stub for freq_transmitter.clj."
  (:require [cn.li.ac.terminal.client.apps.reactive-helpers :as h]))

(defn create-runtime []
  (h/load-app "guis/freq_transmitter.xml"))

(defn open! []
  (let [r (create-runtime)]
    (h/open-app! r "Frequency Transmitter")))
