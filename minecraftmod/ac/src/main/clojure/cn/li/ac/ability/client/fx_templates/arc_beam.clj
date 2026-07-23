(ns cn.li.ac.ability.client.fx-templates.arc-beam
  "Shared FX template: arc-beam defaults + defmulti registry for custom effects.

  Ability FX namespaces register via `build-spec` and expose only `init!`
  plus test helpers delegating to this template."
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.mcmod.runtime.install :as install]))

;; ---------------------------------------------------------------------------
;; Effect registry (populated by build-spec)
;; ---------------------------------------------------------------------------

(def ^:private effect-registry (atom {}))

(defn register-effect!
  "Public: def-arc-beam-fx expands calls to this into each skill FX
  namespace's init!, so it must be visible cross-namespace."
  [effect-id entry]
  (swap! effect-registry assoc effect-id entry))

(defn- effect-entry
  [effect-id]
  (get @effect-registry effect-id))

(defn- runtime-kind
  [effect-id]
  (or (:runtime (effect-entry effect-id)) :level))

(defn- snapshot-runtime
  [effect-id runtime]
  (case runtime
    :hand (hand-effects/effect-state-snapshot effect-id)
    :level (level-effects/effect-state-snapshot effect-id)
    nil))

(defn- update-runtime-state!
  [effect-id runtime f]
  (case runtime
    :hand (hand-effects/update-effect-state! effect-id f)
    :level (level-effects/update-effect-state! effect-id f)))

(defn- reset-runtime-state!
  [effect-id runtime initial-state]
  (case runtime
    :hand (hand-effects/reset-hand-effect-state-for-test! effect-id initial-state)
    :level (level-effects/reset-level-effect-state-for-test! effect-id initial-state)))

;; ---------------------------------------------------------------------------
;; defmulti effect implementations (custom + default arc)
;; ---------------------------------------------------------------------------

(defmulti effect-initial-state
  (fn [effect-id runtime] [effect-id runtime]))

(defmulti effect-enqueue-state!
  (fn [runtime effect-id _store _ctx-id _channel _owner-key _payload]
    [effect-id runtime]))

(defmulti effect-tick-state!
  (fn [runtime effect-id _store] [effect-id runtime]))

(defmulti effect-build-plan
  (fn [effect-id _camera-pos _hand-center-pos _tick & _rest]
    effect-id))

(defmulti effect-clear-owner!
  (fn [effect-id _store _owner-key] effect-id))

(defmulti effect-transform-fn
  (fn [effect-id] effect-id))

(defn validate-multimethod-arity!
  "Check every NON-VARIADIC defmethod of `mf` against `dispatch-arity`.
  Variadic defmethods (with `& args`) accept extra args from the dispatch
  fn beyond their named params, so a lower required-arity is legitimate.
  Only flag methods that are FIXED-ARITY and don't match — those are the
  ones that corrupt the dispatch table."
  [label mf dispatch-arity]
  (let [mis (keep (fn [[dv method]]
                    (let [ma (if (instance? clojure.lang.RestFn method)
                               (.getRequiredArity ^clojure.lang.RestFn method)
                               dispatch-arity)]  ;; non-RestFn → skip check
                      (when (< ma (dec dispatch-arity))
                        {:dispatch-value dv :method-arity ma :min-acceptable (dec dispatch-arity)})))
                  (.getMethodTable ^clojure.lang.MultiFn mf))]
    (when (seq mis)
      (throw (ex-info (str label ": defmethod arity mismatch — "
                           (count mis) " FIXED-ARITY method(s) have wrong arity "
                           "(the MULTIMETHOD dispatch table is CORRUPTED, "
                           "ALL calls silently return nil)")
                      {:multimethod label :mismatches mis})))))

(defn validate-fx-multimethods!
  "Call after all impl namespaces are loaded (post init-discovered-fx!).
  Catches the groundshock-class bug where a defmethod with wrong arity
  silently breaks every effect's build-plan / clear / transform."
  []
  (validate-multimethod-arity! "effect-build-plan"   effect-build-plan   4)
  (validate-multimethod-arity! "effect-clear-owner!"  effect-clear-owner! 3)
  (validate-multimethod-arity! "effect-transform-fn"  effect-transform-fn  1))

;; ---------------------------------------------------------------------------
;; Default arc state / arc implementation
;; ---------------------------------------------------------------------------

(defn default-arc-state
  []
  {:arcs {}})

(defn- ensure-arc-store
  [store]
  (if (contains? (or store {}) :arcs)
    (or store (default-arc-state))
    (default-arc-state)))

