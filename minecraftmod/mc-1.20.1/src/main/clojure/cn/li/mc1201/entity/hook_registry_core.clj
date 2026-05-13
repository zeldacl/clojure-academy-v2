(ns cn.li.mc1201.entity.hook-registry-core
  "Shared helpers for scripted entity hook registration."
  (:require [clojure.string :as str]
            [cn.li.mcmod.entity.dsl :as edsl]
            [cn.li.mcmod.util.log :as log]))

(defn normalize-impl-key
  [impl-key]
  (cond
    (keyword? impl-key) impl-key
    (string? impl-key) (keyword impl-key)
    :else nil))

(defn collect-hook-entries
  [{:keys [entity-kind property-key label resolve-hook-class]}]
  (->> (edsl/list-entities)
       (keep (fn [entity-id]
               (let [entity-spec (edsl/get-entity entity-id)
                     hook-props (get-in entity-spec [:properties property-key])
                     hook-id (some-> (:hook hook-props) name)
                     resolved (when hook-props
                                (resolve-hook-class {:entity-id entity-id
                                                     :entity-spec entity-spec
                                                     :hook-props hook-props
                                                     :hook-id hook-id}))]
                 (when (= entity-kind (:entity-kind entity-spec))
                   (cond
                     (or (nil? hook-props) (empty? hook-props))
                     (log/warn (str label " is missing :properties/" (name property-key))
                               {:entity-id entity-id})

                     (or (nil? hook-id) (empty? hook-id))
                     (log/warn (str label " is missing :" (name property-key) "/:hook")
                               {:entity-id entity-id})

                     (or (nil? (:hook-class resolved)) (empty? (:hook-class resolved)))
                     (log/warn (str label " hook has no registered platform hook class")
                               (merge {:entity-id entity-id :hook-id hook-id}
                                      (select-keys resolved [:hook-impl-key])))

                     :else
                     (merge {:entity-id entity-id
                             :hook-id hook-id}
                            resolved))))))
       distinct))

(defn resolve-hook-conflicts
  [label hook-entries]
  (->> hook-entries
       (group-by :hook-id)
       (keep (fn [[hook-id entries]]
               (let [classes (distinct (map :hook-class entries))]
                 (if (> (count classes) 1)
                   (do
                     (log/warn (str label " hook-id has conflicting hook-class definitions; skipping registration")
                               {:hook-id hook-id
                                :definitions (mapv (fn [{:keys [entity-id hook-class]}]
                                                     {:entity-id entity-id
                                                      :hook-class hook-class})
                                                   entries)})
                     nil)
                   [hook-id (first classes)]))))))

(defn register-hook-classes!
  [{:keys [installed?-atom entries register-fn success-label]}]
  (when (compare-and-set! installed?-atom false true)
    (doseq [[hook-id class-name] entries]
      (if (register-fn hook-id class-name)
        (log/info success-label {:hook-id hook-id :class class-name})
        (log/error (str "Failed to register " (str/lower-case success-label))
                   {:hook-id hook-id :class class-name}))))
  nil)
