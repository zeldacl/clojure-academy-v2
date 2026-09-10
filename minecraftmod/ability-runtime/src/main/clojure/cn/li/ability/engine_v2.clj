(ns cn.li.ability.engine-v2
  "S8 cutover: content-module-neutral composition root for the NEW node-
   core engine (cn.li.combat.api's compile-skill-doc!/compile-skill-
   program/dispatch-skill!), parallel to cn.li.ability.engine (the old
   engine's own composition root, which this namespace does not replace
   -- see NODE_LANGUAGE.md's own \"two engines\" section).

   Design differences from cn.li.ability.engine's :state-provider/
   :commit-state! options, deliberate, not an oversight: the old engine
   mutates an in-graph :txn (a working COPY of owner state) and commits
   it via a whole-state diff after dispatch (cn.li.ac.ability.service.
   combat-runtime's commit-final-state!). The new engine's :cost/spend
   and :cooldown/start are ordinary host actions (registered against the
   SAME neutral capability registry as :entity/damage etc, in
   combat_runtime.clj's install-runtime-adapters!) that apply their
   effect immediately, the moment the graph reaches them -- there is no
   working copy and nothing to diff, so this namespace never needs a
   :state-provider/:commit-state! pair at all. The only thing this
   engine still needs committed after the fact is SESSION state (a
   toggle/session ability's own :state! writes, e.g. active-ticks) --
   :ability-state-provider/:commit-ability-state!/:remove-ability-
   state! are optional compatibility hooks. Production composition owns
   session commits so it can address the exact [owner ability-id] entry;
   when supplied by a legacy caller they are still invoked."
  (:require [cn.li.combat.api :as combat-api]
            [cn.li.mcmod.runtime.capabilities :as capabilities])
  (:import [cn.li.mcmod.runtime.effect ExecutionFrame]))

;; --- host adapter: shared capability registry -> {:query! :command!} ------
;;
;; Every capability handler already registered against cn.li.mcmod.runtime.
;; capabilities (by combat-core/platform.clj's world-facing ports and ac's
;; own install-runtime-adapters!, including the new :cost/spend/:cooldown/
;; start handlers) expects :owner/:world-id (queries) or :owner/:world-id/
;; :ability-id (actions) present in its ARGS map -- confirmed by reading
;; the old engine's own :query/:action dispatch cases (final_engine.clj)
;; and ability-runtime/engine.clj's create-from-capabilities, which inject
;; exactly this for the old engine. This adapter injects the same fields,
;; read off THIS engine's own frame.input (:capabilities :caster/id and
;; :world/id, both universal fixed capabilities every real ability
;; already gets; :ability-id a new top-level input key this namespace's
;; own dispatch! adds, see below) -- not because the DSL declares them as
;; node params (it doesn't; :target/raycast has no :owner param either),
;; but because the shared handler behind the capability name needs them
;; regardless of which engine is calling it.

(defn- owner-of [input] (get-in input [:capabilities :caster/id]))
(defn- world-id-of [input] (get-in input [:capabilities :world/id]))

(defn- registry-host
  "Snapshot the capability registry ONCE (matching ability-runtime/
   engine.clj's create-from-capabilities, which does the same -- by the
   time a production runtime is installed, combat-core/platform.clj's
   install! and ac's install-runtime-adapters! have already registered
   everything real content needs)."
  []
  (let [snap (capabilities/snapshot)]
    {:query! (fn [cap args ^ExecutionFrame fr]
               (let [input (.input fr)
                     handler (get (:queries snap) cap)]
                 (when-not handler
                   (throw (ex-info "unknown query capability" {:capability cap})))
                 (handler (assoc args :owner (owner-of input) :world-id (world-id-of input))
                          {:frame input})))
     :command! (fn [cap args ^ExecutionFrame fr]
                 (let [input (.input fr)
                       handler (get (:actions snap) cap)]
                   (when-not handler
                     (throw (ex-info "unknown action capability" {:capability cap})))
                   (handler (assoc args :owner (owner-of input) :world-id (world-id-of input)
                                   :ability-id (:ability-id input)))))}))

;; --- result translation: ExecutionFrame -> the shape combat_runtime.clj's
;; dispatch-intent!/finalize-result! already consume from the old engine's
;; own execute! -----------------------------------------------------------

(defn- state-write->patch
  "{:key :value} (ExecutionFrame.stateWrites' own shape) -> {:path :mode
   :value} (the old engine's bind-state! patch shape, which combat-
   sessions/apply-actions!'s :session-patch handler already consumes --
   see cn.li.combat.final-engine's own bind-state!)."
  [{:keys [key value]}]
  {:path [key] :mode :assign :value value})

(defn- normalize-vfx-signal
  "{:effect-id :operation :instance-key :audience :payload :nid}
   (cn.li.mcmod.runtime.effect-emit's own :vfx instruction shape) -> {:op
   :effect-id :owner :world-id :instance-key :event-seq :params} (the old
   engine's own normalize-vfx-signal output, which publish-vfx-signal!
   already consumes). event-seq-counter is a per-dispatch atom the
   caller threads through so concurrent signals from one dispatch get a
   real, distinct, monotonic sequence -- matching the old engine's own
   :vfx-order* per-context counter, not a per-namespace global."
  [owner world-id activation-seed event-seq-counter {:keys [effect-id operation instance-key audience
                                                           payload nid] :as signal}]
  {:op (or operation :spawn)
   :effect-id effect-id
   :owner (or (:owner signal) owner)
   :world-id (or (:world-id signal) world-id)
   ;; A graph-local key is only unique within one activation. Scope it with
   ;; the server-issued activation seed so two owners/effects cannot collide
   ;; in the client VFX instance registry.
   :instance-key [world-id owner effect-id activation-seed (or instance-key nid)]
   ;; Must carry the activation seed: client dispatch-signal! seeds geometry
   ;; RNG from (:seed signal). Falling through to event-seq made every bolt
   ;; look identical (first signal is always event-seq 1).
   :seed (long activation-seed)
   :event-seq (swap! event-seq-counter inc)
   :audience audience
   :params (or payload {})})

(defn- normalize-event [owner ability-id event]
  (assoc event :owner owner :ability-id ability-id))

(defn- translate-frame
  "^ExecutionFrame -> the result map cn.li.ac.ability.service.combat-
   runtime's dispatch-intent!/finalize-result! already know how to
   consume (built for the old engine's own execute!, whose full ACCEPTED
   shape this mirrors: :status/:outcome/:next-phase/:finish-ability?/
   :vfx-signals/:feedback/:events/:ability-state-patches). A phase/event
   that never calls `finish` gets an implicit {:outcome :ended :next-
   phase nil :end-ability? false} (cn.li.mcmod.runtime.effect-emit's own
   dispatch! contract), not nil -- matched here, not re-derived."
  [^ExecutionFrame frame owner ability-id]
  (let [result (or (.result frame) {:outcome :ended :next-phase nil :end-ability? false})
        world-id (world-id-of (.input frame))
        event-seq-counter (atom 0)
        activation-seed (long (or (get-in (.input frame) [:capabilities :rng/seed]) 0))]
    {:status :accepted
     :outcome (:outcome result)
     :next-phase (:next-phase result)
     :finish-ability? (boolean (:end-ability? result))
     :vfx-signals (mapv #(normalize-vfx-signal owner world-id activation-seed event-seq-counter %) (.vfx frame))
     :feedback []
     :events (mapv #(normalize-event owner ability-id %) (.events frame))
     :ability-state-patches (mapv state-write->patch (.stateWrites frame))}))

;; --- runtime lifecycle ----------------------------------------------------

(defn create-runtime
  "options: {:commit-ability-state! :remove-ability-state! :catalog-
   compile}. :catalog-compile is a zero-arg fn returning cn.li.ac.
   ability.skills-catalog/assemble's own shape ({:sources :registrations
   :by-id ...}) -- this namespace never requires cn.li.ac.* directly,
   matching cn.li.ability.engine's own \"never knows AC's EDN layout\"
   contract; a future BC/CC pack supplies its own.

   No :ability-state-provider (unlike cn.li.ability.engine's own
   create-runtime): dispatch! never reads session state itself, it takes
   an already-built :input (:tunables/:capabilities/:state) from its
   caller -- the caller is the one place that already knows how to read
   a session (cn.li.ac.ability.service.combat-runtime's own combat-
   sessions/session), so reading it a second time here would just be
   dead code with no real caller. Keeping the option here unused (\"maybe
   someone needs it later\") is exactly the kind of speculative field
   this session's own established discipline avoids."
  [{:keys [commit-ability-state! remove-ability-state! catalog-compile] :as options}]
  (when-not (ifn? catalog-compile)
    (throw (ex-info "engine-v2 requires catalog-compile" {})))
  (let [host (registry-host)
        assembled (catalog-compile)
        registrations (mapv (fn [reg]
                              (assoc reg :compiled
                                     (combat-api/compile-skill-program (:ir reg) host)))
                            (:registrations assembled))
        by-id (into {} (map (juxt :id identity)) registrations)]
    {:options options
     :host host
     :commit-ability-state! commit-ability-state!
     :remove-ability-state! remove-ability-state!
     :catalog (assoc assembled :registrations registrations :by-id by-id)}))

(defn registration [runtime ability-id]
  (get-in runtime [:catalog :by-id ability-id]))

(defn catalog-status [runtime]
  {:status :ready
   :source-count (get-in runtime [:catalog :source-count])
   :registration-count (get-in runtime [:catalog :registration-count])})

(defn dispatch!
  "runtime, ability-id, frame ({:owner :entry :input {:tunables
   :capabilities :state}}) -> the translated result map (see
   translate-frame). frame.input must NOT itself carry :ability-id --
   this function adds it (from the SAME ability-id every registration
   lookup already uses), so a caller building :input for both engines
   from one shared helper never has to special-case this engine's own
   extra key."
  [runtime ability-id {:keys [owner entry input]}]
  (let [reg (registration runtime ability-id)]
    (if (nil? reg)
      {:status :rejected :reason :unknown-ability :ability-id ability-id}
      (let [full-input (assoc input :ability-id ability-id)
            ^ExecutionFrame frame (combat-api/dispatch-skill! (:compiled reg) entry full-input)
            result (translate-frame frame owner ability-id)]
        (when (ifn? (:commit-ability-state! runtime))
          ((:commit-ability-state! runtime) owner (:ability-state-patches result)))
        (when (and (:finish-ability? result) (ifn? (:remove-ability-state! runtime)))
          ((:remove-ability-state! runtime) owner))
        result))))

