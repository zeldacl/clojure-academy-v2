(ns cn.li.mcmod.platform.player-persistent-data
  "Per-player persistent NBT access via Framework function map.

   Data fn stored at [:platform :player-persistent-data :get!]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn install-player-persistent-data!
  [get-persistent-data-fn _label]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :player-persistent-data :get!] get-persistent-data-fn)) nil)

(defn get-persistent-data!
  [player]
  (when-let [f (get-in @(fw/fw-atom) [:platform :player-persistent-data :get!])]
    (f player)))

(defn persistent-data-available?
  []
  (boolean (get-in @(fw/fw-atom) [:platform :player-persistent-data :get!])))
