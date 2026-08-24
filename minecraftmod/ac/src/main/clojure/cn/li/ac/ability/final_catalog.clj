(ns cn.li.ac.ability.final-catalog
  "Pure AC content assembly for the final graph architecture.

   This namespace owns resource loading and typed registration metadata only.
   It never invokes a Minecraft API, a legacy evaluator, or a client callback.
   Combat programs remain source graphs until their nodes have been lowered to
   the final compiler vocabulary; VFX descriptors are validated immediately so
   the combat catalog can depend on a stable VFX ABI first.")
(def ^:const schema-version 1)
(def ^:const expected-combat-sources 39)
(def ^:const expected-combat-registrations 50)
(def ^:const expected-vfx-effects 36)

(def scalar-types #{:bool :int :float :string :resource-id :tick :duration
                    :angle :ratio :seed})
(def geometry-types #{:vec2 :vec3 :unit-vec3 :block-pos :quat :transform
                      :aabb :color})
(def reference-types #{:world-ref :entity-ref :living-entity-ref :player-ref
                       :projectile-ref :block-ref :item-stack-ref
                       :energy-target-ref})
(def record-types #{:caster-snapshot :hit-result :destination :block-placement
                    :entity-snapshot :item-snapshot :query-shape :entity-filter
                    :terrain-plan :beam-result :projectile-candidate
                    :resource-budget :resource-cost :cooldown :progression
                    :feedback :damage-event :damage-contribution
                    :damage-resolution :vfx-audience :vfx-anchor :vfx-signal
                    :vfx-material :render-batch :particle-layout})
(defn- final-type? [type]
  (or (= type :any) (= type :unit)
      (contains? (into #{} (concat scalar-types geometry-types reference-types
                                   record-types)) type)
      (and (vector? type) (= 2 (count type))
           (contains? #{:option :list :set :range :curve :enum :handle :record}
                      (first type)))))

(defn- normalize-type [type]
  (case type
    :boolean :bool
    :long :int
    :double :float
    :keyword :resource-id
    :object :any
    type))

(defn- canonical [value]
  (cond
    (map? value) (into (sorted-map-by (fn [left right]
                                       (compare (pr-str left) (pr-str right))))
                           (map (fn [[k v]] [k (canonical v)])) value)
    (set? value) (vec (sort-by pr-str (map canonical value)))
    (sequential? value) (mapv canonical value)
    :else value))

(defn content-hash [value]
  (format "%x" (hash (pr-str (canonical value)))))

(defn read-resource
  "Read one classpath EDN resource through mcmod's safe data boundary."
  [resource]
  (let [url (ClassLoader/getSystemResource resource)]
    (when-not url
      (throw (ex-info "AC catalog resource not found" {:resource resource})))
    (binding [*read-eval* false]
      (read-string (slurp url)))))

(defn- require-keyword [label value data]
  (when-not (keyword? value)
    (throw (ex-info (str label " must be a keyword") (assoc data :value value))))
  value)

(defn compile-input-schema
  "Compile an explicit typed input map; no arbitrary map merging is allowed."
  [inputs]
  (when-not (map? inputs)
    (throw (ex-info "typed input schema must be a map" {:inputs inputs})))
  (into (sorted-map)
        (map (fn [[name spec]]
               (require-keyword "input name" name {})
               (let [spec (if (keyword? spec) {:type spec} spec)
                     type (normalize-type (:type spec))]
                 (when-not (final-type? type)
                   (throw (ex-info "input has unknown final type"
                                   {:input name :type (:type spec)})))
                 [name (assoc spec :type type)])))
        inputs))

(defn- validate-bindings [bindings]
  (when-not (map? bindings)
    (throw (ex-info "registration bindings must be a map" {:bindings bindings})))
  (let [allowed #{:inputs :constants :metadata :presentation :prerequisites}
        unknown (seq (remove allowed (keys bindings)))]
    (when unknown
      (throw (ex-info "registration contains undeclared binding sections"
                      {:unknown unknown}))))
  bindings)

(defn compile-registration
  "Compile one specialization using explicit bindings.

   The old deep :overrides merge is deliberately rejected. A migration step
   may convert old files into named binding sections before calling this
   function, but the final catalog never evaluates an untyped override map."
  [{:keys [id source-id resource bindings] :as registration} source]
  (require-keyword "registration id" id {:registration registration})
  (when-not (string? resource)
    (throw (ex-info "registration resource must be a classpath string"
                    {:registration registration :resource resource})))
  (when (contains? registration :overrides)
    (throw (ex-info "deep registration overrides are not part of final ABI"
                    {:id id :keys (keys (:overrides registration))})))
  (let [bindings (validate-bindings (or bindings {}))]
    {:id id
     :source-id (or source-id (:id source))
     :source-resource resource
     :bindings bindings
     :graph (:program source)
     :status (or (:status source) :pending-final-node-migration)
     :engine (or (:engine source) :final)}))

