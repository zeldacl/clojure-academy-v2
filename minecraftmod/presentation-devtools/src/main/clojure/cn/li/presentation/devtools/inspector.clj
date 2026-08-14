(ns cn.li.presentation.devtools.inspector)

(defn snapshot [runtime]
  {:mounts (count (:mounts @(:state runtime)))
   :invalidated? (:invalidated? @(:state runtime))})

(defn signal-dependency-report [bindings]
  {:binding-count (count bindings)
   :bindings (sort (keys bindings))})
