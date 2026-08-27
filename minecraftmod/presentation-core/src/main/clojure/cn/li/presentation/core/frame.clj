(ns cn.li.presentation.core.frame
  "Functional Presentation boundary. Contributors return immutable render/UI
   commands; the loader-specific adapter is the only code that materializes a
   Java FramePacket.")

(defn contributor [id f]
  (when-not (keyword? id)
    (throw (ex-info "presentation contributor id must be a keyword" {:id id})))
  (when-not (ifn? f)
    (throw (ex-info "presentation contributor must be callable" {:id id})))
  {:id id :render f})

(defn frame
  [contributors context limit]
  (let [limit (long (max 0 limit))
        commands (->> contributors
                      (sort-by :id)
                      (mapcat #((:render %) context))
                      (take limit)
                      vec)]
    {:frame-seq (long (or (:frame-seq context) 0))
     :commands commands
     :command-count (count commands)}))
