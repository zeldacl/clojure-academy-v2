(ns cn.li.vfx.runtime
  "Instance store for compiled VFX effects (scene + particle emitters).
   Replaces cn.li.vfx.final-client's design (unchanged, still live -- see
   the redesign plan's staging notes), fixing the two concrete bugs the
   plan calls out there:

   1. O(n) linear scan: final-client's ensure-instance!/instance-for-owner/
      signal-instance-id all did (some (fn [[id inst]] ...) @instances) --
      a full scan of every live instance for a single lookup, against up
      to 2048 instances. Here, instances live in a map keyed directly by
      instance-key: ensure!/destroy!/lookup are all O(1).

   2. Seed always 0: final-client's new-instance never set a real seed
      from the spawn signal, so :random/* content was identical across
      every instance of an effect. ensure! here takes seed from the spawn
      signal and stores it; every :context lookup an emitter/scene program
      resolves at compile time can read it back out.

   Compilation happens PER INSTANCE, not cached per effect-id, deliberately
   -- see cn.li.vfx.compile's scope cut 1: [:context k] module fields
   resolve to LITERAL values once, at compile time. An instance's :start/
   :end (or any other :user param) genuinely differs per activation, so a
   compiled program can only be correct for the instance it was compiled
   for; caching per effect-id would require compiling against REGISTERS
   read from a runtime input instead of literal substitution (the shape
   cn.li.vfx.scene's OWN capability reads already use) -- a real, natural
   follow-up once this simpler, correct version is proven, not required to
   prove the model."
  (:require [cn.li.vfx.scene :as scene]
            [cn.li.vfx.compile :as pcompile]
            [cn.li.vfx.frame :as frame]))

(defn create-store
  "registry: {effect-id effect-decl}, effect-decl:
     {:scene dsl-text-or-nil :user-types {cap-key type}
      :emitters [emitter-decl ...]}"
  [registry]
  {:registry registry :instances (atom {})})

(defn- compile-instance
  "Compiles the scene program (if any) and every emitter, then runs each
   emitter's OWN :spawn stage exactly ONCE here -- :spawn/burst means
   burst, a one-time reservation, not a per-tick action; tick! below only
   ever runs :update afterward. A continuously-emitting effect (a
   :spawn/rate-style module re-triggering reservation on its own schedule)
   is a natural follow-up this pass does not implement or claim to."
  [decl {:keys [user] :as _spawn}]
  (let [scene-program (cond
                        (:document decl) (scene/compile-v3-document! (:document decl))
                        (:scene decl) (scene/compile-program
                                       (scene/compile-doc! (:scene decl) (:user-types decl {})))
                        :else nil)
        emitters (mapv (fn [emitter-decl]
                         (let [compiled (pcompile/compile-emitter emitter-decl user)
                               buffer ((:new-buffer compiled))]
                           ((:spawn compiled) buffer 0.0)
                           (assoc compiled :buffer buffer)))
                       (:emitters decl))]
    {:scene-program scene-program :emitters emitters}))

(defn ensure!
  "Create (if absent) and return the instance at `instance-key`. A second
   ensure! for the same key is a no-op returning the existing instance --
   matches final-client's own idempotent-spawn contract."
  [store instance-key {:keys [effect-id seed] :as spawn}]
  (let [decl (get (:registry store) effect-id)]
    (when-not decl (throw (ex-info "unknown vfx effect-id" {:effect-id effect-id})))
    (swap! (:instances store) update instance-key
          (fn [existing]
            (or existing
                (merge {:effect-id effect-id :seed (long (or seed 0)) :user (:user spawn)
                       :age 0 :owner (:owner spawn) :world-id (:world-id spawn)}
                      (compile-instance decl spawn)))))
    (get @(:instances store) instance-key)))

(defn lookup [store instance-key] (get @(:instances store) instance-key))
(defn instances [store] (vals @(:instances store)))

(defn instance-for-owner
  "The instance-key of a live instance matching (effect-id, owner), or
   nil. An O(n) scan over live instances (final-client's own instance-
   for-owner is the same complexity class, up to :max-instances -- this
   is real production usage, cn.li.ac.ability.client.reactive-hud's own
   per-frame HUD read for a specific owner's storm-wing/flashing state,
   not a hot path multiplied per-instance), because a signal's
   instance-key is caller-chosen (e.g. [owner nid]) and this needs the
   REVERSE lookup: \"whichever instance-key this owner's this effect
   landed under\", not a lookup this store's own instance-key index can
   serve directly."
  [store effect-id owner]
  (some (fn [[k v]] (when (and (= effect-id (:effect-id v)) (= owner (:owner v))) k))
        @(:instances store)))

(defn destroy! [store instance-key] (swap! (:instances store) dissoc instance-key) nil)

(defn clear-owner! [store owner]
  (swap! (:instances store) #(into {} (remove (fn [[_ v]] (= owner (:owner v)))) %))
  nil)

(defn clear-world! [store world-id]
  (if world-id
    (swap! (:instances store) #(into {} (remove (fn [[_ v]] (= world-id (:world-id v)))) %))
    (reset! (:instances store) {}))
  nil)

(defn tick!
  "Advance every live instance's particle emitters by `dt`, then advance
   every instance's own :age by exactly one tick (a discrete MC-tick
   counter real content reads directly via ?age/?progress -- e.g. `(math/
   gte ?age ?fade-at)` -- so it must move in whole ticks regardless of
   `dt`, unlike particle-buffer age/lifetime, which genuinely does
   integrate over `dt` seconds inside each emitter's own :update stage
   above). Spawn already ran once at ensure! time (see compile-instance)
   -- only :update runs here, every tick, over whatever the buffer
   currently holds."
  [store ^double dt]
  (doseq [[_ instance] @(:instances store)]
    (doseq [{:keys [buffer update]} (:emitters instance)]
      (update buffer dt)))
  (swap! (:instances store)
        (fn [instances] (into {} (map (fn [[k v]] [k (update v :age (fnil inc 0))])) instances))))

(defn- progress-of
  "age/duration-ticks, clamped to [0,1] -- the exact computation cn.li.
   vfx.final-engine's own sample-node used for :progress (its context's
   :progress key), reproduced here since every real ac/vfx/fx/*.edn
   effect reads ?progress as a universal capability (cn.li.vfx.scene's
   own universal-capabilities). duration comes from the instance's own
   :user (a real spawn-declared field, e.g. arc_ring_session.edn's own
   :duration-ticks spawn input) -- when absent, matching the old engine's
   identical (max 1.0 (or duration 1)) fallback: progress reaches 1.0
   after exactly one tick rather than staying 0 forever."
  [{:keys [age user]}]
  (let [duration (double (or (:duration-ticks user) 1))]
    (max 0.0 (min 1.0 (/ (double (or age 0)) (max 1.0 duration))))))

(defn sample-frame!
  "{instance-key {:scene [op ...] :emitters [{:layout ... :buffer ...} ...]}}
   for every live instance -- the caller (a future presentation/render
   bridge) turns :scene ops and each emitter's :buffer contents into
   actual draw calls; this namespace only owns instance lifecycle and
   simulation, not rendering."
  [store]
  (into {}
        (map (fn [[k instance]]
               [k {:scene (when-let [program (:scene-program instance)]
                           (scene/sample! program {:capabilities (assoc (:user instance)
                                                                        :age (double (:age instance))
                                                                        :progress (progress-of instance))}))
                  :emitters (mapv #(select-keys % [:layout :buffer]) (:emitters instance))}]))
        @(:instances store)))

;; ============================================================
;; Client composition layer: replaces cn.li.vfx.final-client's own
;; instance-lifecycle/dedup/frame-pooling responsibilities on top of the
;; simpler create-store/ensure!/sample-frame! primitives above. Unlike
;; final-client (which layers registry mutation, a synthetic per-instance
;; :id, and O(n) instance-id lookups on top of its own bespoke instance
;; map), every real VFX signal already carries a stable :instance-key
;; (cn.li.ability.engine-v2/normalize-vfx-signal always derives one, e.g.
;; [owner nid] when the source content doesn't declare its own) -- so
;; dedup/tombstone/instance lookups below are keyed by :instance-key
;; directly, an O(1) map op, with no signal-instance-id-style scan.
;;
;; final-client's own :frozen?/register-effect! collision-guard has no
;; equivalent here on purpose: create-store/create-client-runtime take
;; the WHOLE registry as one immutable map at construction time (there is
;; exactly one real caller building exactly one catalog, unlike final-
;; client's multi-tenant incremental register-effect!), so "register
;; after freeze" is not a reachable state to guard against.
;; ============================================================

(defn create-client-runtime
  "registry: same shape create-store takes, plus each entry may declare
   :lifecycle (:transient effects auto-destroy after their declared
   :duration-ticks, then :life-ticks, otherwise one client tick; see
   client-tick! below). This retires both long-lived transient visuals and
   true one-shot effects without requiring a synthetic duration input)."
  ([registry] (create-client-runtime registry {}))
  ([registry {:keys [max-frames] :or {max-frames 8}}]
   (assoc (create-store registry)
          :tombstones (atom {})
          :frames (atom {})
          :latest-frame (atom nil)
          :generation (atom 0)
          :max-frames max-frames
          :next-frame-id (atom 0))))

(defn- tombstone-seq [rt instance-key]
  (get @(:tombstones rt) instance-key {:event-seq -1 :state-seq -1}))

(defn- transient-duration
  "Returns the authoritative client lifetime for a transient instance.
   `duration-ticks` is the explicit V3 lifecycle contract. Effects whose
   visual payload already owns a particle/ray lifetime use `life-ticks` as
   the natural fallback. A transient with neither field is a one-shot
   operation (audio, burst, etc.) and must still be retired after one tick;
   retaining it forever would sample the one-shot render op every frame."
  [instance]
  (max 1 (long (or (get-in instance [:user :duration-ticks])
                   (get-in instance [:user :life-ticks])
                   1))))

(defn- remember-tombstone! [rt instance-key event-seq state-seq]
  (swap! (:tombstones rt) update instance-key
         (fn [previous]
           {:event-seq (max (long (or (:event-seq previous) -1)) (long event-seq))
            :state-seq (max (long (or (:state-seq previous) -1)) (long state-seq))}))
  nil)

(defn dispatch-signal!
  "Apply a network signal with explicit lifecycle and sequence semantics.
   Destroy records a tombstone even when the instance is not currently live;
   a later spawn/snapshot must carry a strictly newer event sequence to
   recreate that identity. This prevents delayed packets from resurrecting
   an authoritatively destroyed effect."
  [rt {:keys [op owner instance-key seed params] :as signal}]
  (let [event-seq (long (or (:event-seq signal) 0))
        state-seq (long (or (:state-seq signal) event-seq))]
    (if (= :clear-owner op)
      (clear-owner! rt owner)
      (let [tomb (tombstone-seq rt instance-key)
            existing (lookup rt instance-key)]
        (if (= :destroy op)
          (let [event-new? (> event-seq (long (:event-seq tomb)))
                state-new? (> state-seq (long (:state-seq tomb)))]
            (when (or event-new? state-new?)
              (remember-tombstone! rt instance-key event-seq state-seq)
              (when existing
                (let [instance-event (long (or (:event-seq existing) -1))
                      instance-state (long (or (:state-seq existing) -1))]
                  (when (or (> event-seq instance-event)
                            (> state-seq instance-state))
                    (destroy! rt instance-key))))))
          (let [create? (and (contains? #{:spawn :snapshot} op)
                             (nil? existing)
                             (> event-seq (long (:event-seq tomb))))
                instance (or existing
                             (when create?
                               (ensure! rt instance-key
                                        (assoc signal :seed (or seed 0) :user params))))]
            (when instance
              (let [event-new? (> event-seq (long (or (:event-seq instance) -1)))
                    state-new? (> state-seq (long (or (:state-seq instance) -1)))]
                (case op
                  :release
                  (when (or event-new? state-new?) (destroy! rt instance-key))

                  :update
                  (when state-new?
                    (swap! (:instances rt) update instance-key
                           (fn [cur] (assoc cur :state-seq state-seq
                                            :user (merge (:user cur) (or params {})))))
                    (when event-new?
                      (swap! (:instances rt) update instance-key assoc :event-seq event-seq)))

                  (:spawn :snapshot)
                  (when (or event-new? state-new? (= :snapshot op))
                    (swap! (:instances rt) update instance-key
                           (fn [cur]
                             (cond-> (assoc cur :event-seq (max event-seq (long (or (:event-seq cur) -1)))
                                                :state-seq (max state-seq (long (or (:state-seq cur) -1))))
                               (contains? #{:spawn :snapshot} op) (update :user merge (or params {}))))))

                  :trigger
                  (when event-new?
                    (swap! (:instances rt) update instance-key assoc :event-seq event-seq))

                  nil)))))))
    nil))`r`n`r`n(defn client-tick!
  "tick! above (particle buffers + per-instance :age), then destroy any
   :transient instance whose :age has reached its effective lifetime:
   explicit :duration-ticks, then :life-ticks, then one tick for a true
   one-shot."
  [rt ^double dt]
  (tick! rt dt)
  (swap! (:instances rt)
        (fn [instances]
          (into {}
                (remove (fn [[_ inst]]
                         (let [decl (get (:registry rt) (:effect-id inst))]
                           (and (= :transient (:lifecycle decl))
                                (>= (long (:age inst)) (transient-duration inst))))))
                instances)))
  nil)

(defn sample-client-frame!
  "Samples every live instance (sample-frame! above), builds a VfxFrame
   (cn.li.vfx.frame/->java-frame) and pools it under a fresh frame-id --
   final-client's own frame-pooling contract: sampling happens once per
   real game frame, and multiple render-stage submissions on that SAME
   frame read the pooled result back via frame-stage/latest-frame-stage
   instead of re-sampling (see ability-runtime.compose/merge-vfx-into-
   frame's own docstring for why that matters)."
  [rt]
  (let [frame-id (swap! (:next-frame-id rt) inc)
        sampled (sample-frame! rt)
        java-frame (frame/->java-frame frame-id @(:generation rt) sampled)
        stages (into {}
                     (map (fn [[k v]] [k v]))
                     (group-by (fn [^cn.li.mcmod.runtime.vfx.VfxBatch b] (.stage b)) (.batches java-frame)))
        pooled {:frame-id frame-id :java-frame java-frame :stages stages
               :outputs (vec (.outputs java-frame))}]
    (swap! (:frames rt)
          (fn [frames]
            (let [next (assoc frames frame-id pooled)
                  ids (sort (keys next))
                  excess (max 0 (- (count ids) (long (:max-frames rt))))]
              (apply dissoc next (take excess ids)))))
    (reset! (:latest-frame rt) pooled)
    pooled))

(defn frame-stage [rt frame-id stage] (get-in @(:frames rt) [frame-id :stages stage]))
(defn latest-frame-stage [rt stage] (get-in @(:latest-frame rt) [:stages stage]))
(defn release-frame! [rt frame-id] (swap! (:frames rt) dissoc frame-id) nil)
(defn resource-generation [rt] @(:generation rt))
(defn reload-resources! [rt generation] (reset! (:generation rt) generation) generation)
(defn registered-effects [rt] (set (keys (:registry rt))))
