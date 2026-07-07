(ns cn.li.ac.block.metal-former.gui-reactive
  "Reactive Metal Former GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        progress-fn (fn [] (double (or (safe-val (:progress container)) 0.0)))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_metalformer.xml"
       :texture-name "metalformer"
       :container container :menu menu
       :histograms [(bgui/hist-buffer progress-fn (fn [] 1.0))]
       :properties {:mode (fn [] (or (safe-val (:mode container)) "IDLE"))}
       :wireless? true :wireless-role :machine})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