(defn- base-meta
  [owner-key ctx-id channel payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :channel channel
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

(defn- arc-item
  "Precompute the zigzag vertex path once per arc, at enqueue time — the
  path is deterministic given (start, end, pattern, seed) and fixed for the
  arc's lifetime, so it must not be recomputed every frame in build-arc-plan."
  [base-meta start end arc-life pattern-key & {:keys [is-aoe? hit-type]}]
  (let [pattern-key* (if is-aoe? :aoe pattern-key)
        pattern (arc-patterns/get-pattern pattern-key*)
        start-v3 (rv3/map->v3 start)
        end-v3 (rv3/map->v3 end)
        seed (rand-int 100000)
        vertices (arc-patterns/generate-zigzag-segments start-v3 end-v3
                   {:segments (:segments pattern)
                    :amplitude (:amplitude pattern)
                    :seed seed})]
    (merge base-meta
           {:ttl arc-life
            :max-ttl arc-life
            :pattern-key pattern-key*
            :hit-type hit-type
            :vertices vertices})))

(defn- play-sound!
  [{:keys [sound-id sound-volume sound-pitch]}]
  (when sound-id
    (client-sounds/queue-current-sound-effect!
      {:type :sound
       :sound-id sound-id
       :volume (double (or sound-volume 0.5))
       :pitch (double (or sound-pitch 1.0))})))

(defn- enqueue-arc-state!
  [opts store ctx-id channel owner-key payload]
  (let [store* (ensure-arc-store store)
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-type aoe-points]} (or payload {})
        base (base-meta owner-key* ctx-id channel payload)
        arc-life (long (or (:arc-life opts) 10))
        arc-pattern (:arc-pattern opts :weak)]
    (case mode
      :perform
      (cond
        (and (:aoe-points? opts) start end)
        (let [main-arcs (vec (repeat 3 (arc-item base start end arc-life arc-pattern)))
              aoe-arcs (->> aoe-points
                            (keep (fn [pt]
                                    (when (map? pt)
                                      (let [life (+ 15 (rand-int 11))]
                                        (arc-item base end pt life arc-pattern :is-aoe? true)))))
                            vec)
              store** (update-in store* [:arcs owner-key*] (fnil into []) (into main-arcs aoe-arcs))]
          (play-sound! opts)
          store**)

        (and (map? start) (map? end))
        (do
          (play-sound! opts)
          (update-in store* [:arcs owner-key*] (fnil conj [])
                     (arc-item base start end arc-life arc-pattern :hit-type hit-type)))

        :else store*)

      :end
      (update store* :arcs dissoc owner-key*)

      store*)))

