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
            [cn.li.vfx.compile :as pcompile]))

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
  (let [scene-program (when (:scene decl)
                        (scene/compile-program (scene/compile-doc! (:scene decl) (:user-types decl {}))))
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
  "Advance every live instance's particle emitters by `dt`. Spawn already
   ran once at ensure! time (see compile-instance) -- only :update runs
   here, every tick, over whatever the buffer currently holds."
  [store ^double dt]
  (doseq [[_ instance] @(:instances store)]
    (doseq [{:keys [buffer update]} (:emitters instance)]
      (update buffer dt))))

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
                                                                        :progress 0.0)}))
                  :emitters (mapv #(select-keys % [:layout :buffer]) (:emitters instance))}]))
        @(:instances store)))
