(ns cn.li.combat.source-runtime
  "Real execution for the six :layer :source descriptors registered by
   cn.li.combat.source-nodes (NODE_LANGUAGE.md section 5) -- the piece
   source_nodes.clj's own docstring calls out as still missing: 'these
   descriptors do not yet change what gets executed... a future version
   also knows about the :ability/* source nodes, once those are wired
   into execution rather than only registered as descriptors.'

   A source node's environment tables (:caster-facade/:tunables/:costs/
   :progression/:cooldown/:invariants) are supplied via ctx's :env key,
   not read from any global -- the exact same explicit-input discipline
   NODE_LANGUAGE.md requires of every other node, applied to the one
   place the language deliberately allows an environment boundary. See
   cn.li.combat.structural-primitives/dispatch for where this plugs in."
  (:require [cn.li.node.value :as value]))

(defn- bind-outputs [ctx binds outputs]
  (if (seq binds)
    (reduce-kv (fn [c port local] (update c :locals assoc local (get outputs port))) ctx binds)
    ctx))

(defn- resolve-name [node ctx]
  (value/resolve-value (:name node) (:locals ctx) (:seed ctx)))

(def ^:private caster-output-keys
  "Maps :ability/caster's own output port names onto the existing
   caster-facade map's keys (cn.li.ac.ability.service.combat-runtime's
   caster-facade, unchanged) -- v3's output ports are a flat, un-
   namespaced rename of the same data, not a new source of truth."
  {:eye :caster/eye :eye-y :caster/eye-y :body :caster/body :aim :caster/aim
   :id :caster/id :world-id :world/id :creative? :caster/creative?
   :forward :movement/forward :back :movement/back :left :movement/left :right :movement/right
   :charge-ticks :charge/ticks
   :normal-metal-blocks :targeting/normal-metal-blocks
   :weak-metal-blocks :targeting/weak-metal-blocks
   :metal-entities :targeting/metal-entities
   :mastery :progression/mastery :level :progression/level})

(defn- run-caster [node ctx]
  (let [facade (get-in ctx [:env :caster-facade])
        outputs (reduce-kv (fn [acc port facade-key] (assoc acc port (get facade facade-key)))
                           {} caster-output-keys)]
    (bind-outputs ctx (:bind node) outputs)))

(defn- run-single-value-source [env-key output-port node ctx]
  (let [name (resolve-name node ctx)
        table (get-in ctx [:env env-key])]
    (when-not (contains? table name)
      (throw (ex-info "source node name not declared in this ability's document"
                      {:component (:component node) :env-key env-key :name name
                       :declared (vec (keys table))})))
    (bind-outputs ctx (:bind node) {output-port (get table name)})))

(defn source? [component]
  (contains? #{:ability/caster :ability/tunable :ability/budget
               :ability/progression :ability/cooldown :ability/invariant}
             component))

(defn run
  "Execute one :layer :source node against ctx's :env tables. `node` is
   the (already-known-to-be-a-source) node; throws if `:name` (for the
   five name-keyed sources) is not a key of the corresponding :env table
   -- a source reading past what its OWN ability document declared is a
   real bug, not a nil to silently propagate."
  [node ctx]
  (case (:component node)
    :ability/caster (run-caster node ctx)
    :ability/tunable (run-single-value-source :tunables :value node ctx)
    :ability/budget (run-single-value-source :costs :budget node ctx)
    :ability/progression (run-single-value-source :progression :progression node ctx)
    :ability/cooldown (run-single-value-source :cooldown :cooldown node ctx)
    :ability/invariant (run-single-value-source :invariants :value node ctx)
    (throw (ex-info "not a source node" {:component (:component node)}))))
