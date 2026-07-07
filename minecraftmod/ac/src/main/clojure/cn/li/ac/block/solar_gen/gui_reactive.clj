(ns cn.li.ac.block.solar-gen.gui-reactive
  "Reactive Solar Generator GUI."
  (:require [cn.li.ac.gui.block-gui-reactive :as bgui]))

(defn create-screen [container menu player]
  (let [safe-val #(some-> % deref)
        max-e (fn [] (max 1.0 (double (or (safe-val (:max-energy container)) 0.0))))
        speed-fn (fn [] (format "%.2fIF/T" (double (or (safe-val (:gen-speed container)) 0.0))))]
    (bgui/create-screen
      {:page-xml "guis/rework/page_solar.xml"
       :texture-name "solar"
       :container container :menu menu
       :histograms [(bgui/hist-buffer (fn [] (double (or (safe-val (:energy container)) 0.0))) max-e)]
       :properties {:gen_speed speed-fn :status (fn [] (or (safe-val (:status container)) "STOPPED"))}
       :wireless? true :wireless-role :generator})))

(def update! bgui/update-signals!)
(def open! bgui/open!)
