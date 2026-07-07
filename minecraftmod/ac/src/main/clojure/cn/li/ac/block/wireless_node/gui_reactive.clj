(ns cn.li.ac.block.wireless-node.gui-reactive
  "Reactive Wireless Node GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        max-e (fn [] (max 1.0 (double (or (safe-val (:max-energy container)) 0.0))))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_node.xml"
       :texture-name "node"
       :container container :menu menu
       :histograms [(bgui/hist-energy 0xFF4488CC)]
       :properties {:range (fn [] (str (or (safe-val (:range container)) "...")))
                    :connections (fn [] (str (or (safe-val (:connections container)) 0)))}
       :wireless? true :wireless-role :machine})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
