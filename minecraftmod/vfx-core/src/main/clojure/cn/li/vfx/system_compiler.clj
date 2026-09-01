(ns cn.li.vfx.system-compiler
  "Compiles an expanded VFX control graph into the network-replicated
   :vfx/system descriptor: parameter ABI, the mandatory four-stage Niagara-
   style emitter contract, and post-expansion structural validation.

   Relocated from ac/final_catalog.clj, which had this as the VFX system
   ABI sitting in a content module -- none of it is AC-specific, and it is
   vfx-core's own execution contract (the four-stage
   spawn/initialize/update/output emitter shape final-client's sampler
   depends on), not catalog-assembly bookkeeping. AC's final-catalog now
   only loads/reads resources and calls compile-system below.

   Type validation for :inputs :spawn parameter declarations uses
   cn.li.node.types (canonical-type + known-type?) instead of a local
   scalar/geometry/reference/record type-set fork -- ac/final_catalog.clj
   used to carry its own copy of exactly that fork; every type tag real VFX
   effect EDN actually declares (:any :bool :color :entity-ref :float :int
   :node :resource-id :seed :string :vec3) resolves through it."
  (:require [cn.li.node.types :as types]
            [cn.li.node.composite :as composite]
            [clojure.walk :as walk]))

(defn- normalize-vfx-type
  [type]
  (let [type (if (map? type) (:type type) type)
        canonical (types/canonical-type type)]
    (when-not (or (= canonical :any) (types/known-type? canonical))
      (throw (ex-info "non-canonical final type" {:type type})))
    canonical))

(defn- graph-components [graph]
  (letfn [(walk [value]
            (cond
              (map? value) (into #{} (concat (when-let [component (:component value)] [component])
                                             (mapcat walk (vals value))))
              (sequential? value) (into #{} (mapcat walk value))
              :else #{}))]
    (walk graph)))

(defn- expand-timeline-children
  "cn.li.node.composite's generic expander only recurses into a
   descriptor's declared :children/:node-typed :inputs; :vfx/timeline's own
   :children is an :any-typed opaque vector of {:at t :node n} wrapper
   maps (see validate-vfx-graph!'s docstring below -- the same shape has
   always needed special-case walking, both here and in the VFX compiler
   this replaces), so a composite referenced only from inside a wrapper's
   :node field is invisible to the generic schema-driven traversal.
   Pre-expand those wrapped subgraphs, using the same expander, before the
   top-level pass runs -- by the time the generic pass reaches an
   already-expanded timeline node there is no composite reference left
   inside it to miss."
  [node-environment value composites]
  (walk/postwalk
   (fn [form]
     (if (and (map? form) (= :vfx/timeline (:component form)) (vector? (:children form)))
       (update form :children
               (fn [items]
                 (mapv (fn [item]
                         (if (map? (:node item))
                           (update item :node #(composite/expand-with-environment-and-composites node-environment % composites))
                           item))
                       items)))
       form))
   value))

(defn expand-graph
  "Expand a VFX control graph's composite references. The single VFX entry
   point into cn.li.node.composite's real expander -- callers never call
   that directly, so :vfx/timeline's wrapper-shape quirk above stays a
   vfx-core concern instead of leaking into every caller."
  [node-environment graph composites]
  (composite/expand-with-environment-and-composites
   node-environment
   (expand-timeline-children node-environment graph composites)
   composites))

(defn validate-vfx-graph!
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

(defn compile-system
  "Compile one loaded :vfx/system effect (with an already-expanded
   :control-graph) into its network-replicated descriptor form."
  [{:keys [id lifecycle inputs control-graph state-slots bounds revision] :as effect}]
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
