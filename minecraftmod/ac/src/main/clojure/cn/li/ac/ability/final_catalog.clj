(ns cn.li.ac.ability.final-catalog
  "Pure AC content assembly for the final graph architecture.

   This namespace owns resource loading and typed registration metadata only.
   It never invokes a Minecraft API, a legacy evaluator, or a client callback.
   Combat programs remain source graphs until their nodes have been lowered to
  the final compiler vocabulary; VFX descriptors are validated immediately so
  the combat catalog can depend on a stable VFX ABI first."
  (:require [cn.li.ability.compose :as ability-compose]
            [cn.li.node.composite :as composite]
            [cn.li.vfx.compiler :as vfx-compiler]
            [cn.li.ac.ability.final-vocabulary :as vocabulary]))
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
     :status (or (:status source) :final)
     :engine (or (:engine source) :final)}))

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

(defn- load-composite-docs [manifest-resource]
  (let [manifest (validate-manifest (read-resource manifest-resource) :composite)]
    (into {}
          (map (fn [{:keys [id resource kind]}]
                 (when-not (= :composite kind)
                   (throw (ex-info "composite manifest contains non-composite" {:id id :kind kind})))
                 (let [document (read-resource resource)
                       ;; The component-era manifest predates the explicit
                       ;; layer field; its kind and body are still the same
                       ;; pure :mid document, so normalize that one omission
                       ;; at the catalog boundary.
                       document (if (nil? (:layer document))
                                  (assoc document :layer :mid)
                                  document)]
                   (when-not (= id (:id document))
                     (throw (ex-info "composite source id mismatch" {:manifest-id id :source-id (:id document)})))
                   (when-not (= :mid (:layer document))
                     (throw (ex-info "composite must use :mid layer" {:id id :layer (:layer document)})))
                   [id document])))
          (:documents manifest))))

