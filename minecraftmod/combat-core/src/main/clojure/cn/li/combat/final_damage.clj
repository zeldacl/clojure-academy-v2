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
(defn collect [reactions event]
  (->> reactions (filter #(matches? % event))
       (sort-by (juxt #(long (or (:priority %) 0)) #(str (:ability-id %)) #(str (:reaction-id %))))
       vec))
(defn- deterministic-roll [seed probability]
  (< (double (mod (Math/abs (long (hash [seed probability]))) 1000000)) (* 1000000.0 (double probability))))
(defn resolve-event [reactions raw-event]
  (let [event (event raw-event) matched (collect reactions event)
        contributions (mapcat #(or (:contributions %) []) matched)
        multiplier (reduce * 1.0 (map #(double (or (:value %) 1.0)) (filter #(= :multiplier (:kind %)) contributions)))
        reduction (min 1.0 (max 0.0 (reduce + 0.0 (map #(double (or (:value %) 0.0)) (filter #(= :reduction (:kind %)) contributions)))))
        before (* (:base event) multiplier (- 1.0 reduction))
        absorption (min before (max 0.0 (reduce + 0.0 (map #(double (or (:value %) 0.0)) (filter #(= :absorption (:kind %)) contributions)))))
        criticals (filter #(= :critical (:kind %)) contributions)
        critical-probability (min 1.0 (max 0.0 (reduce + 0.0 (map #(double (or (:probability %) 0.0)) criticals))))
        critical? (and (pos? critical-probability) (deterministic-roll (:seed event) critical-probability))
        amount (max 0.0 (* (- before absorption) (if critical? (double (or (some :multiplier criticals) 1.0)) 1.0)))
        reflections (->> contributions (filter #(= :reflection (:kind %)))
                       (keep (fn [reflection]
                               (when (< (:depth event) max-reflection-depth)
                                 (assoc event :source (:target event) :target (:source event)
                                        :base (* amount (double (or (:ratio reflection) 1.0)))
                                        :depth (inc (:depth event))
                                        :metadata (assoc (:metadata event) :reflected? true))))) vec)]
    {:event event :matched (mapv #(select-keys % [:ability-id :reaction-id]) matched)
     :amount amount :cancelled? (boolean (some #(= :cancel (:kind %)) contributions))
     :critical? critical? :reflections reflections
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
