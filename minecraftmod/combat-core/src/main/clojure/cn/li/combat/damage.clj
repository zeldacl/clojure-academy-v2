(ns cn.li.combat.damage
  "Damage resolution, additive alongside the old cn.li.combat.final-damage
   (unchanged, still live via cn.li.combat.api -- see the redesign plan's
   staging notes).

   final_damage.clj's resolve-event is NOT a sequential program that fits
   cn.li.node.compile's execution model: it is an AGGREGATION over every
   policy matching one damage event, combining their contributions
   (multiply/reduce/absorb/critical-roll/reflect) with resource-cost
   gating, absorption interval cooldowns, and reflection recursion up to
   max-reflection-depth. That is fundamentally a map-reduce over policy
   outputs, not a single compiled IR program -- porting the arithmetic
   itself through the new VM would risk subtly changing intricate,
   game-balance-critical behavior (critical-roll determinism, reflection
   depth limits, cost gating) without the domain testing to safely catch a
   regression here.

   So: the aggregation math below (event/matches?/lookup-path/eval-value/
   policy-matches?/policy-event/materialize-value/materialize-vfx/
   program-contributions/deterministic-roll/resolve-event's own body) is
   ported ESSENTIALLY VERBATIM from final_damage.clj -- same scopes, same
   opcode table, same resolution order, same numbers. What is actually NEW
   here is the dispatch layer final_damage.clj's `collect` did as an O(n)
   linear scan+filter over every registered policy for every damage event:
   build-index/candidates-for replace that with an O(k) mark-type+priority
   lookup, k = policies actually relevant to this event's mark-type (plus
   the always-checked no-mark-type bucket), still running the SAME
   policy-matches? check per candidate afterward."
  (:require [cn.li.mcmod.runtime.damage-boundary :as boundary]))

(def ^:const max-reflection-depth 8)

(defn event [{:keys [world-id source target base type seed depth metadata] :as value}]
  (when-not (and world-id source target (number? base) (keyword? type))
    (throw (ex-info "invalid final damage event" {:value value})))
  {:world-id world-id :source source :target target :base (double base) :type type
   :seed (long (or seed 0)) :depth (long (or depth 0)) :metadata (or metadata {})})

(defn- matches? [reaction ev]
  (let [match (:match reaction)]
    (and (or (nil? (:types match)) (contains? (set (:types match)) (:type ev)))
         (or (nil? (:target match)) (= (:target match) (:target ev)))
         (or (nil? (:source match)) (= (:source match) (:source ev))))))

(defn- lookup-path [value path]
  (reduce (fn [current key]
            (cond
              (map? current) (get current key)
              (vector? current) (get current (long key))
              (sequential? current) (nth (vec current) (long key) nil)
              :else nil)) value path))

