(ns cn.li.ac.block.wireless-node.gui-reactive
  "Reactive Wireless Node GUI rendering layer.
   Functional logic (container, slots, network) stays in wireless_node/gui.clj."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]
            [cn.li.mcmod.ui.runtime :as rt]
            [cn.li.mcmod.ui.signal :as sig]))

(defn attach-binds! [r container _signals]
  (let [clock (rt/clock-ms-sig r)]
    (rt/put-user-signal! r :connections
      (sig/computed-o [clock] (fn [_] (str (or @(:connections container) 0)))))
    (rt/put-user-signal! r :range
      (sig/computed-o [clock] (fn [_] (str (or @(:range container) "...")))))))

(defn create-screen [container menu player]
  (bgui/create-screen
    {:page-xml "guis/rework/page_wireless.xml" :texture-name "wireless"
     :container container :menu menu
     :histograms [(bgui/hist-energy 0xFF4488CC)]
     :properties {:range #(str (or @(:range container) "..."))
                  :connections #(str (or @(:connections container) 0))}
     :wireless? true :wireless-role :machine :custom-bind! attach-binds!}))

(def update! bgui/update-signals!)
(def open! bgui/open!)
