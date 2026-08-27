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
  (and (or (= :combat/damage (:on policy)) (nil? (:on policy)))
       (matches? policy event)
       (if-let [predicate (:when policy)]
         (boolean (eval-value predicate event))
         true)
       (if-let [mark-type (:mark-type policy)]
         (= mark-type (get-in event [:metadata :context :mark-type]))
         true)))

(defn- policy-event
  "Select the immutable input snapshot belonging to one policy."
  [event policy]
  (let [metadata (:metadata event)
        inputs (or (:inputs metadata) {})
        input (or (get inputs (:ability-id policy)) (:input metadata) {})]
    (assoc event :metadata (assoc metadata :input input))))

(defn materialize-vfx
  "Resolve a policy VFX descriptor against its immutable input event."
  [descriptor event]
  (letfn [(walk [value]
            (cond
              (and (map? value) (:ref value)) (eval-value value event)
              (map? value) (into {} (map (fn [[k v]] [k (walk v)]) value))
              (sequential? value) (mapv walk value)
              :else value))]
    (when (map? descriptor)
      (cond-> descriptor
        (contains? descriptor :payload)
        (assoc :payload (walk (:payload descriptor)))))))
(defn- program-contributions [program event]
  (let [value #(double (or (eval-value % event) 0.0))]
    (case (:component program)
      :damage/multiply [{:kind :multiplier :value (value (:multiplier program))}]
      :damage/reduce [{:kind :reduction :value (value (:rate program))}]
      :damage/absorb [{:kind :absorption :value (value (:cap program))
                       :cost (:cost program) :vfx (:vfx program)
                       :events (:events program) :input (:input (:metadata event))}]
      :damage/cancel [{:kind :cancel}]
      :damage/reflect [{:kind :reflection :ratio (value (:multiplier program))
                        :minimum (value (:minimum program))
                        :max-depth (long (or (value (:max-depth program)) max-reflection-depth))
                        :cost-per-damage (value (:cost-per-damage program))
                        :exp-scale (value (:exp-scale program))
                        :vfx (:vfx program) :events (:events program) :input (:input (:metadata event))}]
      :damage/critical (mapv (fn [{:keys [probability multiplier]}]
                               {:kind :critical
                                :probability (value probability)
                                :multiplier (value multiplier)
                                :vfx (:vfx program)
                                :feedback (:feedback program)
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
        contributions (mapcat (fn [reaction]
                                (let [pe (policy-event event reaction)]
                                  (map #(assoc % :ability-id (:ability-id reaction)
                                                 :reaction-id (:reaction-id reaction))
                                       (concat (or (:contributions reaction) [])
                                               (program-contributions (:program reaction) pe)))))
                              matched)
        multiplier (reduce * 1.0 (map #(double (or (:value %) 1.0)) (filter #(= :multiplier (:kind %)) contributions)))
        reduction (min 1.0 (max 0.0 (reduce + 0.0 (map #(double (or (:value %) 0.0)) (filter #(= :reduction (:kind %)) contributions)))))
        before (* (:base event) multiplier (- 1.0 reduction))
        absorption (min before (max 0.0 (reduce + 0.0 (map #(double (or (:value %) 0.0)) (filter #(= :absorption (:kind %)) contributions)))))
        criticals (filter #(= :critical (:kind %)) contributions)
        critical-probability (min 1.0 (max 0.0 (reduce + 0.0 (map #(double (or (:probability %) 0.0)) criticals))))
        critical? (and (pos? critical-probability) (deterministic-roll (:seed event) critical-probability))
        critical-level (when critical? (first (sort-by (comp - :multiplier) criticals)))
        amount (max 0.0 (* (- before absorption)
                         (if critical? (double (or (:multiplier critical-level) 1.0)) 1.0)))
        reflections (->> contributions (filter #(= :reflection (:kind %)))
                       (keep (fn [reflection]
                               (when (< (:depth event)
                                        (min max-reflection-depth
                                             (long (or (:max-depth reflection)
                                                       max-reflection-depth))))
                                 (assoc event :source (:target event) :target (:source event)
                                        :base (* amount (double (or (:ratio reflection) 1.0)))
                                        :depth (inc (:depth event))
                                        :metadata (assoc (:metadata event)
                                                         :reflected? true
                                                         :reflection reflection))))) vec)]
    {:event event :matched (mapv #(select-keys % [:ability-id :reaction-id]) matched)
     :amount amount :cancelled? (boolean (some #(= :cancel (:kind %)) contributions))
     :critical? critical? :critical-level critical-level :reflections reflections
     :vfx (vec (keep :vfx (filter #(or (and (= :critical (:kind %)) critical?)
                                      (= :reflection (:kind %))
                                      (= :absorption (:kind %))) contributions)))
     :feedback (vec (keep :feedback (filter #(and (= :critical (:kind %)) critical?) contributions)))
     :side-events (vec (mapcat #(or (:events %) [])
                               (filter #(or (and (= :critical (:kind %)) critical?)
                                          (= :reflection (:kind %))
                                          (= :absorption (:kind %))) contributions)))
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
