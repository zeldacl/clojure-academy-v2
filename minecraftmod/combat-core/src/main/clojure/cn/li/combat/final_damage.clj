(ns cn.li.combat.final-damage
  "Deterministic unified damage/reaction engine. Native hits and graph damage
   use this same data boundary; no platform object or callback is stored.")
(require '[cn.li.mcmod.runtime.damage-boundary :as boundary])
(def ^:const max-reflection-depth 8)
(defn event [{:keys [world-id source target base type seed depth metadata] :as value}]
  (when-not (and world-id source target (number? base) (keyword? type))
    (throw (ex-info "invalid final damage event" {:value value})))
  {:world-id world-id :source source :target target :base (double base) :type type
   :seed (long (or seed 0)) :depth (long (or depth 0)) :metadata (or metadata {})})
(defn- matches? [reaction event]
  (let [match (:match reaction)]
    (and (or (nil? (:types match)) (contains? (set (:types match)) (:type event)))
         (or (nil? (:target match)) (= (:target match) (:target event)))
         (or (nil? (:source match)) (= (:source match) (:source event))))))

(defn- lookup-path [value path]
  (reduce (fn [current key]
            (cond
              (map? current) (get current key)
              (vector? current) (get current (long key))
              (sequential? current) (nth (vec current) (long key) nil)
              :else nil)) value path))

(declare eval-value)
(defn- eval-value [value event]
  (cond
    (and (map? value) (:ref value))
    (let [[scope & path] (:ref value)]
      (lookup-path
       (case scope
         :request event
         :context (or (:context (:metadata event)) {})
         :param (or (:params (:metadata event)) {})
         :session (or (:session (:metadata event)) {})
         :slot (or (:slot (:metadata event)) {})
         :mark (or (:mark (:metadata event)) {})
         :input (or (:input (:metadata event)) {})
         nil)
       path))

    (and (map? value) (:tunable value))
    (lookup-path (or (:tunables (:metadata event)) {})
                 (into [(:tunable value)] (or (:path value) [])))

    (and (map? value) (:expr value))
    (let [op (:expr value) args (map #(eval-value % event) (:args value))]
      (case op
        :bool/and (every? true? args)
        :bool/or (boolean (some true? args))
        :bool/not (not (boolean (first args)))
        :value/eq (= (first args) (second args))
        :math/gt (> (double (or (first args) 0.0)) (double (or (second args) 0.0)))
        :math/gte (>= (double (or (first args) 0.0)) (double (or (second args) 0.0)))
        :math/lt (< (double (or (first args) 0.0)) (double (or (second args) 0.0)))
        :math/lte (<= (double (or (first args) 0.0)) (double (or (second args) 0.0)))
        :math/add (reduce + 0.0 (map #(double (or % 0.0)) args))
        :math/sub (reduce - (double (or (first args) 0.0)) (map #(double (or % 0.0)) (rest args)))
        :math/mul (reduce * 1.0 (map #(double (or % 0.0)) args))
        :math/div (if (zero? (double (or (second args) 0.0))) 0.0
                      (/ (double (or (first args) 0.0)) (double (second args))))
        :math/min (apply min (map #(double (or % 0.0)) args))
        :math/max (apply max (map #(double (or % 0.0)) args))
        :math/clamp (max (double (or (second args) 0.0))
                         (min (double (or (nth args 2) 0.0)) (double (or (first args) 0.0))))
        value))
    :else value))

(defn- policy-matches? [policy event]
  (let [damage-types (or (:damage-types policy)
                         (get-in policy [:program :damage-types]))]
    (and (or (= :combat/damage (:on policy)) (nil? (:on policy)))
       (matches? policy event)
       (or (nil? damage-types)
           (contains? (set damage-types) (:type event)))
       (if-let [predicate (:when policy)]
         (boolean (eval-value predicate event))
         true)
       (if-let [mark-type (:mark-type policy)]
         (= mark-type (get-in event [:metadata :input :context :mark-type]))
         true))))

(defn- policy-event
  "Select the immutable input snapshot belonging to one policy."
  [event policy]
  (let [metadata (:metadata event)
        inputs (or (:inputs metadata) {})
        input (or (get inputs (:ability-id policy)) (:input metadata) {})]
    (assoc event :metadata (assoc metadata :input input))))

(defn materialize-value
  "Resolve every neutral reference in a descriptor against an immutable input event."
  [value event]
  (letfn [(walk [value]
            (cond
              (and (map? value) (:ref value)) (eval-value value event)
              (map? value) (into {} (map (fn [[k v]] [k (walk v)]) value))
              (sequential? value) (mapv walk value)
              :else value))]
    (walk value)))

(defn materialize-vfx
  "Resolve a policy VFX descriptor against its immutable input event."
  [descriptor event]
  (when (map? descriptor)
    (cond-> descriptor
      (contains? descriptor :payload)
      (assoc :payload (materialize-value (:payload descriptor) event)))))

(defn- program-contributions [program event]
  (let [value #(double (or (eval-value % event) 0.0))
        cost-map (fn [cost] (into {} (map (fn [[resource amount]] [resource (value amount)]) (or cost {}))))]
    (case (:component program)
      :damage/multiply [{:kind :multiplier :value (value (:multiplier program))
                       :owner (:owner-id (get-in event [:metadata :input :context]))}]
      :damage/reduce [{:kind :reduction :value (value (:rate program))
                       :ignore-threshold (value (:ignore-threshold program))
                       :max-cost (value (:max-cost program)) :vfx (:vfx program)
                       :owner (:owner-id (get-in event [:metadata :input :context]))
                       :events (:events program) :input (:input (:metadata event))}]
      :damage/absorb [{:kind :absorption :value (value (:cap program))
                       :requires-payment true
                       :cost (cost-map (:cost program)) :vfx (:vfx program)
                       :owner (:owner-id (get-in event [:metadata :input :context]))
                       :events (:events program) :input (:input (:metadata event))}]
      :damage/cancel [{:kind :cancel}]
      :damage/reflect [{:kind :reflection :ratio (value (:multiplier program))
                        :minimum (value (:minimum program))
                        :max-depth (long (or (value (:max-depth program)) max-reflection-depth))
                        :cost-per-damage (value (:cost-per-damage program))
                        :owner (:owner-id (get-in event [:metadata :input :context]))
                        :exp-scale (value (:exp-scale program))
                        :vfx (:vfx program) :events (:events program) :input (:input (:metadata event))}]
      :damage/critical (mapv (fn [{:keys [level probability multiplier]}]
                               {:kind :critical
                                :level (long (or level 0))
                                :probability (value probability)
                                :multiplier (value multiplier)
                                :vfx (:vfx program)
                                :feedback (:feedback program)
                                :owner (:owner-id (get-in event [:metadata :input :context]))
                                :events (:events program) :input (:input (:metadata event))})
                             (:levels program))
      [])))

(defn collect [reactions event]
  (->> reactions (filter (fn [policy]
                          (policy-matches? policy (policy-event event policy))))
       (sort-by (juxt #(long (or (:priority %) 0)) #(str (:ability-id %)) #(str (:reaction-id %))))
       vec))
(defn- deterministic-roll [seed probability]
  (< (double (mod (Math/abs (long (hash [seed probability]))) 1000000)) (* 1000000.0 (double probability))))
(defn resolve-event [reactions raw-event]
  (let [event (event raw-event) matched (collect reactions event)
        raw-contributions (mapcat (fn [reaction]
                                    (let [pe (policy-event event reaction)]
                                      (map #(assoc % :ability-id (:ability-id reaction)
                                                     :reaction-id (:reaction-id reaction))
                                           (concat (or (:contributions reaction) [])
                                                   (program-contributions (:program reaction) pe)))))
                                  matched)
        contributions (vec (remove (fn [contribution]
                                     (and (:requires-payment contribution)
                                          (let [resources (get-in contribution [:input :context :resources] {})]
                                            (some (fn [[resource amount]]
                                                    (> (double (or amount 0.0))
                                                       (double (or (get resources resource) 0.0))))
                                                  (:cost contribution)))))
                                   raw-contributions))
        multiplier (reduce * 1.0 (map #(double (or (:value %) 1.0)) (filter #(= :multiplier (:kind %)) contributions)))
        reduction (min 1.0 (max 0.0 (reduce + 0.0 (map (fn [contribution] (let [threshold (:ignore-threshold contribution) ignored? (and (some? threshold) (> (double (:base event)) (double threshold)))] (if ignored? 0.0 (double (or (:value contribution) 0.0))))) (filter #(= :reduction (:kind %)) contributions)))))
        before (* (:base event) multiplier (- 1.0 reduction))
        absorption (min before (max 0.0 (reduce + 0.0 (map #(double (or (:value %) 0.0)) (filter #(= :absorption (:kind %)) contributions)))))
        criticals (filter #(= :critical (:kind %)) contributions)
        critical-levels (->> criticals
                             (group-by :level)
                             (map (fn [[level entries]]
                                    {:level level
                                     :probability (min 1.0 (max 0.0 (reduce + 0.0 (map #(double (or (:probability %) 0.0)) entries))))
                                     :multiplier (double (or (some->> entries (map :multiplier) (remove nil?) (apply max)) 1.0))
                                     :entries entries}))
                             (sort-by :level)
                             vec)
        critical-level (some (fn [{:keys [level probability] :as level-data}]
                              (when (and (pos? probability)
                                         (deterministic-roll (+ (long (:seed event)) (long level)) probability))
                                level-data))
                            critical-levels)
        critical? (boolean critical-level)
        amount-before-reflect (max 0.0 (* (- before absorption)
                         (if critical? (double (or (:multiplier critical-level) 1.0)) 1.0)))
        reflections (->> contributions (filter #(= :reflection (:kind %)))
                       (keep (fn [reflection]
                               (when (and (not= :environment (:source event))
                                          (< (:depth event)
                                             (min max-reflection-depth
                                                  (long (or (:max-depth reflection)
                                                            max-reflection-depth)))))
                                 (assoc event :source (:target event) :target (:source event)
                                        :base (* amount-before-reflect (double (or (:ratio reflection) 1.0)))
                                        :minimum (double (or (:minimum reflection) 0.0))
                                        :depth (inc (:depth event))
                                        :metadata (assoc (:metadata event)
                                                         :reflected? true
                                                         :reflection reflection))))) vec)
        reflection-total (reduce + 0.0 (map #(double (or (:base %) 0.0)) reflections))
        amount (max 0.0 (- amount-before-reflect reflection-total))
        absorb-cost (if (pos? absorption)
                      (reduce (fn [acc contribution]
                                (merge-with + acc (:cost contribution)))
                              {} (filter #(= :absorption (:kind %)) contributions))
                      {})
        reduction-cost (reduce + 0.0 (map (fn [contribution] (let [threshold (:ignore-threshold contribution) ignored? (and (some? threshold) (> (double (:base event)) (double threshold)))] (if ignored? 0.0 (min (max 0.0 (double (or (:max-cost contribution) 0.0))) (* (double (:base event)) (double (or (:value contribution) 0.0))))))) (filter #(= :reduction (:kind %)) contributions)))
        reflection-cost (reduce + 0.0
                                 (map (fn [contribution]
                                        (* amount-before-reflect (double (or (:ratio contribution) 0.0))
                                           (double (or (:cost-per-damage contribution) 0.0))))
                                      (filter #(= :reflection (:kind %)) contributions)))
        resource-costs (merge-with + absorb-cost
                                    (when (pos? reduction-cost) {:cp reduction-cost})
                                    (when (pos? reflection-cost) {:cp reflection-cost}))]
    {:event event :matched (mapv #(select-keys % [:ability-id :reaction-id]) matched)
     :amount amount :resource-costs resource-costs :cancelled? (boolean (some #(= :cancel (:kind %)) contributions))
     :critical? critical? :critical-level critical-level :reflections reflections
     :vfx (vec (keep :vfx (filter #(or (and (= :critical (:kind %)) critical?
                                                   (= (:level %) (:level critical-level)))
                                      (= :reflection (:kind %))
                                      (or (= :absorption (:kind %)) (= :reduction (:kind %)))) contributions)))
     :feedback (vec
                  (keep identity
                        (map (fn [contribution]
                               (let [input (or (:input contribution) {})
                                     input (assoc-in input [:context :critical-multiplier]
                                                     (double (or (:multiplier critical-level) 1.0)))]
                                 (materialize-value
                                  (:feedback contribution)
                                  (assoc event :metadata {:input input}))))
                             (filter #(and (= :critical (:kind %)) critical? (= (:level %) (:level critical-level))) contributions))))
     :side-events (vec (distinct (mapcat #(or (:events %) [])
                                          (filter #(or (and (= :critical (:kind %)) critical?
                                                            (= (:level %) (:level critical-level)))
                                                       (= :reflection (:kind %))
                                                       (or (= :absorption (:kind %)) (= :reduction (:kind %)))) contributions))))
     :state-patches (vec (mapcat :state-patches matched))
     :events (vec (mapcat :events matched))}))
(defn install-boundary!
  "Connect the pure resolver to mcmod's begin/complete SPI. The token holds
   only neutral result data; commit-state! runs only after :applied? true."
  [{:keys [reactions state-provider commit-state!]}]
  (boundary/install!
   {:begin (fn [raw]
             (let [result (resolve-event reactions raw)]
               {:token result :amount (:amount result) :cancelled? (:cancelled? result)}))
    :complete (fn [{:keys [token applied? actual-amount]}]
                (when (and applied? (ifn? commit-state!))
                  (commit-state! {:event (:event token) :amount actual-amount
                                  :state-patches (:state-patches token) :events (:events token)}))
                {:applied? applied? :amount actual-amount})}))
