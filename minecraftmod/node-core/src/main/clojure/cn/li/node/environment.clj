(ns cn.li.node.environment
  "Immutable node execution environment.

   Domains build one environment during bootstrap and pass it explicitly to
   validators/VMs. The environment is a value, so AC/BC/CC can coexist in one
   process without resetting or leaking a process-global registry. Every
   descriptor is normalized by the canonical descriptor namespace before it
   enters the environment."
  (:require [cn.li.node.descriptor :as descriptor]))

(defn build
  [{:keys [descriptors extra-ops]}]
  (let [descriptors (mapv descriptor/normalize descriptors)
        ids (map :id descriptors)]
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
