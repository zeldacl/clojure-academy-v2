(ns cn.li.node.schema
  "Pure schema vocabulary for the final graph compiler.

   This namespace intentionally contains no evaluator, host callback, or
   Minecraft reference.  Domain modules register descriptors that are data
   only; execution ownership belongs to combat-core/vfx-core and their
   neutral mcmod ports.")

(def scalar-types
  #{:bool :int :float :string :resource-id :tick :duration :angle :ratio :seed})

(def geometry-types
  #{:vec2 :vec3 :unit-vec3 :block-pos :quat :transform :aabb :color})

(def reference-types
  #{:world-ref :entity-ref :living-entity-ref :player-ref :projectile-ref
    :block-ref :item-stack-ref :energy-target-ref})

(def record-types
  #{:caster-snapshot :hit-result :destination :block-placement
    :entity-snapshot :item-snapshot :query-shape :entity-filter
    :terrain-plan :beam-result :projectile-candidate :resource-budget
    :resource-cost :cooldown :progression :feedback :damage-event
    :damage-contribution :damage-resolution :vfx-audience :vfx-anchor
    :vfx-signal :vfx-material :render-batch :particle-layout})

(def primitive-types
  (into #{} (concat scalar-types geometry-types reference-types record-types)))

(defn option-type [t] [:option t])
(defn list-type [t] [:list t])
(defn set-type [t] [:set t])
(defn range-type [t] [:range t])
(defn curve-type [t] [:curve t])
(defn enum-type [name] [:enum name])

(defn generic-type?
  [t]
  (and (vector? t)
       (contains? #{:option :list :set :range :curve :enum} (first t))
       (= 2 (count t))))

(defn type?
  "Return true for a closed schema type. User records are nominal keywords
   under :record/name and are accepted by `type?` after declaration."
  [t]
  (cond
    (contains? primitive-types t) true
    (= :any t) true
    (= :unit t) true
    (and (vector? t) (= :handle (first t)) (= 2 (count t))
         (keyword? (second t))) true
    (generic-type? t) (type? (second t))
    (and (vector? t) (= :record (first t)) (= 2 (count t))
         (keyword? (second t))) true
    :else false))

(defn handle-type [effect-id] [:handle effect-id])
(defn record-type [record-id] [:record record-id])

(defn normalize-type
  "Canonicalize the small amount of shorthand accepted in EDN documents."
  [t]
  (cond
    (= t :boolean) :bool
    (= t :long) :int
    (= t :double) :float
    (= t :keyword) :resource-id
    (and (vector? t) (= :list-of (first t))) (list-type (normalize-type (second t)))
    (and (vector? t) (= :option (first t))) (option-type (normalize-type (second t)))
    (and (vector? t) (= :set (first t))) (set-type (normalize-type (second t)))
    :else t))

(defn compatible?
  "Conservative assignability. `:any` is only a consumer-side escape hatch."
  [expected actual]
  (let [expected (normalize-type expected)
        actual (normalize-type actual)]
    (or (= expected :any)
        (= expected actual)
        (and (= expected :float) (= actual :int))
        (and (vector? expected) (vector? actual)
             (= (first expected) (first actual))
             (= 2 (count expected)) (= 2 (count actual))
             (compatible? (second expected) (second actual))))))

(defn valid-port?
  [port]
  (and (keyword? port)
       (not (contains? #{:component :impl :layer} port))))

(defn valid-field?
  [field]
  (and (map? field)
       (type? (normalize-type (:type field)))
       (or (not (contains? field :default))
           (or (= :any (:type field))
               (nil? (:default field))
               ;; Static literal validation is deliberately conservative;
               ;; runtime values are checked by the producing node.
               true))))

(defn validate-descriptor
  "Validate the domain-neutral shape of one final node descriptor. Throws a
   structured error so editor/compiler diagnostics can point at the node."
  [{:keys [id kind inputs outputs effects] :as descriptor}]
  (when-not (keyword? id)
    (throw (ex-info "node descriptor requires keyword :id" {:descriptor descriptor})))
  (when-not (contains? #{:source :query :policy :action :vfx :feedback :flow :function} kind)
    (throw (ex-info "node descriptor has invalid :kind" {:id id :kind kind})))
  (doseq [[port field] (merge (or inputs {}) (or outputs {}))]
    (when-not (valid-port? port)
      (throw (ex-info "node descriptor has invalid port" {:id id :port port})))
    (when-not (valid-field? field)
      (throw (ex-info "node descriptor has invalid field type" {:id id :port port :field field}))))
  (when-not (set? (or effects #{}))
    (throw (ex-info "node descriptor :effects must be a set" {:id id :effects effects})))
  (assoc descriptor :inputs (or inputs {}) :outputs (or outputs {}) :effects (or effects #{})))

