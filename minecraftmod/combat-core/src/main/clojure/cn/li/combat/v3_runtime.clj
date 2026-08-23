(ns cn.li.combat.v3-runtime
  "Bridges the real cn.li.mcmod.runtime.capabilities registry (the same
   one cn.li.combat.skill-runtime's existing v2 execute! already builds a
   HostTable from, via host/build-host-table-from-capabilities) into the
   plain ctx map cn.li.node.flow/execute! and cn.li.combat.structural-
   primitives/dispatch already understand -- :dispatch-action!/
   :dispatch-query! as thin wrappers over the registry's own handler
   functions, no ExecutionFrame/HostTable Java object needed at all.

   This works because every capability handler combat-core itself
   registers (cn.li.combat.platform/install!) already ignores the
   ExecutionFrame argument query handlers receive -- e.g. raycast!'s
   [request _frame] -- so a nil in that position is exactly as much
   information any of them actually use today. If a future handler ever
   legitimately needs the frame for something query handlers don't do
   yet, this bridge is the one place that would need to change, not
   every v3 primitive.

   ADDITIVE ONLY: nothing calls execute! below yet.
   cn.li.ac.ability.service.combat-runtime's dispatch-intent! still runs
   every ability through cn.li.combat.skill-runtime/dispatch! (v2) only.
   Actually routing a real ability's activation through this namespace --
   deciding WHICH abilities are v3, and calling this instead of/alongside
   skill-runtime/execute! -- is a deliberately separate, un-taken step:
   it is the point where this stops being purely additive and starts
   being able to change what a real ability does in the running game."
  (:require [cn.li.node.flow :as node-flow]
            [cn.li.combat.structural-primitives :as structural]))

(defn dispatch-fns
  "{:dispatch-action! :dispatch-query!} wrapping `capability-state`'s own
   registered handlers (the shape cn.li.mcmod.runtime.capabilities/snapshot
   returns). An action/query naming a capability nothing registered
   returns nil, the same as a HostTable miss would -- callers (combat's
   :action-impl/:query-impl request-shaping primitives) already treat a
   nil result as 'nothing happened', not a special case to add here."
  [capability-state]
  {:dispatch-action! (fn [capability request]
                       (when-let [handler (get (:actions capability-state) capability)]
                         (handler request)))
   :dispatch-query! (fn [capability request]
                      (when-let [handler (get (:queries capability-state) capability)]
                        (handler request nil)))})

(defn build-ctx
  "Assemble a real ctx for cn.li.node.flow/execute! from the same pieces
   cn.li.combat.skill-runtime/dispatch! already gathers for the v2 engine
   (see that namespace's execution-intent local): `caster-facade` is
   AC's caster-facade-fn result (the :from map), `tunables`/`costs`/
   `progression`/`cooldown`/`invariants` are the ability's own declared
   tables (already curve-materialized for tunables), `capability-state`
   is cn.li.mcmod.runtime.capabilities/snapshot. `resources` seeds
   :resources* (a fresh atom per activation, matching :cost/spend's own
   mutable-accounting expectation -- see cn.li.combat.policy-primitives).

   `emit-vfx!`/`emit-action!`/`emit-event!` default to no-ops so build-ctx
   is usable in isolation (e.g. a query-only program); a real caller
   supplies real ones to actually observe VFX signals / owner-patches /
   domain events."
  [{:keys [owner world-id ability-id activation-seed session-state
           caster-facade tunables costs progression cooldown invariants
           capability-state resources emit-vfx! emit-action! emit-event!]}]
  (merge
   {:locals {} :seed (long (or activation-seed 0)) :dispatch structural/dispatch
    :owner owner :world-id world-id :ability-id ability-id
    :activation-seed (long (or activation-seed 0))
    :session-state session-state
    :resources* (atom (or resources {}))
    :env {:caster-facade caster-facade
          :tunables (or tunables {})
          :costs (or costs {})
          :progression (or progression {})
          :cooldown (or cooldown {})
          :invariants (or invariants {})}
    :emit-vfx! (or emit-vfx! (fn [_]))
    :emit-action! (or emit-action! (fn [_]))
    :emit-event! (or emit-event! (fn [_]))}
   (dispatch-fns capability-state)))

(defn execute!
  "Run `program` (a :program tree whose top level may reference the
   :ability/* source nodes -- see cn.li.combat.source-runtime) against a
   real ctx built from `opts` (see build-ctx). Returns the final ctx;
   callers read :locals/:finished?/:outcome the same way
   cn.li.combat.end-to-end-ability-test's fake-dispatched tests already
   do -- this function only replaces where :dispatch-action!/
   :dispatch-query!/:env come from, not the execution model itself."
  [program opts]
  (node-flow/execute! program (build-ctx opts)))