(defn dispatch-compiled!
  "Like dispatch! but for a program that is NOT in the catalog -- a
   player-composed spell, compiled ad-hoc per-request by cn.li.combat.
   player/compile-and-admit against THIS runtime's own :host (the same
   shared capability registry every catalog ability already dispatches
   through). owner/ability-id here are just labels threaded into the
   translated result (:owner seeds :vfx-signals' default audience owner;
   :ability-id tags :events) -- unlike dispatch! above, neither needs (or
   gets looked up against) a catalog registration, so a caller building a
   pseudo ability-id like :player/spell for this purely to satisfy
   activation-context/caster-facade's own signatures elsewhere is fine.
   No :ability-state-patches commit here (unlike dispatch!): a player
   spell is :activation :instant by construction (cn.li.combat.player/
   desugar never emits :phases), so it never has session state to
   persist -- the caller can still read :ability-state-patches off the
   returned map if that ever changes, this function just doesn't commit
   it on the caller's behalf."
  [runtime owner ability-id ir entry input]
  (let [program (combat-api/compile-skill-program ir (:host runtime))
        full-input (assoc input :ability-id ability-id)
        ^ExecutionFrame frame (combat-api/dispatch-skill! program entry full-input)]
    (translate-frame frame owner ability-id)))

(defn tick!
  "The new engine has no scheduled-continuation mechanism of its own (no
   converted ability uses one -- every :pulse re-dispatch goes through
   the SAME session-driven path as :start/:release/:abort, already fully
   generic at the AC layer: cn.li.ac.ability.service.combat-runtime's
   own pulse-active-sessions! calls dispatch-intent! directly, it does
   not go through anything resembling this function). Kept only for
   interface parity with cn.li.ability.engine's own tick!, which IS still
   needed for beam-settlement-style scheduled work belonging to
   old-engine content."
  [_runtime tick]
  {:status :accepted :tick tick :results []})

(defonce ^:private production-runtime* (atom nil))

(defn install-production!
  [options]
  (let [runtime (create-runtime options)]
    (reset! production-runtime* runtime)
    runtime))

(defn production-runtime [] @production-runtime*)

(defn reset-production-runtime-for-test!
  "Clear the process-local production runtime between isolated test
   Framework lifetimes.  The runtime snapshots capability handlers at
   installation time, so retaining it after a test Framework is replaced
   would dispatch against stale handlers (or return no result when the
   companion AC runtime atom was reset)."
  []
  (reset! production-runtime* nil)
  nil)

(defn dispatch-production! [owner ability-id frame]
  (if-let [runtime @production-runtime*]
    (dispatch! runtime ability-id (assoc frame :owner owner))
    {:status :rejected :reason :engine-v2-not-installed}))
