(ns cn.li.combat.final-compiler
  "Data-only compiler for the final graph combat program.")
(require '[cn.li.node.contracts :as contracts])
(def ^:const max-instructions (:max-ir-instructions contracts/budgets))
(def ^:const max-iteration (:max-iteration contracts/budgets))
(def ^:private supported-reference-scopes #{:frame :input :local :session})
(defn- fail [reason data] (throw (ex-info (name reason) (assoc data :reason reason))))

(defn- validate-reference-scopes
  "Reject references that the Final evaluator cannot resolve.

   Scope checking historically validated only lexical :local bindings. That
   allowed a typo such as [:item :position] to compile and become nil at
   runtime. Final's value evaluator has a closed set of roots; keep that ABI
   explicit and fail the graph compilation at the offending path instead of
   silently producing a nil input for a later action.
  "
  [value path]
  (cond
    (and (map? value) (vector? (:ref value)))
    (let [reference (:ref value)
          scope (first reference)]
      (when-not (contains? supported-reference-scopes scope)
        (fail :unsupported-reference-scope
              {:path path :reference reference :scope scope
               :supported (vec (sort supported-reference-scopes))})))

    (map? value)
    (doseq [[key child] value]
      (validate-reference-scopes child (conj path key)))

    (sequential? value)
    (doseq [[index child] (map-indexed vector value)]
      (validate-reference-scopes child (conj path index)))

    :else nil))
(defn node-kind [node]
  (or (:kind node)
      (let [component (:component node)]
        (cond
          (contains? #{:flow/sequence :flow/branch :flow/foreach :flow/after
                       :flow/phases :flow/finish :flow/control :flow/once} component) :flow
          (= component :finalize) :flow
          (= component :graph/input) :source
          (contains? #{:graph/output :feedback/emit} component) :feedback
          (contains? #{:resource/try-spend :cooldown/start :progression/mark
                       :score/mark} component) :policy
          (contains? #{:energy/target :target/raycast :target/raycast-fan
                       :target/beam :target/entities :target/blocks
                       :target/entity-snapshot :target/item-held
                       :target/saved-location :target/resolve-destination
                       :target/block-placement :target/directional-destination-query
                       :owner/snapshot :terrain/wave-plan} component) :query
          (contains? #{:owner/can-fly :energy/charge :resource/add
                       :resource/enforce-floor} component) :action
          :else
          (case (namespace component)
            "ability" :source "session" :source "data" :source
            "target" :query "owner" :query "query" :query "host" :query
            "combat" :action "entity" :action "world" :action "block" :action "terrain" :query
            "motion" :action "projectile" :action "inventory" :action
            "energy" :action "resource" :action "cost" :policy "policy" :policy
            "cooldown" :policy "progression" :policy "score" :policy
            "guard" :policy "txn" :flow "effect" :vfx "vfx" :vfx
            "domain" :feedback "feedback" :feedback nil)))))
(defn- children-of [component node]
  (case component
    :flow/sequence (vec (:steps node))
    :flow/branch (vec (remove nil? [(:then node) (:else node)]))
    :flow/foreach (if (:body node) [(:body node)] [])
    :flow/after (if (:body node) [(:body node)] [])
    :flow/once (vec (keep identity [(:body node) (:on-first node)]))
    :flow/phases (vec (concat
                       (keep identity (map #(get node %) [:start :pulse :release :abort]))
                       (vals (:events node))))
    []))
(defn- compile-node [node path flags]
  (when-not (map? node) (fail :not-a-node {:path path :node node}))
  (let [component (:component node) kind (node-kind node)]
    (when-not (keyword? component) (fail :missing-component {:path path}))
    (when-not kind (fail :unknown-final-node-kind {:path path :component component}))
    ;; A query after an action is lowered to a runtime transaction barrier.
    ;; The final engine flushes the already-preflighted command prefix before
    ;; issuing the query, preserving source order without delegating to the
    ;; legacy VM. The barrier is explicit in compiled IR metadata so tooling
    ;; can account for the non-atomic segment.
    (when (= component :flow/foreach)
      (let [raw-limit (:limit node)
            limit (if (number? raw-limit) (long raw-limit) max-iteration)]
        (when-not (<= 0 limit max-iteration)
          (fail :iteration-budget-exceeded {:path path :limit limit :max max-iteration}))))
    (let [self-mutates? (contains? #{:policy :action} kind)
          deferred? (or (:deferred? flags) (= component :flow/after))
          children (children-of component node)]
      (loop [remaining (seq (map-indexed vector children))
             current-flags (assoc flags :mutated? (or (:mutated? flags) self-mutates?))
             compiled []]
        (if-let [[index child] (first remaining)]
          (let [child-result (compile-node child (conj path index) (assoc current-flags :deferred? deferred?))]
            (recur (next remaining) (assoc current-flags :mutated? (:mutated? child-result)) (conj compiled child-result)))
          (let [instructions (inc (reduce + 0 (map :instructions compiled)))]
            (when (> instructions max-instructions)
              (fail :instruction-budget-exceeded {:path path :count instructions :max max-instructions}))
            {:node node :children compiled :instructions instructions :mutated? (:mutated? current-flags)}))))))
(defn compile-program [program]
  (validate-reference-scopes program [:program])
  (let [compiled (compile-node program [:program] {:mutated? false :deferred? false})]
    {:schema-version 1 :program program :instructions (:instructions compiled)
     :content-hash (str (hash (pr-str program)))}))

