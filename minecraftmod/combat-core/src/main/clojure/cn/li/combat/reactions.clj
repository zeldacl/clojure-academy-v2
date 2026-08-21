(ns cn.li.combat.reactions
  "Generic declarative damage reaction evaluator.

   Reactions are ordinary EDN data.  State/session access is injected by the
   caller; this namespace has no AC or skill knowledge."
  (:require [cn.li.combat.vm :as vm]))

(defn- scale-components [value factor]
  (cond
    (number? value) (* (double value) (double factor))
    (map? value) (reduce-kv (fn [m k v]
                              (assoc m k (scale-components v factor)))
                            (empty value) value)
    (vector? value) (mapv #(scale-components % factor) value)
    (seq? value) (mapv #(scale-components % factor) value)
    :else value))

(defn- value [value context]
  (cond
    (and (map? value) (:expr value))
    (vm/evaluate-expression (:expr value)
                            (mapv #(value % context) (:args value))
                            (long (or (:activation-seed context) 0)))
    (and (map? value) (:ref value))
    (let [[scope key & path] (:ref value)
          root (case scope
                 :context (:context context) :request (:request context)
                 :param (:params context) :session (:session-state context)
                 :state (:state context) {})]
      (get-in root (into [key] path)))
    (and (map? value) (contains? value :tunable))
    (let [resolved (get (:tunables context) (:tunable value))]
      (if (contains? value :path)
        (get-in resolved (:path value))
        resolved))
    (map? value) (reduce-kv (fn [m k v] (assoc m k (value v context)))
                            (empty value) value)
    (vector? value) (mapv #(value % context) value)
    :else value))

(defn apply!
  [request {:keys [reactions session-fn state-fn precheck? tunables-fn
                  domain-state]}]
  (let [reactions (->> reactions
                       (mapcat (fn [entry]
                                 (if (seq (:reactions entry))
                                   (map #(assoc %
                                                :ability-id (:id entry)
                                                :activation (:activation entry))
                                        (:reactions entry))
                                   [entry])))
                       (filter #(= :combat/damage (:on %)))
                       (sort-by (juxt #(long (or (:priority %) 0))
                                      #(str (:ability-id %)))))]
    (reduce
     (fn [current {:keys [ability-id activation when program mark-type]}]
       (let [target (str (:target current))
             session (session-fn target)
             state (state-fn target)
             mark (when mark-type
                    (get-in domain-state [:entity-marks target mark-type]))
             context {:request current :state state
                      :domain-state domain-state
                      :session-state (:state session)
                      :params (:parameter-snapshot session)
                      :tunables (tunables-fn ability-id session state)
                      :activation-seed (long (or (:activation-seed session) 0))
                      :context {:enabled? (boolean session)
                                :ability-id (:ability-id session)
                                :front? (boolean (get-in current [:metadata :attacker-front?]))
                                :resource (double (or (get-in state [:resources :cp]) 0.0))
                                :skill-exp (double (or (get-in state [:ability-data :skill-exps ability-id]) 0.0))
                                :depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                                :damage (double (:base current))
                                :max-cp (double (or (get-in state [:resources :max-cp]) 0.0))
                                :mark mark
                                :mark? (boolean mark)
                                :mark-rate (double (or (:rate mark) 1.0))
                                :owner target
                                :world-id (or (get-in current [:metadata :world-id])
                                              (:world-id state))}}
             condition? (or (nil? when) (boolean (value when context)))
             component (:component program)]
         (if-not (and (or session (= :passive activation)) condition?)
           current
           (if (= :damage/multiply component)
             (let [base (double (:base current))
                   multiplier (double (or (value (:multiplier program) context) 1.0))]
               (if (and (pos? base) (Double/isFinite multiplier)
                        (>= multiplier 0.0) (<= multiplier 16.0))
                 (let [scaled (* base multiplier)
                       ratio (if (pos? base) (/ scaled base) 0.0)]
                   (-> current
                       (assoc :base scaled)
                       (assoc :components (scale-components (:components current) ratio))
                       (assoc-in [:metadata :damage-multiplier]
                                 {:ability-id ability-id
                                  :multiplier multiplier
                                  :mark mark})))
                 current))
           (if (= :damage/reduce component)
             (let [base (double (:base current))
                   rate (double (or (value (:rate program) context) 0.0))
                   max-cost (double (or (value (:max-cost program) context) 0.0))
                   threshold (double (or (value (:ignore-threshold program) context)
                                         Double/POSITIVE_INFINITY))
                   cp (double (or (get-in state [:resources :cp]) 0.0))
                   cp-cost (min cp (max 0.0 max-cost))
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   valid? (and (Double/isFinite base) (pos? base)
                               (Double/isFinite rate) (<= 0.0 rate 1.0)
                               (Double/isFinite threshold) (<= base threshold))]
               (if-not valid?
                 current
                 (let [remaining (* base (- 1.0 rate))
                       ratio (if (pos? base) (/ remaining base) 0.0)
                       result (-> current
                                  (assoc :base remaining)
                                  (assoc :components (scale-components (:components current) ratio))
                                  (assoc-in [:metadata :resource-cost] {:cp (- cp-cost)})
                                  (assoc-in [:metadata :damage-reduction]
                                            {:rate rate :damage-ignore-threshold threshold})
                                  (update :state-patch (fnil into [])
                                          [[:resource :cp (- cp-cost)]
                                           [:ability-exp ability-id (* base exp-scale)]]))]
                   (if-let [vfx (:vfx program)]
                     (update result :vfx-signals (fnil conj [])
                             {:op :spawn :effect-id (:effect-id vfx :audio-one-shot)
                              :instance-key [ability-id :damage]
                              :owner target
                              :world-id (get-in context [:context :world-id])
                              :event-seq 0 :seed (long (or (:activation-seed context) 0))
                              :event :spawn
                              :params (merge {:position (or (get-in state [:position])
                                                             [0.0 0.0 0.0])}
                                              (dissoc vfx :effect-id))})
                     result))))
           (if (= :damage/absorb component)
             (let [base (double (:base current))
                   ticks (long (or (get-in session [:state :active-ticks]) 0))
                   last-tick (long (or (get-in session [:state (:last-tick-path program)]) -1))
                   interval (long (or (value (:interval-ticks program) context) 0))
                   cap (double (or (value (:cap program) context) 0.0))
                   front? (boolean (value (:front? program) context))
                   window? (and (pos? base)
                                (or (= -1 last-tick) (> (- ticks last-tick) interval)))
                   costs (into {} (map (fn [[r amount]]
                                         [r (double (or (value amount context) 0.0))])
                                       (:cost program)))
                   resources (or (:resources state) {})
                   enough? (every? (fn [[r amount]]
                                    (>= (double (or (get resources r) 0.0)) amount)) costs)
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   current (if window?
                             (update current :state-patch (fnil conj [])
                                     [:ability-exp ability-id exp-scale]) current)]
               (if (and window? front? enough? (Double/isFinite cap)
                        (<= 0.0 cap 100.0))
                 (let [absorbed (min base cap)
                       remaining (max 0.0 (- base absorbed))
                       defer? (and precheck? (pos? remaining))
                       ratio (if (pos? base) (/ remaining base) 0.0)]
                   (if defer?
                     current
                     (cond-> (-> current
                                 (assoc :base remaining)
                                 (assoc :components (scale-components (:components current) ratio))
                                 (assoc-in [:metadata :resource-cost]
                                           (into {} (map (fn [[r amount]] [r (- amount)]) costs)))
                                 (update :state-patch (fnil into [])
                                         (mapv (fn [[r amount]] [:resource r (- amount)]) costs))
                                 (update :session-patch (fnil conj [])
                                         {:path (:last-tick-path program) :mode :assign :value ticks}))
                       precheck? (assoc :cancelled? true))))
                 current))
             (let [multiplier (double (or (value (:multiplier program) context) 0.0))
                   cost-rate (double (or (value (:cost-per-damage program) context) 0.0))
                   minimum (double (or (value (:minimum program) context) 0.0))
                   max-depth (long (or (value (:max-depth program) context) 0))
                   exp-scale (double (or (value (:exp-scale program) context) 0.0))
                   depth (long (or (get-in current [:metadata :reflection-depth]) 0))
                   base (double (:base current))
                   reflected (* base multiplier (Math/pow 0.5 (double depth)))
                   cp (double (or (get-in state [:resources :cp]) 0.0))
                   consumption (max 0.0 (* base cost-rate))
                   source (:source current)
                   eligible? (and source (not= (str source) target)
                                  (Double/isFinite base) (pos? base)
                                  (Double/isFinite reflected) (>= reflected minimum)
                                  (< depth max-depth) (pos? consumption)
                                  (>= cp consumption))]
               (if-not eligible? current
                 (let [ratio (if (pos? base) (/ reflected base) 0.0)
                       reflected-request
                       (-> current
                           (assoc :source target :target source :base reflected)
                           (cond-> (vector? (:direction current))
                             (assoc :direction (mapv #(- (double %)) (:direction current))))
                           (assoc :components (scale-components (:components current) ratio))
                           (update :tags (fnil conj #{}) :reflected)
                           (update :metadata merge
                                   {:reflection-source? true
                                    :reflection-depth (inc depth)
                                    :reflection-ability ability-id}))]
                   (-> current
                       (assoc :base (max 0.0 (- base reflected)))
                       (assoc-in [:metadata :resource-cost] {:cp (- consumption)})
                       (update :state-patch (fnil conj [])
                               [:ability-exp ability-id (* base exp-scale)])
                       (update :world-effects (fnil conj [])
                               {:type :damage :request reflected-request})
                       (cond-> precheck? (assoc :cancelled? true))))))))))
     request reactions)))))
