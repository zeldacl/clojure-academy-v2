(ns cn.li.ac.block.imag-fusor.gui-reactive
  "Reactive Imaginary Fusor GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        max-e (fn [] (max 1.0 (double (or (safe-val (:max-energy container)) 0.0))))
        progress-fn (fn [] (double (or (safe-val (:progress container)) 0.0)))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_imag.xml"
       :texture-name "imagfusor"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double (or (safe-val (:energy container)) 0.0))) max-e)
                    (bgui/hist-buffer progress-fn (fn [] 1.0))]
       :properties {:status (fn [] (or (safe-val (:status container)) "IDLE"))}
       :wireless? true :wireless-role :machine})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