(defn- migrate-registration-bindings
  "Convert the current manifest's data into named sections once.

   This is a catalog migration adapter, not a runtime compatibility layer;
   the returned final registration contains no :overrides key."
  [registration]
  (if-let [overrides (:overrides registration)]
    (-> registration
        (dissoc :overrides)
        (assoc :bindings {:metadata (dissoc overrides :runtime)
                          :presentation (:runtime overrides)}))
    (assoc registration :bindings (or (:bindings registration) {}))))

(defn- validate-manifest [manifest kind]
  (when-not (= schema-version (:schema-version manifest))
    (throw (ex-info "unsupported AC catalog schema version"
                    {:kind kind :schema-version (:schema-version manifest)})))
  (let [documents (:documents manifest)]
    (when-not (vector? documents)
      (throw (ex-info "AC manifest documents must be a vector" {:kind kind})))
    (when-not (= (count documents) (count (set (map :id documents))))
      (throw (ex-info "AC manifest contains duplicate ids" {:kind kind}))))
  manifest)

(defn- load-combat [combat-manifest]
  (let [manifest (validate-manifest (read-resource combat-manifest) :combat)
        sources (reduce (fn [result {:keys [id resource kind source-id]}]
                          (when-not (= :ability kind)
                            (throw (ex-info "combat manifest contains non-ability"
                                            {:id id :kind kind})))
                          (let [source (read-resource resource)
                                ;; Lower the legacy damage reaction container
                                ;; at catalog load. The final runtime never
                                ;; interprets :reactions; policies are plain
                                ;; data consumed by combat-core/final-damage.
                                source (if-let [reactions (:reactions source)]
                                         (-> source
                                             (dissoc :reactions)
                                             (assoc :damage-policies reactions))
                                         source)
                                source-key (or source-id id)]
                            (when-not (or (= source-key (:id source))
                                          (= id (:id source)))
                              (throw (ex-info "combat source id mismatch"
                                              {:manifest-id id :source-key source-key
                                               :source-id (:id source)})))
                            (assoc result source-key source)))
                        {} (:documents manifest))
        registrations (mapv (fn [registration]
                              (let [registration (migrate-registration-bindings registration)
                                    source (get sources (or (:source-id registration)
                                                            (:id registration)))]
                                (when-not source
                                  (throw (ex-info "combat registration source missing"
                                                  {:registration (:id registration)
                                                   :source-id (:source-id registration)})))
                                (compile-registration registration source)))
                            (:documents manifest))
        source-files (set (map :source-resource registrations))]
    {:manifest manifest
     :sources sources
     :registrations registrations
     :source-count (count sources)
     :registration-count (count registrations)
     :source-files source-files
     :content-hash (content-hash {:sources sources :registrations registrations})}))

(defn- normalize-vfx-type [type]
  (let [type (if (map? type) (:type type) type)]
    (case type
      :object :any
      :keyword :resource-id
      :long :int
      :double :float
      (normalize-type type))))

(defn- vfx-descriptor [{:keys [id lifecycle inputs graph] :as effect}]
  (let [parameters (into {}
                        (map (fn [[name type]]
                               [name {:type (normalize-vfx-type type)
                                      :mutability :immutable}]))
                        (or (get inputs :spawn) {}))]
    (when-not (contains? #{:transient :session :persistent :singleton} lifecycle)
      (throw (ex-info "invalid VFX lifecycle" {:id id :lifecycle lifecycle})))
    (when (> (count parameters) 256)
      (throw (ex-info "VFX effect has too many parameters" {:id id})))
    {:id id
     :schema-version 1
     :lifecycle (if (= :singleton lifecycle) :persistent lifecycle)
     :parameters (mapv (fn [[name spec]] (assoc spec :name name))
                       (sort-by first (seq parameters)))
     :primitives #{}
     :source-graph graph}))

(defn- load-vfx [vfx-manifest]
  (let [manifest (validate-manifest (read-resource vfx-manifest) :vfx)
        effects (mapv (fn [{:keys [id resource kind]}]
                        (when-not (= :vfx-effect kind)
                          (throw (ex-info "vfx manifest contains non-effect"
                                          {:id id :kind kind})))
                        (let [effect (read-resource resource)]
                          (when-not (= id (:id effect))
                            (throw (ex-info "vfx source id mismatch"
                                            {:manifest-id id :source-id (:id effect)})))
                          (vfx-descriptor effect)))
                      (:documents manifest))
        catalog (into (sorted-map) (map (fn [effect] [(:id effect) effect]) effects))]
    {:manifest manifest
     :effects catalog
     :effect-count (count catalog)
     :content-hash (content-hash catalog)}))

(defn assemble
  "Load the complete AC content catalog in VFX-before-combat order."
  ([] (assemble {}))
  ([{:keys [combat-manifest vfx-manifest]
     :or {combat-manifest "ac/combat/manifest.edn"
          vfx-manifest "ac/vfx/manifest.edn"}}]
   (let [vfx (load-vfx vfx-manifest)
         combat (load-combat combat-manifest)]
     {:schema-version schema-version
      :vfx vfx
      :combat combat
      :counts {:combat-sources (:source-count combat)
               :combat-registrations (:registration-count combat)
               :vfx-effects (:effect-count vfx)}
      :content-hash (content-hash {:combat combat :vfx vfx})})))
