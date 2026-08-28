(ns cn.li.node.descriptor
  "Immutable descriptor validation for the single node ABI.

   The public layers are source, primitive and composite. Kernel descriptors
   are internal execution boundaries and can only be reached from a trusted
   composite lowering; they are never exported to the visual editor.")

(def valid-layers #{:primitive :composite :kernel :source})

(declare valid-fields? valid-children? valid-execution?)

(defn normalize
  [{:keys [id revision layer inputs outputs children effects impl execution]
    :as spec}]
  (when-not (and (keyword? id)
                 (integer? revision)
                 (contains? valid-layers layer))
    (throw (ex-info "invalid node descriptor: :id/:revision/:layer"
                    {:id id :layer layer :spec (dissoc spec :impl)})))
  (when-not (and (valid-fields? inputs)
                 (valid-fields? outputs)
                 (valid-children? children)
                 (valid-execution? execution))
    (throw (ex-info "invalid node descriptor fields" {:id id})))
  (when (and (contains? #{:composite :kernel} layer) impl)
    (throw (ex-info "composite/kernel descriptor must not contain :impl"
                    {:id id :layer layer})))
  (when (and (= layer :primitive) (nil? impl) (nil? execution))
    (throw (ex-info "primitive descriptor must contain :impl or :execution"
                    {:id id})))
  (when (and (= layer :source) (or impl execution))
    (throw (ex-info "source descriptor must not contain implementation"
                    {:id id})))
  (when (and (= layer :kernel)
             (not= :internal (:visibility spec)))
    (throw (ex-info "kernel descriptor must be internal"
                    {:id id :visibility (:visibility spec)})))
  (-> spec
      (update :inputs #(or % {}))
      (update :outputs #(or % {}))
      (update :children #(or % {}))
      (update :effects #(or % #{}))
      (update :reads-environment #(or % #{}))))

(defn- valid-field-spec? [spec]
  (and (map? spec) (contains? spec :type)))

(defn- valid-fields? [fields]
  (or (nil? fields)
      (and (map? fields)
           (every? valid-field-spec? (vals fields)))))

(defn- valid-children? [children]
  (or (nil? children)
      (and (map? children)
           (every? (fn [spec]
                     (and (map? spec)
                          (contains? #{:single :seq :case-map} (:kind spec))))
                   (vals children)))))

(defn- valid-execution? [execution]
  (or (nil? execution)
      (and (map? execution)
           (keyword? (:kind execution))
           (or (nil? (:capability execution))
               (keyword? (:capability execution))))))