(defn- tick-arc-state!
  [store]
  (update (ensure-arc-store store) :arcs
    (fn [by-owner]
      (into {}
            (keep (fn [[owner-key items]]
                    (let [live (->> items
                                    (map #(update % :ttl dec))
                                    (filter #(pos? (long (:ttl %))))
                                    vec)]
                      (when (seq live)
                        [owner-key live]))))
            by-owner))))

(defn- arc-ops
  "`cam-v3`/`wiggle-phase` are computed once per build-arc-plan call, not
  once per arc — camera position and the global wiggle clock don't vary
  within a single frame's plan build. `vertices`/`pattern-key` were
  precomputed at enqueue time (see arc-item)."
  [cam-v3 wiggle-phase {:keys [vertices pattern-key ttl max-ttl]}]
  (let [pattern (arc-patterns/get-pattern pattern-key)
        life-ratio (/ (double ttl) (double (max 1 max-ttl)))]
    (ru/zigzag-arc-ops cam-v3 vertices pattern
      {:life-ratio life-ratio
       :wiggle-phase wiggle-phase
       :effective-wiggle (arc-patterns/effective-wiggle-amount pattern life-ratio)})))

(defn- build-arc-plan
  [opts camera-pos _hand-center-pos _tick]
  (let [effect-id (:effect-id opts)
        by-owner (:arcs (level-effects/effect-state-snapshot effect-id))]
    (when (seq by-owner)
      (let [cam-v3 (rv3/map->v3 camera-pos)
            wiggle-phase (arc-patterns/wiggle-phase)
            items (mapcat val by-owner)
            ops (into [] (mapcat #(arc-ops cam-v3 wiggle-phase %)) items)]
        (when (seq ops)
          {:ops ops})))))

(defmethod effect-initial-state :default
  [effect-id runtime]
  (if-let [entry (effect-entry effect-id)]
    (let [init (case runtime
                 :hand (or (:hand-initial-state entry) (:initial-state entry))
                 :level (or (:level-initial-state entry) (:initial-state entry)))]
      (cond
        (fn? init) (init)
        (some? init) init
        :else (default-arc-state)))
    (default-arc-state)))

(defmethod effect-enqueue-state! :default
  [runtime effect-id store ctx-id channel owner-key payload]
  (when (= runtime :level)
    (enqueue-arc-state! (:arc-opts (effect-entry effect-id))
                        store ctx-id channel owner-key payload)))

(defmethod effect-tick-state! :default
  [runtime effect-id store]
  (when (= runtime :level)
    (tick-arc-state! store)))

(defmethod effect-build-plan :default
  [effect-id camera-pos hand-center-pos tick & _args]
  (when-let [entry (effect-entry effect-id)]
    (when-let [arc-opts (:arc-opts entry)]
      (build-arc-plan arc-opts camera-pos hand-center-pos tick))))

(defmethod effect-clear-owner! :default
  [effect-id store owner-key]
  (update (ensure-arc-store store) :arcs dissoc owner-key))

(defmethod effect-transform-fn :default
  [_effect-id]
  nil)

;; ---------------------------------------------------------------------------
;; Runtime dispatch wrappers (used by build-spec)
;; ---------------------------------------------------------------------------

(defn- dispatch-enqueue!
  [runtime effect-id store ctx-id channel owner-key payload]
  (effect-enqueue-state! runtime effect-id store ctx-id channel owner-key payload))

(defn- dispatch-tick!
  [runtime effect-id store]
  (effect-tick-state! runtime effect-id store))

(defn- dispatch-clear-owner!
  [effect-id store owner-key]
  (effect-clear-owner! effect-id store owner-key))

;; ---------------------------------------------------------------------------
;; Channel normalization
;; ---------------------------------------------------------------------------

(defn- normalize-channels
  [channels]
  (cond
    (map? channels) channels

    (sequential? channels)
    (into {}
          (map-indexed
            (fn [idx {:keys [topic mode level-payload-fn targets handler immediate-fn]
                      :or {mode :perform targets [:level]}}]
              [(keyword (str "ch" idx))
               (cond-> {:topic topic :mode mode :targets targets}
                 level-payload-fn (assoc :level-payload level-payload-fn)
                 handler (assoc :handler handler)
                 immediate-fn (assoc :immediate-fn immediate-fn))])
            channels))

    :else
    (throw (IllegalArgumentException. "build-spec :channels must be vector or map"))))

(defn- resolve-initial-state
  [opts runtime]
  (let [specific (case runtime
                   :hand (:hand-initial-state opts)
                   :level (:level-initial-state opts))
        shared (:initial-state opts)
        resolved (or specific shared (effect-initial-state (:effect-id opts) runtime))]
    (if (fn? resolved) (resolved) resolved)))

;; ---------------------------------------------------------------------------
;; Public test / owner API
;; ---------------------------------------------------------------------------

(defn initial-state
  "Default runtime state for an effect (from registry or defmethod)."
  [effect-id & {:keys [runtime]}]
  (let [kind (runtime-kind effect-id)
        rt (or runtime (case kind :both :hand kind))]
    (effect-initial-state effect-id rt)))

(defn snapshot
  ([effect-id]
   (let [kind (runtime-kind effect-id)
         default-runtime (case kind :both :hand kind)]
     (snapshot effect-id {:runtime default-runtime})))
  ([effect-id {:keys [runtime] :as _opts}]
   (let [rt (or runtime (runtime-kind effect-id))
         runtimes (case rt
                    :both [:level :hand]
                    :none []
                    [rt])]
     (if (= 1 (count runtimes))
       (or (snapshot-runtime effect-id (first runtimes))
           (effect-initial-state effect-id (first runtimes)))
       (into {}
             (keep (fn [r]
                     (let [s (or (snapshot-runtime effect-id r)
                                 (effect-initial-state effect-id r))]
                       (when s [r s]))))
             runtimes)))))

(defn reset-for-test!
  [effect-id & {:keys [runtime]}]
  (let [rt (or runtime (runtime-kind effect-id))
        runtimes (case rt
                   :both [:level :hand]
                   :none []
                   [rt])]
    (doseq [r runtimes]
      (reset-runtime-state! effect-id r (effect-initial-state effect-id r)))
    nil))

(defn clear-owner!
  [effect-id owner-key & {:keys [runtime]}]
  (let [rt (or runtime (runtime-kind effect-id))
        runtimes (case rt
                   :both [:level :hand]
                   :none []
                   [rt])]
    (doseq [r runtimes]
      (update-runtime-state!
        effect-id r
        (fn [store]
          (dispatch-clear-owner! effect-id (or store (effect-initial-state effect-id r)) owner-key))))
    nil))

(defn enqueue-for-test!
  [effect-id ctx-id channel payload & {:keys [owner-key runtime]}]
  (let [rt (or runtime (runtime-kind effect-id))
        runtimes (case rt
                   :both [:level :hand]
                   :none []
                   [rt])]
    (doseq [r runtimes]
      (update-runtime-state!
        effect-id r
        (fn [store]
          (dispatch-enqueue! r effect-id
                             (or store (effect-initial-state effect-id r))
                             ctx-id channel
                             (or owner-key [:ctx ctx-id]) payload))))
    nil))

;; ---------------------------------------------------------------------------
;; build-spec
;; ---------------------------------------------------------------------------

(defn build-spec
  "Build a complete `fx-spec/register!` map, pure — no registration side
  effect. The registry entry that `init!` (from def-arc-beam-fx) later
  passes to `register-effect!` travels in the returned map under the
  private key `::arc-entry`; `init!` strips it before calling
  `fx-spec/register!`.

  Required: `:effect-id`, `:channels`
  Runtime: `:runtime` — :level (default), :hand, :both, :none
  State: `:initial-state`, or `:level-initial-state` / `:hand-initial-state` for :both
  Arc opts (default impl): `:sound-id`, `:arc-life`, `:arc-pattern`, `:aoe-points?`
  Hand: `:transform-fn` — static fn or keyword dispatching effect-transform-fn

  Every :level/:both effect gets a :build-plan-fn wired to the
  effect-build-plan multimethod — effects with arc-opts render the default
  arc via the :default method; effects with a custom `defmethod
  effect-build-plan :<id>` render that; effects with neither get nil every
  call (idle-gated by level-effects, so this costs one multimethod dispatch
  only while the effect has live state, never while idle)."
  [opts]
  (let [effect-id (:effect-id opts)
        runtime (or (:runtime opts) :level)
        arc-opts (when (some opts [:sound-id :sound-volume :sound-pitch :arc-life :arc-pattern :aoe-points?])
                   (merge {:effect-id effect-id :arc-pattern :weak :arc-life 10}
                          (select-keys opts [:effect-id :sound-id :sound-volume :sound-pitch
                                             :arc-life :arc-pattern :aoe-points?])))
        channels (normalize-channels (:channels opts))
        transform (or (:transform-fn opts)
                        (when (= runtime :hand)
                          #(effect-transform-fn effect-id)))]
    (when-not (keyword? effect-id)
      (throw (IllegalArgumentException. "build-spec requires :effect-id keyword")))
    (-> (cond-> {:id effect-id :channels channels}
          (not= runtime :none)
          (as-> spec spec
                (if (contains? #{:level :both} runtime)
                  (assoc spec :level
                         {:initial-state (resolve-initial-state opts :level)
                          :enqueue-state-fn #(dispatch-enqueue! :level effect-id %1 %2 %3 %4 %5)
                          :tick-state-fn #(dispatch-tick! :level effect-id %1)
                          :build-plan-fn (fn [cam pos tick query-fn]
                                          (effect-build-plan effect-id cam pos tick query-fn))})
                  spec)
                (if (contains? #{:hand :both} runtime)
                  (assoc spec :hand
                         {:initial-state (resolve-initial-state opts :hand)
                          :enqueue-state-fn #(dispatch-enqueue! :hand effect-id %1 %2 %3 %4 %5)
                          :tick-state-fn #(dispatch-tick! :hand effect-id %1)
                          :transform-fn transform})
                  spec)))
        (assoc ::arc-entry
               {:runtime runtime
                :arc-opts arc-opts
                :initial-state (:initial-state opts)
                :level-initial-state (:level-initial-state opts)
                :hand-initial-state (:hand-initial-state opts)
                :after-register (:after-register opts)}))))

;; ---------------------------------------------------------------------------
;; Per-skill FX boilerplate
;; ---------------------------------------------------------------------------

(defmacro def-arc-beam-fx
  "Declare the standard `init!` / `fx-snapshot` / `reset-fx-for-test!` /
  `clear-fx-owner!` quartet for one skill's FX namespace, given its
  (already-defined, via build-spec) `spec` var and :effect-id keyword.
  Expands in the calling (per-skill FX) namespace, so every skill FX file
  collapses these four near-identical forms into one macro invocation.

  init! does all load-time-deferred registration for this effect:
  - process-once! loads the shared defmethod impl namespace (JVM-level
    dispatch table, must not redo on Framework reinjection) — was a bare
    top-level (require ...) at the bottom of this file; each effect's
    init! now triggers it exactly once per process instead.
  - register-effect! populates the (still process-local, P3-pending)
    effect-registry from the ::arc-entry build-spec attached.
  - fx-spec/register! registers the public fx-spec, stripped of the
    private ::arc-entry key."
  [effect-kw]
  `(do
     (defn ~'init! []
       (install/process-once! ::arc-beam-impls
         #(require 'cn.li.ac.ability.client.fx-templates.arc-beam.impl.load))
       (register-effect! ~effect-kw (get ~'spec ::arc-entry))
       (fx-spec/register! (dissoc ~'spec ::arc-entry))
       nil)
     (defn ~'fx-snapshot [] (snapshot ~effect-kw))
     (defn ~'reset-fx-for-test! [] (reset-for-test! ~effect-kw))
     (defn ~'clear-fx-owner! [owner-key#] (clear-owner! ~effect-kw owner-key#))))
