(ns cn.li.node.environment
  "Immutable node execution environment.

   Domains build one environment during bootstrap and pass it explicitly to
   validators/VMs. The environment is a value, so AC/BC/CC can coexist in one
   process without resetting or leaking a process-global registry.")

(defn- valid-descriptor? [descriptor]
  (and (map? descriptor)
       (keyword? (:id descriptor))
       (integer? (:revision descriptor))
       (contains? #{:primitive :mid :source} (:layer descriptor))))

(defn build
  [{:keys [descriptors extra-ops]}]
  (let [descriptors (vec descriptors)
        ids (map :id descriptors)]
    (when-not (every? valid-descriptor? descriptors)
      (throw (ex-info "invalid node descriptor in environment" {})))
    (when-not (= (count ids) (count (set ids)))
      (throw (ex-info "duplicate node descriptor id" {:ids ids})))
    {:descriptors (into {} (map (juxt :id identity) descriptors))
     :extra-ops (or extra-ops {})}))

(defn descriptor
  [environment id]
  (get-in environment [:descriptors id]))

(defn all-descriptors
  [environment]
  (vals (:descriptors environment)))

(defn primitive-count
  [environment]
  (count (filter #(= :primitive (:layer %)) (all-descriptors environment))))

(defn add-descriptors
  "Pure extension used while composing ContentPacks; returns a new environment
   and never mutates the source value."
  [environment descriptors]
  (build {:descriptors (concat (all-descriptors environment) descriptors)
          :extra-ops (:extra-ops environment)}))

(defn add-ops
  [environment ops]
  (assoc environment :extra-ops (merge (:extra-ops environment) ops)))
