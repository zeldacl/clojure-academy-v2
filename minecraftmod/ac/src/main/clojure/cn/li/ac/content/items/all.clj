(ns cn.li.ac.content.items.all
  "Content entrypoint for AC item declarations."
  (:require [cn.li.ac.item.components :as components]
            [cn.li.ac.item.constraint-plate :as constraint-plate]
            [cn.li.ac.item.mat-core :as mat-core]
            [cn.li.ac.item.media :as media]
            [cn.li.ac.item.windgen-fan :as windgen-fan]))

(defonce ^:private items-installed? (atom false))

(defn init-items!
  []
  (when (compare-and-set! items-installed? false true)
    (components/init-components!)
    (constraint-plate/init-constraint-plate!)
    (mat-core/init-mat-cores!)
    (media/init-media!)
    (windgen-fan/init-windgen-fan!)))