(declare eval-value)
(defn- eval-value [value ev]
  (cond
    (and (map? value) (:ref value))
    (let [[scope & path] (:ref value)]
      (lookup-path
       (case scope
         :request ev
         :context (or (:context (:metadata ev)) {})
         :param (or (:params (:metadata ev)) {})
         :session (or (:session (:metadata ev)) {})
         :slot (or (:slot (:metadata ev)) {})
         :mark (or (:mark (:metadata ev)) {})
         :input (or (:input (:metadata ev)) {})
         nil)
       path))

    (and (map? value) (:tunable value))
    (lookup-path (or (:tunables (:metadata ev)) {})
                 (into [(:tunable value)] (or (:path value) [])))

    (and (map? value) (:expr value))
    (let [op (:expr value) args (map #(eval-value % ev) (:args value))]
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

(defn- policy-matches? [policy ev]
  (let [damage-types (or (:damage-types policy)
                         (get-in policy [:program :damage-types]))]
    (and (or (= :combat/damage (:on policy)) (nil? (:on policy)))
       (matches? policy ev)
       (or (nil? damage-types)
           (contains? (set damage-types) (:type ev)))
       (if-let [predicate (:when policy)]
         (boolean (eval-value predicate ev))
         true)
       (if-let [mark-type (:mark-type policy)]
         (= mark-type (get-in ev [:metadata :input :context :mark-type]))
         true))))

(defn- policy-event [ev policy]
  (let [metadata (:metadata ev)
        inputs (or (:inputs metadata) {})
        input (or (get inputs (:ability-id policy)) (:input metadata) {})]
    (assoc ev :metadata (assoc metadata :input input))))

(defn materialize-value [value ev]
  (letfn [(walk [value]
            (cond
              (and (map? value) (:ref value)) (eval-value value ev)
              (map? value) (into {} (map (fn [[k v]] [k (walk v)]) value))
              (sequential? value) (mapv walk value)
              :else value))]
    (walk value)))

(defn materialize-vfx [descriptor ev]
  (when (map? descriptor)
    (cond-> descriptor
      (contains? descriptor :payload)
      (assoc :payload (materialize-value (:payload descriptor) ev)))))

(defn- program-contributions [program ev]
  (let [value #(double (or (eval-value % ev) 0.0))
        cost-map (fn [cost] (into {} (map (fn [[resource amount]] [resource (value amount)]) (or cost {}))))]
    (case (:component program)
      :damage/multiply [{:kind :multiplier :value (value (:multiplier program))
                       :owner (:owner-id (get-in ev [:metadata :input :context]))}]
      :damage/reduce [{:kind :reduction :value (value (:rate program))
                       :ignore-threshold (value (:ignore-threshold program))
                       :max-cost (value (:max-cost program)) :vfx (:vfx program)
                       :owner (:owner-id (get-in ev [:metadata :input :context]))
                       :cost-resource (:cost-resource program)
                       :progression-tag (:progression-tag program)
                       :progression-amount (value (:progression-scale program))
                       :progression-eligible? (not (and (some? (:ignore-threshold program))
                                                   (> (double (:base ev))
                                                      (value (:ignore-threshold program)))))
                       :events (:events program) :input (:input (:metadata ev))}]
      :damage/absorb [{:kind :absorption :value (value (:cap program))
                       :requires-payment true
                       :cost (cost-map (:cost program)) :vfx (:vfx program)
                       :interval-ticks (value (:interval-ticks program))
                       :last-tick-path (:last-tick-path program) :front? (when (contains? program :front?)
                                 (boolean (eval-value (:front? program) ev)))
                       :owner (:owner-id (get-in ev [:metadata :input :context]))
                       :progression-tag (:progression-tag program)
                       :progression-amount (value (:progression-scale program))
                       :events (:events program) :input (:input (:metadata ev))}]
      :damage/cancel [{:kind :cancel}]
      :damage/reflect [{:kind :reflection :ratio (value (:multiplier program))
                        :minimum (value (:minimum program))
                        :max-depth (long (or (value (:max-depth program)) max-reflection-depth))
                        :cost-per-damage (value (:cost-per-damage program))
                        :owner (:owner-id (get-in ev [:metadata :input :context]))
                        :cost-resource (:cost-resource program)
                        :progression-tag (:progression-tag program)
                        :progression-amount (value (:progression-scale program))
                        :progression-eligible? (and (not= :environment (:source ev))
                                             (< (long (:depth ev))
                                                (min max-reflection-depth
                                                     (long (or (value (:max-depth program))
                                                               max-reflection-depth)))))
                        :vfx (:vfx program) :events (:events program) :input (:input (:metadata ev))}]
      :damage/critical (mapv (fn [{:keys [level probability multiplier]}]
                               {:kind :critical
                                :level (long (or level 0))
                                :probability (value probability)
                                :multiplier (value multiplier)
                                :vfx (:vfx program)
                                :feedback (:feedback program)
                                :owner (:owner-id (get-in ev [:metadata :input :context]))
                                :events (:events program) :input (:input (:metadata ev))})
                             (:levels program))
      [])))

(defn- deterministic-roll [seed probability]
  (< (double (mod (Math/abs (long (hash [seed probability]))) 1000000)) (* 1000000.0 (double probability))))

;; --- dispatch layer: mark-type + priority index (the actually new part) ----

(defn- priority-key [policy] [(long (or (:priority policy) 0)) (str (:ability-id policy)) (str (:reaction-id policy))])

(defn build-index
  "policies -> {:by-mark-type {mark-type [sorted-policies]} :universal
   [sorted-policies]}. universal holds every policy with no :mark-type --
   policy-matches? treats a missing :mark-type as \"always eligible\" (see
   its last clause above), so those must be checked against every event
   regardless of the event's own mark-type, exactly like the old collect's
   full scan did."
  [policies]
  (let [sorted (sort-by priority-key policies)
        universal (filterv #(nil? (:mark-type %)) sorted)
        by-mark-type (->> sorted
                          (remove #(nil? (:mark-type %)))
                          (group-by :mark-type)
                          (into {} (map (fn [[k v]] [k (vec (sort-by priority-key v))]))))]
    {:by-mark-type by-mark-type :universal universal}))

(defn candidates-for
  "The narrowed candidate set for `ev`'s mark-type, still in priority
   order -- policy-matches? (types/target/source/:when) still runs on each
   below; this only avoids scanning policies for OTHER mark-types."
  [index ev]
  (let [mark-type (get-in ev [:metadata :input :context :mark-type])]
    (vec (concat (get (:by-mark-type index) mark-type []) (:universal index)))))

(defn collect
  "candidates-for narrowed by the full policy-matches? check, in priority
   order -- direct replacement for final_damage.clj's collect, now O(k)
   over an index instead of O(n) over every registered policy."
  [index ev]
  (filterv #(policy-matches? % (policy-event ev %)) (candidates-for index ev)))

(defn resolve-event
  "index: build-index's output. Same algorithm/field names/resolution
   order as final_damage.clj's resolve-event -- see this namespace's
   docstring for why that arithmetic is a verbatim port, not a
   reimplementation."
  [index raw-event]
  (let [ev (event raw-event) matched (collect index ev)
        raw-contributions (mapcat (fn [reaction]
                                    (let [pe (policy-event ev reaction)]
                                      (map #(assoc % :ability-id (:ability-id reaction)
                                                     :reaction-id (:reaction-id reaction))
                                           (concat (or (:contributions reaction) [])
                                                   (program-contributions (:program reaction) pe)))))
                                  matched)
        progression-events (vec (keep (fn [contribution]
                                        (when (and (:progression-tag contribution)
                                                   (number? (:progression-amount contribution))
                                                   (or (= :absorption (:kind contribution))
                                                       (and (contains? #{:reduction :reflection} (:kind contribution))
                                                            (not= false (:progression-eligible? contribution)))))
                                          {:type :score/mark
                                           :tag (:progression-tag contribution)
                                           :progression (:progression-amount contribution)
                                           :owner (:owner contribution)
                                           :ability-id (:ability-id contribution)}))
                                      raw-contributions))
        contributions (vec (remove (fn [contribution]
                                     (or (and (:requires-payment contribution)
                                              (let [resources (get-in contribution [:input :context :resources] {})]
                                                (some (fn [[resource amount]]
                                                        (> (double (or amount 0.0))
                                                           (double (or (get resources resource) 0.0))))
                                                      (:cost contribution))))
                                         (and (= :absorption (:kind contribution))
                                              (false? (:front? contribution)))
                                         (and (= :absorption (:kind contribution))
                                              (pos? (double (or (:interval-ticks contribution) 0.0)))
                                              (let [session (or (get-in contribution [:input :session]) {})
                                                    last-tick (lookup-path session (or (:last-tick-path contribution) []))
                                                    elapsed (- (long (:seed ev)) (long (or last-tick Long/MIN_VALUE)))]
                                                (< elapsed (long (:interval-ticks contribution)))))))
                                   raw-contributions))
        multiplier (reduce * 1.0 (map #(double (or (:value %) 1.0)) (filter #(= :multiplier (:kind %)) contributions)))
        reduction (min 1.0 (max 0.0 (reduce + 0.0 (map (fn [contribution] (let [threshold (:ignore-threshold contribution) ignored? (and (some? threshold) (> (double (:base ev)) (double threshold)))] (if ignored? 0.0 (double (or (:value contribution) 0.0))))) (filter #(= :reduction (:kind %)) contributions)))))
        before (* (:base ev) multiplier (- 1.0 reduction))
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
                                         (deterministic-roll (+ (long (:seed ev)) (long level)) probability))
                                level-data))
                            critical-levels)
        critical? (boolean critical-level)
        amount-before-reflect (max 0.0 (* (- before absorption)
                         (if critical? (double (or (:multiplier critical-level) 1.0)) 1.0)))
        reflections (->> contributions (filter #(= :reflection (:kind %)))
                       (keep (fn [reflection]
                               (when (and (not= :environment (:source ev))
                                          (< (:depth ev)
                                             (min max-reflection-depth
                                                  (long (or (:max-depth reflection)
                                                            max-reflection-depth)))))
                                 (assoc ev :source (:target ev) :target (:source ev)
                                        :base (* amount-before-reflect (double (or (:ratio reflection) 1.0)))
                                        :minimum (double (or (:minimum reflection) 0.0))
                                        :depth (inc (:depth ev))
                                        :metadata (assoc (:metadata ev)
                                                         :reflected? true
                                                         :reflection reflection))))) vec)
        reflection-total (reduce + 0.0 (map #(double (or (:base %) 0.0)) reflections))
        amount (max 0.0 (- amount-before-reflect reflection-total))
        absorb-cost (if (pos? absorption)
                      (reduce (fn [acc contribution]
                                (merge-with + acc (:cost contribution)))
                              {} (filter #(= :absorption (:kind %)) contributions))
                      {})
        cost-resource! (fn [component contribution]
                         (or (:cost-resource contribution)
                             (throw (ex-info "damage policy requires :cost-resource"
                                             {:component component
                                              :ability-id (:ability-id contribution)
                                              :reaction-id (:reaction-id contribution)}))))
        reduction-costs (keep (fn [contribution]
                                (let [threshold (:ignore-threshold contribution)
                                      ignored? (and (some? threshold) (> (double (:base ev)) (double threshold)))
                                      cost (if ignored? 0.0
                                             (min (max 0.0 (double (or (:max-cost contribution) 0.0)))
                                                  (* (double (:base ev)) (double (or (:value contribution) 0.0)))))]
                                  (when (pos? cost)
                                    [(cost-resource! :damage/reduce contribution) cost])))
                              (filter #(= :reduction (:kind %)) contributions))
        reflection-costs (keep (fn [contribution]
                                 (let [cost (* amount-before-reflect (double (or (:ratio contribution) 0.0))
                                              (double (or (:cost-per-damage contribution) 0.0)))]
                                   (when (pos? cost)
                                     [(cost-resource! :damage/reflect contribution) cost])))
                               (filter #(= :reflection (:kind %)) contributions))
        resource-costs (reduce (fn [acc [resource cost]] (update acc resource (fnil + 0.0) cost))
                               absorb-cost (concat reduction-costs reflection-costs))]
    {:event ev :matched (mapv #(select-keys % [:ability-id :reaction-id]) matched)
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
                                  (assoc ev :metadata {:input input}))))
                             (filter #(and (= :critical (:kind %)) critical? (= (:level %) (:level critical-level))) contributions))))
     :side-events (vec (distinct (concat progression-events
                                         (mapcat #(or (:events %) [])
                                          (filter #(or (and (= :critical (:kind %)) critical?
                                                            (= (:level %) (:level critical-level)))
                                                       (= :reflection (:kind %))
                                                       (or (= :absorption (:kind %)) (= :reduction (:kind %)))) contributions)))))
     :state-patches (vec (mapcat :state-patches matched))
     :session-patches (vec (for [contribution (filter #(and (= :absorption (:kind %))
                                                              (pos? absorption)
                                                              (seq (:last-tick-path %)))
                                                        contributions)]
                              {:path (:last-tick-path contribution)
                               :mode :assign
                               :value (long (:seed ev))}))
     :events (vec (mapcat :events matched))}))

(defn install-boundary!
  "Connect the pure resolver to mcmod's begin/complete SPI, same contract
   as final_damage.clj's install-boundary! -- takes an already-built index
   instead of a raw reaction collection."
  [{:keys [index state-provider commit-state!]}]
  (boundary/install!
   {:begin (fn [raw]
             (let [result (resolve-event index raw)]
               {:token result :amount (:amount result) :cancelled? (:cancelled? result)}))
    :complete (fn [{:keys [token applied? actual-amount]}]
                (when (and applied? (ifn? commit-state!))
                  (commit-state! {:event (:event token) :amount actual-amount
                                  :state-patches (:state-patches token) :events (:events token)}))
                {:applied? applied? :amount actual-amount})}))
