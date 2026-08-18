(ns cn.li.combat.vm
  "Allocation-conscious interpreter for the private combat IR."
  (:require [cn.li.mcmod.runtime.expr :as expr]
            [cn.li.mcmod.runtime.seeded-rng :as rng]
            [cn.li.mcmod.runtime.effect-contract :as effect-contract])
  (:import [cn.li.mcmod.runtime.effect CompiledProgram ExecutionFrame HostTable]
           [clojure.lang IFn]
           [java.util ArrayList]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)
(def ^:const operand-stride 4)

(defn- finish-result [^ExecutionFrame frame ^long outcome-index finish-session?]
  {:status :finished :outcome outcome-index :finish-session? finish-session?
   :actions (.-actions frame) :vfx (.-vfx frame) :events (.-events frame)})

(defn- reject-result [^ExecutionFrame frame ^long reason-index]
  {:status :rejected :reason reason-index
   :actions (.-actions frame) :vfx (.-vfx frame) :events (.-events frame)})

(defn- vec3-components [value]
  (cond
    (and (map? value) (vector? (:vec3 value))) (:vec3 value)
    (vector? value) value
    :else (throw (ex-info "expected vec3 expression value" {:value value}))))

(defn evaluate-expression
  ([opcode args] (evaluate-expression opcode args 0))
  ([opcode args ^long rng-state]
  (case opcode
    :math/add (expr/add (double (nth args 0)) (double (nth args 1)))
    :math/sub (expr/sub (double (nth args 0)) (double (nth args 1)))
    :math/mul (expr/mul (double (nth args 0)) (double (nth args 1)))
    :math/div (expr/div (double (nth args 0)) (double (nth args 1)))
    :math/min (min (double (nth args 0)) (double (nth args 1)))
    :math/max (max (double (nth args 0)) (double (nth args 1)))
    :math/abs (Math/abs (double (nth args 0)))
    :math/sqrt (Math/sqrt (double (nth args 0)))
    :math/sin (Math/sin (double (nth args 0)))
    :math/cos (Math/cos (double (nth args 0)))
    :math/clamp (expr/clamp (double (nth args 0)) (double (nth args 1)) (double (nth args 2)))
    :math/lerp (expr/lerp (double (nth args 0)) (double (nth args 1)) (double (nth args 2)))
    :math/lt (< (double (nth args 0)) (double (nth args 1)))
    :math/lte (<= (double (nth args 0)) (double (nth args 1)))
    :math/eq (= (double (nth args 0)) (double (nth args 1)))
    :math/gte (>= (double (nth args 0)) (double (nth args 1)))
    :math/gt (> (double (nth args 0)) (double (nth args 1)))
    :bool/and (and (boolean (nth args 0)) (boolean (nth args 1)))
    :bool/or (or (boolean (nth args 0)) (boolean (nth args 1)))
    :bool/not (not (boolean (nth args 0)))
    :vec3/dot (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                (expr/vec3-dot ax ay az bx by bz))
    :vec3/distance (let [[ax ay az] (vec3-components (nth args 0))
                         [bx by bz] (vec3-components (nth args 1))]
                     (expr/vec3-distance ax ay az bx by bz))
    :vec3/add (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                {:vec3 [(+ (double ax) (double bx))
                        (+ (double ay) (double by))
                        (+ (double az) (double bz))]})
    :vec3/sub (let [[ax ay az] (vec3-components (nth args 0))
                    [bx by bz] (vec3-components (nth args 1))]
                {:vec3 [(- (double ax) (double bx))
                        (- (double ay) (double by))
                        (- (double az) (double bz))]})
    :vec3/scale (let [[ax ay az] (vec3-components (nth args 0))
                      scale (double (nth args 1))]
                  {:vec3 [(* (double ax) scale)
                          (* (double ay) scale)
                          (* (double az) scale)]})
    :vec3/length (let [[x y z] (vec3-components (nth args 0))]
                   (Math/sqrt (+ (* (double x) (double x))
                                 (* (double y) (double y))
                                 (* (double z) (double z)))))
    :vec3/normalize (let [[x y z] (vec3-components (nth args 0))
                          length (Math/sqrt (+ (* (double x) (double x))
                                               (* (double y) (double y))
                                               (* (double z) (double z))))]
                      (if (zero? length)
                        {:vec3 [0.0 0.0 0.0]}
                        {:vec3 [(/ (double x) length)
                                (/ (double y) length)
                                (/ (double z) length)]}))
    :random/uniform (rng/uniform rng-state (double (nth args 0)) (double (nth args 1)))
    :random/int (rng/bounded-int rng-state (long (nth args 0)) (long (nth args 1)))
    :random/chance (< (double (rng/unit-double rng-state)) (double (nth args 0)))
    (throw (ex-info "unsupported expression opcode" {:opcode opcode})))))

(declare eval-node-value execute-component!)

(def ^:private query-capability-by-component
  {:target/raycast :raycast
   :target/entities :entity/select
   :target/blocks :block/select})

(def ^:private action-capability-by-component
  {:inventory/consume :inventory/consume
   :combat/damage :entity/damage
   :combat/impulse :entity/impulse
   :combat/status :entity/status
   :entity/spawn :entity/spawn
    :entity/discard :entity/discard
    :block/break-budget :block/break
    :world/sound :world/sound})

(defn- append-object! [^ArrayList output value]
  (.add output value)
  nil)

(defn- emit-component! [^ExecutionFrame frame component data]
  (case component
    :effect/vfx (append-object! (.-vfx frame)
                                (effect-contract/vfx-signal
                                  {:effect-id (:effect-id data)
                                   :operation (or (:operation data) :spawn)
                                   :payload (:payload data)}))
    :domain/event (append-object! (.-events frame)
                                  {:event-type (:event-type data)
                                   :payload (:payload data)})
    :session/patch (append-object! (.-actions frame)
                                   {:type :session-patch
                                    :entries (:entries data)})
    :owner/patch (append-object! (.-actions frame)
                                 {:type :owner-patch
                                  :entries (:entries data)})
    (when-let [capability (get action-capability-by-component component)]
      (append-object! (.-actions frame)
                      (effect-contract/action-request
                        (assoc (dissoc data :component)
                               :capability capability
                               :world-id (str (or (:world-id data) "unknown"))))))))

(defn- resolve-data [value context]
  (cond
    (and (map? value) (:expr value) (contains? value :args))
    (eval-node-value value context)
    (and (map? value) (:ref value))
    (eval-node-value value context)
    (map? value)
    (reduce-kv (fn [result key nested]
                 (assoc result key (resolve-data nested context)))
               (empty value) value)
    (vector? value) (mapv #(resolve-data % context) value)
    :else value))

(defn- invoke-query-component!
  [^ExecutionFrame frame ^HostTable host component data context]
  (let [capability (get query-capability-by-component component)
        order (:query-order context)
        capability-index (when (and capability order)
                           (.indexOf ^java.util.List order capability))
        ^objects handlers (when host (.-queryHandlers host))
        ^IFn handler (when (and handlers (<= 0 (long (or capability-index -1)))
                                (< (long capability-index) (alength handlers)))
                       (aget handlers capability-index))
        request (effect-contract/query-request
                  (resolve-data
                    (merge {:capability capability
                            :world-id (str (or (get-in context [:context :world-id]) "unknown"))}
                           (dissoc data :result :component))
                    context))
        result (when handler (.invoke handler request frame))]
    (when-let [results* (:results* context)]
      (vswap! results* assoc (:result data) result))
    (when-let [slots* (:slots* context)]
      (vswap! slots* assoc (:result data) result))
    nil))

(defn- eval-node-value [value context]
  (if (and (map? value) (:expr value))
    (evaluate-expression (:expr value)
                         (mapv #(eval-node-value % context) (:args value))
                         (long (or (:activation-seed context) 0)))
    (if (and (map? value) (:ref value))
      (let [[scope key & path] (:ref value)]
        (get-in (case scope
                  :context (:context context)
                  :params (:params context)
                  :slot (or (when-let [slots* (:slots* context)] @slots*)
                            (:slots context))
                  :input (:input context)
                  {}) (into [key] path)))
      value)))

(defn- execute-component!
  [^ExecutionFrame frame ^HostTable host component data context]
  (case component
    :target/raycast (invoke-query-component! frame host component data context)
    :target/entities (invoke-query-component! frame host component data context)
    :target/blocks (invoke-query-component! frame host component data context)
    :flow/phases
    (let [phase (or (:phase context) :start)
          child (if (= phase :events)
                  (get-in data [:events (:event context)])
                  (get data phase))]
      (when child
        (execute-component! frame host (:component child)
                            (dissoc child :component) context)))
    :flow/sequence
    (loop [steps (seq (:steps data))]
      (when-let [child (first steps)]
        (let [result (execute-component! frame host (:component child)
                                         (dissoc child :component) context)]
          (if result
            result
            (recur (next steps))))))
    :flow/branch
    (let [condition (boolean (eval-node-value (:when data) context))
          child (if condition (:then data) (:else data))]
      (when child
        (execute-component! frame host (:component child)
                            (dissoc child :component) context)))
    :flow/foreach
    (let [items (let [value (or (resolve-data (:items data) context) [])]
                  (if (vector? value) value (vec value)))
          limit (min (count items) (max 0 (long (:limit data))))]
      (loop [index 0]
        (when (< index limit)
          (let [item (nth items index)]
          (when-let [slots* (:slots* context)]
            (vswap! slots* assoc (:as data) item))
          (let [result (execute-component! frame host
                                           (:component (:body data))
                                           (dissoc (:body data) :component)
                                           context)]
            (if result
              result
              (recur (inc index))))))))
    :flow/window
    (let [value (double (resolve-data (:value data) context))
          pass? (and (> value (double (:min-exclusive data)))
                     (<= value (double (:max-inclusive data))))
          child (if pass? (:on-pass data) (:on-fail data))]
      (execute-component! frame host (:component child)
                          (dissoc child :component) context))
    :flow/finish {:status :finished :outcome (:outcome data)
                  :finish-session? (boolean (:finish-session? data))}
    :flow/once (let [key (resolve-data (:key data) context)
                     latches* (:latches* context)
                     seen? (and latches* (contains? @latches* key))]
                 (if seen?
                   (when-let [child (:on-duplicate data)]
                     (execute-component! frame host (:component child)
                                         (dissoc child :component) context))
                   (do
                     (when latches* (vswap! latches* conj key))
                     (when-let [child (:on-first data)]
                       (execute-component! frame host (:component child)
                                           (dissoc child :component) context)))))
    :data/bind
    (do
      (when-let [slots* (:slots* context)]
        (vswap! slots* assoc (:to data) (resolve-data (:value data) context)))
      nil)
    :guard/resource
    (let [resources (or (get-in context [:context :resources]) {})
          cost (:cost data)]
      (every? (fn [[key value]]
                (>= (double (or (get resources key) 0.0))
                    (double (resolve-data value context))))
              cost))
    :guard/held-item
    (let [held (get-in context [:context :held-item])]
      (contains? (set (:item-ids data)) held))
    :txn/atomic
    (let [guards (every? (fn [guard]
                           (boolean
                             (execute-component! frame host
                                                  (:component guard)
                                                  (dissoc guard :component)
                                                  context)))
                         (:guards data))]
      (if guards
        (do
          (doseq [reservation (:reservations data)]
            (execute-component! frame host (:component reservation)
                                (dissoc reservation :component) context))
          (when-let [body (:body data)]
            (execute-component! frame host (:component body)
                                (dissoc body :component) context))
          (when-let [on-success (:on-success data)]
            (execute-component! frame host (:component on-success)
                                (dissoc on-success :component) context)))
        (when-let [on-fail (:on-fail data)]
          (execute-component! frame host (:component on-fail)
                              (dissoc on-fail :component) context))))
    (emit-component! frame component (resolve-data data context))))

(defn- query! [^CompiledProgram program ^ExecutionFrame frame ^HostTable host
              capability-id request-index result-slot]
  (let [^objects constants (.-objectConstants program)
        request (aget constants request-index)
        ^objects objects (.-objects frame)]
    (when host
      (let [^objects handlers (.-queryHandlers host)
            ^IFn handler (aget handlers capability-id)]
        (aset objects result-slot (.invoke handler request frame))))
    nil))

(defn execute!
  ([^CompiledProgram program ^ExecutionFrame frame ^HostTable host entry]
   (execute! program frame host entry {}))
  ([^CompiledProgram program ^ExecutionFrame frame ^HostTable host entry context]
   (let [^ints opcodes (.-opcodes program)
         ^ints operands (.-operands program)
         ^doubles constants (.-doubleConstants program)
         ^longs long-constants (.-longConstants program)
         ^objects object-constants (.-objectConstants program)
         ^doubles doubles (.-doubles frame)
         ^longs longs (.-longs frame)
         ^booleans booleans (.-booleans frame)
         ^objects objects (.-objects frame)]
     (loop [pc (long entry)]
       (let [base (* pc operand-stride)]
         (case (aget opcodes pc)
           1 (do (aset-double doubles (aget operands base)
                              (aget constants (aget operands (inc base))))
                 (recur (inc pc)))
           2 (do (aset-long longs (aget operands base)
                            (aget long-constants (aget operands (inc base))))
                 (recur (inc pc)))
           3 (do (aset-boolean booleans (aget operands base)
                               (not (zero? (aget operands (inc base)))))
                 (recur (inc pc)))
           4 (do (aset objects (aget operands base)
                         (aget object-constants (aget operands (inc base))))
                 (recur (inc pc)))
           5 (do (aset-double doubles (aget operands base)
                              (double (get-in context [:params (aget operands (inc base))] 0.0)))
                 (recur (inc pc)))
           6 (do (aset objects (aget operands base)
                         (get-in context [:context (aget operands (inc base))]))
                 (recur (inc pc)))
           7 (do (aset-double doubles (aget operands base)
                              (aget doubles (aget operands (inc base))))
                 (recur (inc pc)))
           8 (let [dst (aget operands base)
                   opcode (aget object-constants (aget operands (inc base)))
                   args [(aget doubles (aget operands (+ base 2)))
                         (aget doubles (aget operands (+ base 3)))]]
               (aset-double doubles dst (double (evaluate-expression opcode args)))
               (recur (inc pc)))
           9 (do (query! program frame host (aget operands base)
                         (aget operands (inc base)) (aget operands (+ base 2)))
                 (recur (inc pc)))
           10 (recur (aget operands base))
           11 (if (aget booleans (aget operands base))
                (recur (inc pc))
                (recur (aget operands (inc base))))
           12 (do (aset objects (aget operands (inc base))
                           {:batch (aget objects (aget operands base))
                            :index 0 :limit (aget operands (+ base 2))})
                   (recur (inc pc)))
           13 (let [iterator (aget objects (aget operands base))
                    index (long (get iterator :index 0))
                    limit (long (get iterator :limit 0))]
                (if (< index limit)
                  (do (aset objects (aget operands (inc base))
                           (nth (get iterator :batch) index nil))
                      (aset objects (aget operands base) (assoc iterator :index (inc index)))
                      (recur (inc pc)))
                  (recur (aget operands (+ base 2)))))
           14 (do (aset-boolean booleans (aget operands (+ base 2)) true)
                  (recur (inc pc)))
           15 (do (append-object! (.-actions frame)
                                  {:type :session-patch :index (aget operands base)})
                  (recur (inc pc)))
           16 (do (append-object! (.-actions frame)
                                  {:type :owner-patch :index (aget operands base)})
                  (recur (inc pc)))
            17 (do (aset objects (aget operands base) {}) (recur (inc pc)))
            18 (let [transaction (aget objects (aget operands base))
                     ^IFn handler (when host (.-preflightHandler host))
                     accepted (if handler (boolean (.invoke handler transaction)) true)]
                 (if accepted
                   (recur (inc pc))
                   (recur (aget operands (inc base)))))
            19 (do (append-object! (.-actions frame)
                                   {:type :txn-reservation
                                    :index (aget operands base)})
                   (recur (inc pc)))
            20 (let [transaction (aget objects (aget operands base))
                     ^IFn handler (when host (.-commitHandler host))
                     committed (if handler
                                 (.invoke handler transaction frame)
                                 true)]
                 (if committed
                   (do (aset objects (aget operands base) committed)
                       (recur (inc pc)))
                   (recur (aget operands (inc base)))))
           21 (do (append-object! (.-actions frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           22 (do (append-object! (.-vfx frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           23 (do (append-object! (.-events frame)
                                  (aget object-constants (aget operands base)))
                  (recur (inc pc)))
           24 (finish-result frame (aget operands base)
                             (not (zero? (aget operands (inc base)))))
           25 (reject-result frame (aget operands base))
           26 (let [node (aget object-constants (aget operands base))
                    component (:component node)]
                (or (execute-component! frame host component
                                        (dissoc node :component) context)
                    (recur (inc pc))))
           (throw (ex-info "unknown combat opcode"
                           {:pc pc :opcode (aget opcodes pc)}))))))))

(defn invoke-query! [^HostTable host ^long capability-id request output]
  (let [^objects handlers (.-queryHandlers host)
        ^IFn handler (aget handlers capability-id)]
    (.invoke handler request output)))
