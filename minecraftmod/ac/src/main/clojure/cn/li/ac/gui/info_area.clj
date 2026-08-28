(ns cn.li.ac.gui.info-area
  "AC themed InfoArea composition over presentation-core primitives."
  (:require [cn.li.presentation.core.components :as components]))

(defn property-node [key label-bind value-bind]
  (components/property-row key label-bind value-bind {:height 18}))

(defn histogram-node [key value-bind]
  (components/histogram key value-bind {:height 6}))

(defn info-area-node [key fields]
  (components/panel key {:direction :column :width :fill :height :fill}
                    (mapv (fn [{:keys [id label value ratio]}]
                            (if ratio
                              (histogram-node id ratio)
                              (property-node id label value)))
                          fields)))

(defn snapshot
  [data policy]
  (let [initialized? (boolean (:initialized data))
        owner? (boolean (:owner? policy))
        load-ratio (max 0.0 (min 1.0 (/ (double (or (:load data) 0))
                                         (max 1.0 (double (or (:max-capacity data) 1))))))]
    {:title "Info"
     :initialized? initialized?
     :editable? (and initialized? owner?)
     :load-ratio load-ratio
     :fields [{:id :owner :label "Owner" :value (or (:owner data) "Unknown")}
              {:id :range :label "Range" :value (or (:range data) 0)}
              {:id :bandwidth :label "Bandwidth" :value (or (:bandwidth data) 0)}
              {:id :load :label "Load" :value (format "%.0f%%" (* 100.0 load-ratio))
               :ratio load-ratio}]}))
(defn create-model [initial]
  {:state (atom initial) :revision (atom 0)})

(defn rebuild! [model next-snapshot]
  (swap! (:revision model) inc)
  (reset! (:state model) next-snapshot)
  @(:state model))

(defn attach! [model initial]
  (if model
    (rebuild! model initial)
    (create-model initial)))
