(ns cn.li.combat.passives
  "Generic declarative passive effects for compiled ability documents.

   The evaluator deliberately knows only resource targets and arithmetic
   operations.  It never names a skill or a category; those are data in the
   catalog and the owner's learned-skill snapshot.")

(defn- apply-effect [values {:keys [target operation value]}]
  (when-not (and (keyword? target) (number? value))
    (throw (ex-info "invalid passive resource effect"
                    {:effect {:target target :operation operation :value value}})))
  (let [current (double (get values target 0.0))
        amount (double value)]
    (assoc values target
           (case operation
             :add (+ current amount)
             :multiply (* current amount)
             (throw (ex-info "unsupported passive resource operation"
                             {:target target :operation operation}))))))

(defn build-index
  "Build the stable passive index once at catalog load time."
  [catalog]
  (->> (get-in catalog [:combat :abilities])
       (sort-by (comp str key))
       (keep (fn [[ability-id ability]]
               (when (= :passive (:activation ability))
                 [ability-id (vec (or (:passive-effects ability) []))])))
       vec))

(defn apply-resource-modifiers
  "Apply all learned declarative passive resource effects in `catalog`.

   `values` is a small target->number map, for example
   `{:max-cp 1000.0 :max-overload 200.0}`.  Compiled passive abilities are
   considered in stable id order so results are deterministic."
  [catalog ability-data values]
  (let [learned (set (or (:learned-skills ability-data) #{}))
        abilities (or (:passive-index catalog) (build-index catalog))]
    (reduce (fn [result [ability-id ability]]
              (if (contains? learned ability-id)
                (reduce apply-effect result ability)
                result))
            (into {} (map (fn [[k v]] [k (double v)]) values))
            abilities)))
