(ns cn.li.combat.final-compiler
  "Data-only compiler for the final graph combat program.")
(require '[cn.li.node.contracts :as contracts])
(def ^:const max-instructions (:max-ir-instructions contracts/budgets))
(def ^:const max-iteration (:max-iteration contracts/budgets))
(defn- fail [reason data] (throw (ex-info (name reason) (assoc data :reason reason))))
(defn node-kind [node]
  (or (:kind node)
      (let [component (:component node)]
        (cond
          (contains? #{:flow/sequence :flow/branch :flow/foreach :flow/after
                       :flow/phases :flow/finish :flow/control :flow/once} component) :flow
          (= component :finalize) :flow
          (= component :graph/input) :source
          (contains? #{:graph/output :feedback/emit} component) :feedback
          (contains? #{:resource/try-spend :cooldown/start :progression/mark} component) :policy
          :else
          (case (namespace component)
            "ability" :source "session" :source "data" :source
            "target" :query "owner" :query "query" :query "host" :query
            "combat" :action "entity" :action "world" :action "block" :action
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
    :flow/once (if (:body node) [(:body node)] [])
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
  (let [compiled (compile-node program [:program] {:mutated? false :deferred? false})]
    {:schema-version 1 :program program :instructions (:instructions compiled)
     :content-hash (str (hash (pr-str program)))}))
