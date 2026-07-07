(ns cn.li.ac.block.wireless-matrix.gui-reactive
  "Reactive Wireless Matrix GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)]
    (bgui/create-screen
      {:page-xml "guis/rework/page_matrix.xml"
       :texture-name "matrix"
       :container container :menu menu
       :histograms [(bgui/hist-energy 0xFF4488CC)]
       :properties {:ssid (fn [] (or (safe-val (:ssid container)) "..."))
                    :bandwidth (fn [] (str (or (safe-val (:bandwidth container)) 0) " MHz"))
                    :connections (fn [] (str (or (safe-val (:connections container)) 0)))}
       :wireless? true :wireless-role :machine})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
