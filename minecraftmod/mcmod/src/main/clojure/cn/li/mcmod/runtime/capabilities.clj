(ns cn.li.mcmod.runtime.capabilities
  "Startup-linked neutral host capabilities.

   The registry stores ordinary Clojure functions in a stable keyword order;
   platform code owns the function bodies and may only exchange neutral data
   batches or action results with the core runtimes.

   Previously validated every registration against two hardcoded closed
   keyword sets (query-capabilities/action-capabilities) that had to be
   hand-edited whenever ANY registrar added a capability -- and there was
   never only one registrar: combat-core's platform.clj registers its own
   ~30 capabilities via install!, but ac/ability/service/combat_runtime.clj
   ALSO directly registers five more of its own (:energy/target,
   :entity/mark, :energy/charge, :resource/enforce-floor, :resource/add) --
   confirmed by reading both call sites, not assumed. A bc/cc wanting to add
   a genuinely new capability would have had to edit this neutral module,
   exactly the coupling this refactor exists to remove.

   Replaced the closed-set membership check with what actually matters for
   multi-tenancy: detecting a genuine COLLISION, two different registrars
   both trying to claim the same capability name. Both of today's real
   bootstrap call sites (platform.clj's install!, combat_runtime.clj's
   install-ac-host-capabilities!) already self-guard with
   `(when-not (contains? (:queries/:actions (snapshot)) capability) ...)`
   before registering -- confirmed by reading both -- so this change is a
   no-op for the current single-tenant flow and only ever fires for a real
   cross-registrar collision. One real test (combat_runtime_vanilla_damage_
   reflection_test.clj) deliberately re-registers :entity/damage to stub it
   for isolation then restores the original -- an intentional overwrite,
   not a collision -- and now passes {:allow-overwrite? true} explicitly at
   both call sites, confirmed by reading it before assuming this change was
   safe.")

(defonce ^:private state*
  (atom {:frozen? false :queries {} :actions {}}))

(defn- register! [kind capability handler allow-overwrite?]
  (when (:frozen? @state*)
    (throw (ex-info "capability registry frozen" {:capability capability})))
  (when-not (keyword? capability)
    (throw (ex-info "capability must be a keyword" {:capability capability})))
  (when-not (ifn? handler)
    (throw (ex-info "capability handler must be callable" {:capability capability})))
  (when (and (not allow-overwrite?) (contains? (get @state* kind) capability))
    (throw (ex-info "capability already registered" {:capability capability :kind kind})))
  (swap! state* assoc-in [kind capability] handler)
  capability)

(defn register-query!
  "opts is {:allow-overwrite? true} to intentionally replace an already-
   registered handler (e.g. a test swapping in a stub, then restoring the
   original) -- without it, registering an already-claimed capability
   throws. Both of today's real bootstrap registrars (combat-core's
   platform.clj, ac's combat_runtime.clj) already self-guard with their own
   `(when-not (contains? (snapshot) capability) ...)` before calling this,
   so this default costs them nothing and only ever fires for a genuine
   cross-registrar collision."
  ([capability handler] (register-query! capability handler {}))
  ([capability handler {:keys [allow-overwrite?]}] (register! :queries capability handler allow-overwrite?)))

(defn register-action!
  ([capability handler] (register-action! capability handler {}))
  ([capability handler {:keys [allow-overwrite?]}] (register! :actions capability handler allow-overwrite?)))

(defn freeze! []
  (swap! state* assoc :frozen? true)
  @state*)

(defn reset-for-test! []
  (reset! state* {:frozen? false :queries {} :actions {}})
  nil)

(defn snapshot [] @state*)