(defn- load-combat [combat-manifest node-environment composites]
  (let [manifest (validate-manifest (read-resource combat-manifest) :combat)
        sources (reduce (fn [result {:keys [id resource kind source-id]}]
                          (when-not (= :ability kind)
                            (throw (ex-info "combat manifest contains non-ability"
                                            {:id id :kind kind})))
                          (let [source (-> (read-resource resource)
                                           (update :program #(composite/expand-with-environment-and-composites
                                                              node-environment % composites)))
                                source-key (or source-id id)]
                            (when-not (or (= source-key (:id source))
                                          (= id (:id source)))
                              (throw (ex-info "combat source id mismatch"
                                              {:manifest-id id :source-key source-key
                                               :source-id (:id source)})))
                            (assoc result source-key source)))
                        {} (:documents manifest))
        registrations (mapv (fn [registration]
                              (let [source (get sources (or (:source-id registration)
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
      :content-hash (content-hash {:sources sources :registrations registrations})
      :composites composites}))

(defn- normalize-vfx-type [type]
  (let [type (if (map? type) (:type type) type)]
    (case type
      :object :any
      :keyword :resource-id
      :long :int
      :double :float
      (normalize-type type))))

(defn- graph-components [graph]
  (letfn [(walk [value]
            (cond
              (map? value) (into #{} (concat (when-let [component (:component value)] [component])
                                             (mapcat walk (vals value))))
              (sequential? value) (into #{} (mapcat walk value))
              :else #{}))]
    (walk graph)))

(defn- validate-vfx-graph!
  "Validate every executable VFX component after composite expansion.
   Timeline children are {:at t :node n} data wrappers, so this walks the
   graph shape directly instead of applying combat's child-port validator."
  [graph effect-id]
  (letfn [(walk [value path]
            (cond
              (map? value)
              (do
                (when-let [component (:component value)]
                  (when-not (= "vfx" (namespace component))
                    (throw (ex-info "VFX graph references unknown final node"
                                    {:effect-id effect-id :component component :path path}))))
                (doseq [[k v] value]
                  (walk v (conj path k))))
              (sequential? value)
              (doseq [[idx item] (map-indexed vector value)]
                (walk item (conj path idx)))
              :else nil))]
    (walk graph [:control-graph])
    graph))

(defn- expand-vfx-graph
  "Expand VFX composites with the standalone VFX compiler."
  [graph composites]
  (vfx-compiler/expand-graph graph composites))

(defn- vfx-emitter-stages
  "Compile the mandatory Niagara-style four-stage emitter contract.
   Modules are explicit data with stable numeric opcodes; execution order is
   the stage vector order, never inferred from node traversal." 
  [emitter-id capacity components]
  (let [particle? (some components #{:vfx/emitter :vfx/particle :vfx/particle-field
                                      :vfx/ring-particle-field :vfx/particle-trail})
        spawn (cond-> []
                (some components #{:vfx/emitter})
                (conj {:opcode 100 :module :emission/rate :component :vfx/emitter})
                particle? (conj {:opcode 110 :module :particle/allocate :component :vfx/particle}))
        initialize (if particle? [{:opcode 200 :module :particle/initialize}] [])
        update (if particle? [{:opcode 300 :module :particle/integrate}
                              {:opcode 310 :module :particle/compact}] [])
        output (if particle? [{:opcode 400 :module :particle/output}] [])]
    (when (and (seq spawn) (not= (map :opcode spawn) (sort (map :opcode spawn))))
      (throw (ex-info "VFX emitter stage order is not monotonic" {:emitter emitter-id})))
    {:id emitter-id
     :capacity (long (max 1 capacity))
     :stages {:spawn spawn :initialize initialize :update update :output output}}))

(defn- vfx-descriptor [{:keys [id lifecycle inputs control-graph state-slots bounds revision] :as effect}]
  (let [parameters (into {}
                        (map (fn [[name type]]
                               [name {:type (normalize-vfx-type type)
                                      :scope :user
                                      :mutability :immutable}]))
                        (or (get inputs :spawn) {}))
        components (graph-components control-graph)
        particle? (boolean (some components #{:vfx/emitter :vfx/particle :vfx/particle-field
                                               :vfx/ring-particle-field :vfx/particle-trail}))
        emitter (when particle? (vfx-emitter-stages :default 1024 components))
        snapshot-mode (case lifecycle
                        :transient :none
                        :session :restart
                        :persistent :procedural-seek)]
    (when-not (contains? #{:transient :session :persistent} lifecycle)
      (throw (ex-info "invalid VFX lifecycle" {:id id :lifecycle lifecycle})))
    (when (> (count parameters) 64)
      (throw (ex-info "VFX system has too many network parameters" {:id id :count (count parameters)})))
    {:id id
     :kind :vfx/system
     :asset/type :vfx/system
     :asset/version 1
     :schema-version 1
     :lifecycle lifecycle
     :snapshot-mode snapshot-mode
     :replication {:audience :tracking :priority 50 :max-distance 96.0}
     :parameters (mapv (fn [[name spec]] (assoc spec :name name))
                       (sort-by first (seq parameters)))
     :primitives (let [components (graph-components control-graph)]
                   (cond-> #{}
                     (some components #{:vfx/ring :vfx/beam :vfx/line :vfx/beam-bounds}) (conj :line)
                     (some components #{:vfx/quad :vfx/emitter :vfx/particle}) (conj :quad)))
     :control-graph control-graph
     ;; Temporary compiler metadata is the only place that knows the source
     ;; graph shape. Runtime execution consumes :control-graph and these
     ;; explicit stages, never an untyped node-core program.
     :emitters (vec (remove nil? [emitter]))
     :particle-capacity (some-> emitter :capacity long)
     :system-outputs (vec (keep (fn [component]
                                  (case component
                                    :vfx/audio :audio
                                    :vfx/audio-one-shot :audio
                                    :vfx/audio-loop :audio
                                    :vfx/camera-fov :camera
                                    :vfx/camera-shake :camera
                                    :vfx/post-process :screen
                                    nil)) components))
     :bounds bounds
     :state-slots (or state-slots {})
     :input-schemas inputs
     :revision revision}))
(defn- load-vfx [vfx-manifest composites]
  (let [manifest (validate-manifest (read-resource vfx-manifest) :vfx)
        effects (mapv (fn [{:keys [id resource kind]}]
                        (when-not (= :vfx/system kind)
                          (throw (ex-info "vfx manifest contains non-effect"
                                          {:id id :kind kind})))
                        (let [effect (read-resource resource)]
                          (when-not (= id (:id effect))
                            (throw (ex-info "vfx source id mismatch"
                                            {:manifest-id id :source-id (:id effect)})))
                           (let [expanded (expand-vfx-graph (:control-graph effect) composites)]
                             (validate-vfx-graph! expanded id)
                             (vfx-descriptor (assoc effect :control-graph expanded)))))
                      (:documents manifest))
        catalog (into (sorted-map) (map (fn [effect] [(:id effect) effect]) effects))]
    {:manifest manifest
     :effects catalog
     :effect-count (count catalog)
     :content-hash (content-hash catalog)
     ;; Keep the loaded descriptor map available to audit tooling. Runtime
     ;; execution receives only the expanded effect graphs above; this field
     ;; is metadata and is never consulted by the VFX sampler.
     :composites composites}))

(defn assemble
  "Load the complete AC content catalog in VFX-before-combat order."
  ([] (assemble {}))
  ([{:keys [combat-manifest vfx-manifest combat-composites vfx-composites]
     :or {combat-manifest "ac/combat/manifest.edn"
          vfx-manifest "ac/vfx/manifest.edn"
          combat-composites "ac/combat/composites_v3_manifest.edn"
          vfx-composites "ac/vfx/composites_v3_manifest.edn"}}]
   (let [combat-composite-docs (load-composite-docs combat-composites)
         vfx-composite-docs (merge (load-composite-docs vfx-composites)
                                   ;; The reusable timeline composites are
                                   ;; part of the final VFX catalog as well.
                                   (load-composite-docs "ac/vfx/components_manifest.edn"))
         vfx (load-vfx vfx-manifest vfx-composite-docs)
         node-environment (vocabulary/environment combat-composite-docs)
         combat (load-combat combat-manifest node-environment combat-composite-docs)]
     (let [bundle (ability-compose/compose-catalog :ac node-environment combat vfx)]
       (assoc bundle
              :schema-version schema-version
              :counts {:combat-sources (:source-count combat)
                       :combat-registrations (:registration-count combat)
                       :vfx-effects (:effect-count vfx)}
              :content-hash (content-hash
                             (ability-compose/catalog-fingerprint-input bundle)))))))
