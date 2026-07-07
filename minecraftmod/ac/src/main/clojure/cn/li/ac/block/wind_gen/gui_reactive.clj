(ns cn.li.ac.block.wind-gen.gui-reactive
  "Reactive Wind Generator GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        max-e (fn [] (max 1.0 (double (or (safe-val (:max-energy container)) 0.0))))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_windbase.xml"
       :texture-name "windbase"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double (or (safe-val (:energy container)) 0.0))) max-e)]
       :properties {:gen_speed (fn [] (format "%.2fIF/T" (double (or (safe-val (:gen-speed container)) 0.0))))
                    :status (fn [] (or (safe-val (:status container)) "IDLE"))
                    :altitude (fn [] (str (or (safe-val (:altitude container)) "...")))}
       :wireless? true :wireless-role :generator})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
