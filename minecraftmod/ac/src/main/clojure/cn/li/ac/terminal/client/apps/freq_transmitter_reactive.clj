(ns cn.li.ac.terminal.client.apps.freq-transmitter-reactive
  "Complete reactive replacement for freq_transmitter.clj."
  (:require [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]
            [cn.li.mcmod.ui.events :as events]
            [cn.li.mcmod.ui.xml :as ui-xml]))

(defn create-runtime []
  (let [r (rt/create-runtime)
        spec (ui-xml/load-spec (modid/namespaced-path "guis/new/freq_transmitter.xml"))
        _ (rt/build! r spec)
        frequency (sig/signal-d 0.0)
        target-name (sig/signal-o "None")
        transmitting? (sig/signal-o false)]
    (rt/put-user-signal! r :frequency frequency)
    (rt/put-user-signal! r :target-name target-name)
    (rt/put-user-signal! r :transmitting? transmitting?)
    (events/on! r :btn-send :left-click (fn [_ _ _] (sig/sset-o! transmitting? true)))
    (events/on! r :btn-clear :left-click (fn [_ _ _] (sig/sset-d! frequency 0.0) (sig/sset-o! target-name "None")))
    r))

(defn open! [] (let [r (create-runtime)] (bridge/open-reactive-screen! r "Freq Transmitter")))
