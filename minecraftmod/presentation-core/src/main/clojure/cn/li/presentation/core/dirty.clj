(ns cn.li.presentation.core.dirty
  "Clojure dirty-bit state. Dynamic uniforms/transforms never imply layout.")

(def all #{:structure :measure :layout :paint :transform :semantics})
(def dynamic #{:paint :transform})

(defn create [] (atom #{:structure :measure :layout :paint :semantics}))

(defn mark! [dirty-state flags]
  (let [flags (set flags)]
    (when-not (every? all flags)
      (throw (ex-info "unknown dirty flag" {:flags flags})))
    (swap! dirty-state into flags))
  nil)

(defn dynamic-update! [dirty-state kind]
  (when-not (contains? dynamic kind)
    (throw (ex-info "dynamic update may only mark paint/transform" {:kind kind})))
  (mark! dirty-state [kind]))

(defn take! [dirty-state]
  (let [current @dirty-state]
    (reset! dirty-state #{})
    current))

(defn dirty? [dirty-state flag]
  (contains? @dirty-state flag))
