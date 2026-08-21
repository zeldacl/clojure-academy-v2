(ns cn.li.vfx.install
  "Bridge from a compiled EDN VFX catalog (recipe.clj) to live vfx-core
   runtime descriptors (runtime.clj/register-effect!).

   Before this namespace existed, an EDN effect document compiled all the
   way to a :compiled-program (recipe.clj) and was loaded into
   combat-catalog's :vfx state (ac/.../combat_catalog.clj), but nothing
   ever turned that data into a registered effect -- vfx-core/vm.clj's
   graph interpreter had no caller. Every EDN :effect/vfx signal an
   ability emitted reached effect_controller.clj's dispatch-signal!,
   found no registered effect-id, and was dropped (see
   unmapped-signal-count*). install-catalog! is the missing link."
  (:require [clojure.set :as set]
            [cn.li.vfx.runtime :as runtime]
            [cn.li.vfx.vm :as vm]))

(defn- effect-descriptor [effect]
  (let [graph (:graph effect)
        bounds-node (:bounds effect)]
    {:id (:id effect)
     :lifecycle (:lifecycle effect)
     :init (fn [context] (vm/init-state context))
     :update (fn [state context] (vm/advance-state graph state context))
     :sample (fn [sample-context] (vm/sample! graph (:state sample-context) sample-context))
     ;; register-effect! requires :bounds to be callable even when the
     ;; effect document declares none -- returning nil here is exactly
     ;; what visible-instance? already treats as "no culling, always
     ;; visible" (runtime.clj:412-416).
     :bounds (fn [state _context]
               (when bounds-node
                 (vm/eval-bounds bounds-node {:input (:input state) :state state})))}))

(defn- required-field? [declaration]
  ;; A bare type keyword (:double, :vec3, ...) is required; {:type :double
  ;; :default 1.0} (see vortex_column_session.edn's :alpha/:orientation) is
  ;; optional -- the vm.clj interpreter reads whatever's missing as nil
  ;; either way, so only bare-keyword fields are load-bearing enough to flag.
  (not (and (map? declaration) (contains? declaration :default))))

(defn validate-requirements!
  "Check every combat-core requirement (as produced by
   cn.li.combat.recipe/vfx-signal-requirements) against vfx-catalog's own
   compiled effects. Returns a seq of failure maps (empty when every
   requirement is satisfied); never throws, so a caller can decide whether
   one failing requirement disables just its ability or the whole catalog.

   Deliberately narrow: only (a) an effect-id nothing compiled, or (b) a
   REQUIRED field (per the effect's own :inputs declaration for whichever
   operation was actually sent) missing from the payload -- the one case
   where the interpreter would read nil for something a node's :sample
   logic actually needs and produce a broken or invisible effect. An
   unknown/extra payload field, or an operation the effect declares no
   :inputs for at all (legitimate for a fire-and-forget :destroy with no
   :inputs :destroy entry -- see mag_manip.edn's :fade-ticks against
   effects that never declared a :destroy schema), is real drift but
   functionally inert: it just sits unread. Flagging it anyway would
   disable working abilities over cosmetic mismatches, exactly the kind of
   over-eager validator that makes people stop trusting validators.
   Value-type checking is skipped for the same reason as unknown fields:
   most payload values are {:ref ...}/{:tunable ...}/{:from ...} whose real
   shape is only known at runtime."
  [vfx-catalog requirements]
  (keep
   (fn [{:keys [effect-id operation payload-keys] :as requirement}]
     (let [effect (get-in vfx-catalog [:effects effect-id])]
       (cond
         (nil? effect)
         (assoc requirement :reason :unknown-vfx-effect)

         :else
         (when-let [declared (get-in effect [:inputs operation])]
           (let [required (set (keep (fn [[k v]] (when (required-field? v) k)) declared))
                 missing (set/difference required payload-keys)]
             (when (seq missing)
               (assoc requirement :reason :missing-required-fields
                      :missing-fields missing)))))))
   requirements))

(defn install-catalog!
  "Register every compiled effect in `vfx-catalog` (as returned by
   cn.li.vfx.recipe/load-catalog!) with `runtime`. Idempotent per effect id
   -- an id the registry already has (e.g. a hand-authored legacy effect
   registered under the same id) is left alone rather than throwing, so a
   partial re-install after a hot-reload doesn't crash the client."
  [runtime vfx-catalog]
  (doseq [[id effect] (:effects vfx-catalog)]
    (when-not (contains? (runtime/registered-effects runtime) id)
      (runtime/register-effect! runtime (effect-descriptor effect))))
  (runtime/registered-effects runtime))
