(ns cn.li.node.composite
  "Compile-time expansion of :layer :mid composite invocations into the
   caller's tree. A composite is macro-substituted at its call site -- it
   is data-shape sugar, not a runtime function call, exactly like combat-
   core's pre-existing composite mechanism (recipe.clj), generalized here
   to also produce typed :outputs instead of leaking hardcoded slot names
   (see NODE_LANGUAGE.md section 7).

   expand is the ONLY place a :mid id can be resolved away: after it runs,
   every :component in the tree (below the ability's own top-level source
   nodes) is a :primitive. A :source node found anywhere inside a
   composite body is a compile error -- source nodes are only legal at an
   ability document's own top level (NODE_LANGUAGE.md section 5)."
  (:require [cn.li.node.descriptor :as registry]
            [clojure.walk :as walk]))

(def ^:const max-depth 16)
(def ^:const max-nodes 4096)

(defn- fail [reason data]
  (throw (ex-info (name reason) (assoc data :reason reason))))

(defn- composite? [id]
  (when-let [d (registry/descriptor id)] (= :mid (:layer d))))

(defn- resolve-inputs
  "Build {input-key -> supplied-value-or-subtree} for one call site,
   applying declared defaults and rejecting unknown/missing-required keys."
  [d node path]
  (let [declared (:inputs d)
        supplied (apply dissoc node :component :bind (keys (:children d)))
        unknown (remove declared (keys supplied))]
    (when (seq unknown)
      (fail :unknown-composite-input {:path path :component (:component node) :fields (vec unknown)}))
    (reduce-kv
     (fn [acc k spec]
       (cond
         (contains? node k) (assoc acc k (get node k))
         (contains? spec :default) (assoc acc k (:default spec))
         :else (fail :missing-required-field {:path path :component (:component node) :field k})))
     {} declared)))

(defn- rename-locals
  "Rewrite every :bind target and every {:ref [:local name ...]} inside
   `body` to a call-site-unique name. This is what makes an inlined
   composite body a genuinely closed scope: its internal local names
   cannot collide with, or be read by, the caller or a sibling expansion of
   the same composite."
  [body ns-prefix]
  (let [rename (fn [n] (keyword (str (name ns-prefix) "$" (name n))))]
    (walk/postwalk
     (fn [form]
       (cond
         (and (map? form) (vector? (:ref form)) (= :local (first (:ref form))) (keyword? (second (:ref form))))
         (update-in form [:ref 1] rename)

         (and (map? form) (map? (:bind form)))
         (update form :bind (fn [binds] (into {} (map (fn [[port local]] [port (rename local)]) binds))))

         :else form))
     body)))

(defn- substitute-inputs
  "Replace {:ref [:input k & path]} anywhere in `body` (value position or
   whole node position) with the resolved input value/subtree."
  [body inputs]
  (walk/postwalk
   (fn [form]
     (if (and (map? form) (vector? (:ref form)) (= :input (first (:ref form))))
       (let [[_ k & path] (:ref form) v (get inputs k)]
         (if (seq path) (get-in v (vec path)) v))
       form))
   body))

(defn- output-binds
  "Turn the call site's requested :bind {port -> caller-local} into extra
   :data/bind steps, reading the renamed internal name each declared
   :outputs entry's :from points at."
  [d node ns-prefix path]
  (when-let [binds (:bind node)]
    (let [declared (set (keys (:outputs d)))
          unknown (remove declared (keys binds))]
      (when (seq unknown)
        (fail :unknown-output-port {:path path :component (:component node) :ports (vec unknown)})))
    (mapv (fn [[port caller-local]]
            (let [from (get-in d [:outputs port :from])]
              (when-not (and (vector? from) (= :local (first from)))
                (fail :invalid-output-from {:path path :component (:component d) :port port}))
              {:component :data/bind :to caller-local
               :value {:ref [:local (keyword (str (name ns-prefix) "$" (name (second from))))]}}))
          binds)))

(defn- expand-node [node stack budget path]
  (vswap! budget inc)
  (when (> (long @budget) max-nodes)
    (fail :expansion-node-budget-exceeded {:path path :max max-nodes}))
  (when (> (count stack) max-depth)
    (fail :expansion-depth-exceeded {:path path :max max-depth}))
  (if-not (map? node)
    node
    (let [component (:component node)]
      (if (composite? component)
        (do
          (when (contains? stack component)
            (fail :composite-expansion-cycle {:path path :component component :stack (vec stack)}))
          (let [d (registry/descriptor component)
                inputs (resolve-inputs d node path)
                ns-prefix (gensym (str (name component) "__"))
                renamed-body (rename-locals (:body d) ns-prefix)
                substituted (substitute-inputs renamed-body inputs)
                extra-binds (output-binds d node ns-prefix path)
                ;; Flatten into the body's own :flow/sequence when it already
                ;; is one (the overwhelmingly common composite shape) instead
                ;; of wrapping a second sequence around it -- keeps expanded
                ;; trees shallow and predictable for anything inspecting them
                ;; (tests, a future editor's "expanded preview").
                expanded-body (cond
                                (empty? extra-binds) substituted
                                (= :flow/sequence (:component substituted))
                                (update substituted :steps into extra-binds)
                                :else {:component :flow/sequence :steps (into [substituted] extra-binds)})]
            (expand-node expanded-body (conj stack component) budget path)))
        (let [d (registry/descriptor component)]
          (when-not d (fail :unknown-component {:path path :component component}))
          (when (and (= :source (:layer d)) (seq stack))
            (fail :source-node-outside-ability {:path path :component component}))
          (as-> node node*
            (reduce (fn [n [key {:keys [kind]}]]
                      (case kind
                        :single (if (map? (get n key))
                                  (assoc n key (expand-node (get n key) stack budget (conj path key)))
                                  n)
                        :seq (if (vector? (get n key))
                               (assoc n key (mapv #(if (map? %) (expand-node % stack budget (conj path key)) %)
                                                   (get n key)))
                               n)
                        :case-map (if (map? (get n key))
                                    (assoc n key (into {} (map (fn [[k v]]
                                                                  [k (if (map? v)
                                                                       (expand-node v stack budget (conj path [key k]))
                                                                       v)])
                                                                (get n key))))
                                    n)
                        n))
                    node* (:children d))
            (reduce (fn [n [key _]]
                      (if (map? (get n key))
                        (assoc n key (expand-node (get n key) stack budget (conj path key)))
                        n))
                    node*
                    (filter (fn [[_ spec]] (= :node (:type spec))) (:inputs d)))))))))

(defn expand
  "Expand every composite invocation in `root`, returning a tree whose every
   :component resolves to a :primitive descriptor (or, only at nodes
   reachable before any composite substitution, a :source descriptor).
   Throws (see cn.li.node.scope's :reason vocabulary plus :unknown-composite-
   input/:missing-required-field/:composite-expansion-cycle/:expansion-node-
   budget-exceeded/:expansion-depth-exceeded/:source-node-outside-ability)
   on unknown component/input, missing required input, cycles, or budget
   overrun."
  [root]
  (expand-node root #{} (volatile! 0) [:program]))
