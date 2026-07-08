(ns cn.li.ac.block.wireless-matrix.gui-reactive
  "Reactive Wireless Matrix GUI rendering layer.
   Functional logic (container, slots, network) stays in wireless_matrix/gui.clj."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn attach-binds! [r container _signals]
  (let [clock (rt/clock-ms-sig r)]
    (rt/put-user-signal! r :ssid
      (sig/computed-o [clock] (fn [_] (or @(:ssid container) "..."))))
    (rt/put-user-signal! r :bandwidth
      (sig/computed-o [clock] (fn [_] (str (or @(:bandwidth container) 0) " MHz"))))
    (rt/put-user-signal! r :connections
      (sig/computed-o [clock] (fn [_] (str (or @(:connections container) 0)))))))

(defn create-screen [container menu player]
  (bgui/create-screen
    {:page-xml "guis/rework/page_matrix.xml" :texture-name "matrix"
     :container container :menu menu
     :histograms [(bgui/hist-energy 0xFF4488CC)]
     :properties {:ssid #(or @(:ssid container) "...")
                  :bandwidth #(str (or @(:bandwidth container) 0) " MHz")
                  :connections #(str (or @(:connections container) 0))}
     :wireless? true :wireless-role :machine :custom-bind! attach-binds!}))

(def update! bgui/update-signals!)
(def open! bgui/open!)
