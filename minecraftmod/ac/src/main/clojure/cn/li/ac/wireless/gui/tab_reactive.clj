(ns cn.li.ac.wireless.gui.tab-reactive
  "Reactive Wireless Tab — signal-driven network panel.
   Replaces cgui-core widget tree + wireless-tab/view.clj pattern."
  (:require [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn create-wireless-panel [{:keys [role container menu]}]
  (let [r (rt/create-runtime)
        ssid (sig/signal-o "")
        connections (sig/signal-l 0)
        bandwidth (sig/signal-d 0.0)]
    (rt/put-user-signal! r :ssid ssid)
    (rt/put-user-signal! r :connections connections)
    (rt/put-user-signal! r :bandwidth bandwidth)
    {:runtime r :role role}))
