(ns cn.li.node.descriptor
  "Pure descriptor values and immutable NodeEnvironment construction. A
   catalog is owned by one composition root; no process-global registry or
   freeze lifecycle exists, so AC/BC/CC and multiplayer sessions are isolated.")

(def valid-layers #{:primitive :mid :source})

;; Forward declarations keep the pure constructor near the public layer while
;; the field validators remain private implementation details below.
(declare valid-fields? valid-children?)

(defn normalize
  "Validate one descriptor without mutating any registry. This is the value
   constructor used by the shared NodeEnvironment." 
  [{:keys [id revision layer inputs outputs children effects impl] :as spec}]
  (when-not (and (keyword? id) (integer? revision) (contains? valid-layers layer))
    (throw (ex-info "invalid node descriptor: :id/:revision/:layer"
                    {:id id :spec (dissoc spec :impl)})))
  (when-not (and (valid-fields? inputs) (valid-fields? outputs) (valid-children? children))
    (throw (ex-info "invalid node descriptor fields" {:id id})))
  (when (and (= layer :mid) impl)
    (throw (ex-info "mid-layer descriptor must not contain :impl" {:id id})))
  (when (and (= layer :primitive) (nil? impl))
    (throw (ex-info "primitive descriptor must contain :impl" {:id id})))
  (when (and (= layer :source) impl)
    (throw (ex-info "source descriptor must not contain :impl" {:id id})))
  (-> spec
      (update :inputs #(or % {}))
      (update :outputs #(or % {}))
      (update :children #(or % {}))
      (update :effects #(or % #{}))
      (update :reads-environment #(or % #{}))))

(defn- valid-field-spec? [spec]
  (and (map? spec) (contains? spec :type)))

(defn- valid-fields? [fields]
  (or (nil? fields) (and (map? fields) (every? valid-field-spec? (vals fields)))))

(defn- valid-children? [children]
  (or (nil? children)
      (and (map? children)
           (every? (fn [spec] (and (map? spec) (contains? #{:single :seq :case-map} (:kind spec))))
                   (vals children)))))
