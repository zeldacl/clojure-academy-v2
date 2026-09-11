(ns cn.li.combat.host-parity
  "Static gates that catch the low-level skill/runtime mismatch class:

   - vocab says query (:returns set) but host only registered an action
     (:kernel/trace-beam and value-returning :entity/spawn had this shape)
   - vocab/IR capability never registered at all
   - V4 graph :component that resolves to neither a pure op, lib :defn,
     vocab node, nor a graph_compile special form

   Compile already catches unknown-call-target / arity / type-mismatch /
   nil-typed-param when skills assemble. This namespace covers the gap
   compile cannot see: the capability registry the emitter actually
   dispatches through."
  (:require [clojure.set :as set]
            [cn.li.combat.dsl-vocabulary :as vocab]
            [cn.li.combat.lib :as lib]
            [cn.li.combat.platform :as platform]
            [cn.li.node.graph-compile :as graph-compile]
            [cn.li.node.ops :as ops]))

;; Owned by cn.li.ac.ability.service.combat-runtime/install-ac-host-capabilities!,
;; not combat-core/platform. Listed here so the parity gate can treat them
;; as intentionally host-provided without pulling ac into combat-core tests.
(def ac-query-capabilities
  #{:cost/spend :energy/target})

(def ac-action-capabilities
  #{:entity/mark :energy/charge :resource/enforce-floor :resource/add
    :cooldown/start
    ;; Registered in platform/install! alongside action-handlers.
    :projectile/schedule-beam})

(def ^:private damage-policy-effect :damage-context-write)

(def graph-special-components
  "Lowered by cn.li.node.graph-compile/component-call without a vocab/
   :defn/:ops entry.

   Aliased, not re-listed: this used to be a hand-copied duplicate of the
   set in graph-compile, so adding a special form there silently made this
   gate report it unresolvable, and removing one silently made the gate
   over-accept."
  graph-compile/special-components)

(defn- capability-of
  [node-id spec]
  (or (:capability spec) node-id))

(defn- query-spec?
  [spec]
  (some? (:returns spec)))

(defn- damage-policy-only?
  "damage/* nodes mutate an in-flight damage REQUEST (cn.li.combat.damage),
   not the skill host registry. Exclude them from skill-host parity."
  [spec]
  (contains? (or (:effects spec) #{}) damage-policy-effect))

(defn platform-query-capabilities
  []
  (set (keys (platform/query-handlers))))

(defn platform-action-capabilities
  []
  (set (keys (platform/action-handlers))))

(defn known-query-capabilities
  "Capabilities a skill :query instruction may legally dispatch."
  []
  (set/union (platform-query-capabilities) ac-query-capabilities))

(defn known-action-capabilities
  "Capabilities a skill :action instruction may legally dispatch."
  []
  (set/union (platform-action-capabilities) ac-action-capabilities))

(defn vocab-host-gaps
  "Return {:missing-queries [...] :missing-actions [...] :kind-mismatches [...]}
   for every non-damage-policy vocab node. kind-mismatches are capabilities
   that appear in the wrong handler map relative to :returns."
  ([] (vocab-host-gaps vocab/nodes
                       (known-query-capabilities)
                       (known-action-capabilities)))
  ([nodes query-caps action-caps]
   (let [rows (for [[node-id spec] nodes
                    :when (not (damage-policy-only? spec))
                    :let [cap (capability-of node-id spec)
                          query? (query-spec? spec)]]
                {:node-id node-id
                 :capability cap
                 :query? query?
                 :ok? (if query?
                        (contains? query-caps cap)
                        (contains? action-caps cap))
                 :wrong-map? (if query?
                               (and (not (contains? query-caps cap))
                                    (contains? action-caps cap))
                               (and (not (contains? action-caps cap))
                                    (contains? query-caps cap)))})]
     {:missing-queries (vec (sort-by :node-id (filter #(and (:query? %) (not (:ok? %))) rows)))
      :missing-actions (vec (sort-by :node-id (filter #(and (not (:query? %)) (not (:ok? %))) rows)))
      :kind-mismatches (vec (sort-by :node-id (filter :wrong-map? rows)))})))

(defn query-action-overlap
  "Capabilities registered as BOTH query and action (except allowlisted).
   Overlap hides the query/action kind bug: install succeeds, dispatch
   still fails when the compiler emits the other op."
  ([]
   (query-action-overlap (platform-query-capabilities)
                         (platform-action-capabilities)
                         #{}))
  ([query-caps action-caps allow]
   (vec (sort (set/difference (set/intersection query-caps action-caps) allow)))))

(defn ir-capability-gaps
  "Walk compiled skill IR; every :query/:action must name a capability in
   the matching known set."
  ([ir] (ir-capability-gaps ir (known-query-capabilities) (known-action-capabilities)))
  ([ir query-caps action-caps]
   (vec
    (for [block (:blocks ir)
          instr (:instrs block)
          :when (contains? #{:query :action} (:op instr))
          :let [cap (:capability instr)
                ok? (case (:op instr)
                      :query (contains? query-caps cap)
                      :action (contains? action-caps cap)
                      false)]
          :when (not ok?)]
      {:op (:op instr)
       :capability cap
       :nid (:nid instr)
       :node (:node instr)}))))

(defn resolvable-component?
  "True when a V4 graph :component keyword can lower + compile."
  [component]
  (or (contains? graph-special-components component)
      (ops/known-op? component)
      (contains? vocab/nodes component)
      (contains? lib/fns component)))

(defn unresolvable-components
  "Collect {:component :path} for every :component keyword in a skill/VFX
   document that cannot resolve."
  ([form] (unresolvable-components form []))
  ([form path]
   (cond
     (map? form)
     (let [here (when-let [c (:component form)]
                  (when (and (keyword? c) (not (resolvable-component? c)))
                    [{:component c :path (conj path :component)}]))]
       (concat here
               (mapcat (fn [[k v]] (unresolvable-components v (conj path k))) form)))
     (sequential? form)
     (mapcat (fn [i v] (unresolvable-components v (conj path i)))
             (range) form)
     :else nil)))
