(ns cn.li.mcmod.runtime.damage-boundary
  "Single neutral damage interception boundary shared by native events and
   graph combat/damage commands.")
(def ^:const schema-version 1)
(defonce ^:private resolver* (atom nil))
(defn install! [{:keys [begin complete] :as resolver}]
  (when-not (and (ifn? begin) (ifn? complete))
    (throw (ex-info "damage boundary requires begin and complete callbacks" {:resolver resolver})))
  (reset! resolver* resolver) resolver)
(defn clear! [] (reset! resolver* nil) nil)
(defn event [{:keys [world-id source target base type metadata hit-id depth] :as value}]
  (when-not (string? world-id) (throw (ex-info "damage event requires string :world-id" {:value value})))
  (when-not (some? source) (throw (ex-info "damage event requires :source" {:value value})))
  (when-not (some? target) (throw (ex-info "damage event requires :target" {:value value})))
  (when-not (and (number? base) (<= 0.0 (double base))) (throw (ex-info "damage event :base must be non-negative" {:value value})))
  (when-not (keyword? type) (throw (ex-info "damage event :type must be a keyword" {:value value})))
  {:schema-version schema-version :world-id world-id :source source :target target :base (double base) :type type :metadata (or metadata {}) :hit-id hit-id :depth (long (or depth 0))})
(defn begin! [value]
  (let [resolver @resolver* value (event value)]
    (when-not resolver (throw (ex-info "damage boundary is not installed" {})))
    (let [result ((:begin resolver) value)]
      (when-not (map? result) (throw (ex-info "damage resolver must return a map" {:event value :result result})))
      (assoc result :schema-version schema-version :event value))))
(defn complete! [{:keys [token event] :as resolution} applied? actual-amount]
  (let [resolver @resolver* result {:schema-version schema-version :token token :event event :applied? (boolean applied?) :actual-amount (double (or actual-amount 0.0))}]
    (when-not resolver (throw (ex-info "damage boundary is not installed" {})))
    ((:complete resolver) result)))
