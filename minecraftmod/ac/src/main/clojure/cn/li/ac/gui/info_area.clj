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
        energy (double (or (:energy data) 0.0))
        max-energy (max 1.0 (double (or (:max-energy data) 1.0)))
        capacity (double (or (:load data) (:capacity data) 0.0))
        max-capacity (max 1.0 (double (or (:max-capacity data) 1.0)))
        load-ratio (max 0.0 (min 1.0 (/ capacity max-capacity)))]
    {:title "Info"
     :initialized? initialized?
     :editable? (and initialized? owner?)
     :load-ratio load-ratio
     ;; Histograms are data, not imperative widgets. Keeping them in the
     ;; shared projection preserves the Energy/Capacity bars from main while
     ;; allowing every container surface to render one reusable node.
     :histograms (cond-> []
                   (contains? data :energy)
                   (conj {:id :energy :label "Energy"
                          :ratio (max 0.0 (min 1.0 (/ energy max-energy)))
                          :value (format "%.0f IF" energy)})
                   (or (contains? data :load) (contains? data :capacity))
                   (conj {:id :capacity :label "Capacity"
                          :ratio load-ratio
                          :value (str (long capacity) "/" (long max-capacity))}))
     :fields [{:id :owner :label "Owner" :value (or (:owner data) "Unknown")}
              {:id :range :label "Range" :value (or (:range data) 0)}
              {:id :bandwidth :label "Bandwidth" :value (or (:bandwidth data) 0)}
              {:id :load :label "Load" :value (format "%.0f%%" (* 100.0 load-ratio))
               :ratio load-ratio}]}))
