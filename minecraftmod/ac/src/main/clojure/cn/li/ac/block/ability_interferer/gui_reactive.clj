(ns cn.li.ac.block.ability-interferer.gui-reactive
  "Reactive Ability Interferer GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        max-e (fn [] (max 1.0 (double (or (safe-val (:max-energy container)) 0.0))))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_interferer.xml"
       :texture-name "interferer"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double (or (safe-val (:energy container)) 0.0))) max-e)]
       :properties {:range (fn [] (str (or (safe-val (:range container)) "...")))
                    :active (fn [] (if (safe-val (:active? container)) "ON" "OFF"))}
       :wireless? false})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
