(ns cn.li.node.composite
  "Compile-time expansion of :layer :mid composite invocations into the
   caller's tree. A composite is macro-substituted at its call site: it is
   data-shape sugar, not a runtime function call, and can expose typed
   :outputs without leaking hardcoded slot names (see NODE_LANGUAGE.md
   section 7).

   expand is the ONLY place a :mid id can be resolved away: after it runs,
   every :component in the tree (below the ability's own top-level source
   nodes) is a :primitive. A :source node found anywhere inside a
   composite body is a compile error -- source nodes are only legal at an
   ability document's own top level (NODE_LANGUAGE.md section 5)."
  (:require [cn.li.node.environment :as registry]
            [clojure.walk :as walk]))

(def ^:const max-depth 16)
(def ^:const max-nodes 4096)

(defn- fail [reason data]
  (throw (ex-info (name reason) (assoc data :reason reason))))

(defn- composite? [descriptor-of id]
  (when-let [d (descriptor-of id)] (= :mid (:layer d))))

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
  "Rewrite every :bind target, every {:ref [:local name ...]} read, and
   every field a node's own descriptor declares as :binds-locals inside
   `body` to a call-site-unique name. This is what makes an inlined
   composite body a genuinely closed scope: its internal local names
   cannot collide with, or be read by, the caller or a sibling expansion of
   the same composite.

   :binds-locals exists because :bind ({port -> local}) is not the only
   way a component introduces a new local name -- a structural primitive
   like :flow/foreach binds its own loop variable through plain keyword
   fields (:as/:index-as), a different, older convention this function
   otherwise has no way to know about generically. A component whose
   descriptor declares :binds-locals #{:as :index-as} gets those field
   VALUES renamed the same way :bind targets are, so a reader inside the
   body ({:ref [:local :as-name ...]}, itself renamed by the branch above)
   and the binder's own :as field stay in sync after renaming. Missing
   this was a real bug: :flow/foreach's :as was left unrenamed while every
   {:ref [:local ...]} reading it WAS renamed, so the loop variable and
   its readers silently pointed at two different local names post-
   expansion (caught by combat-core's :combat/area-damage composite test,
   which returned nil for the loop-bound entity id)."
  [descriptor-of body ns-prefix]
  (let [rename (fn [n] (keyword (str (name ns-prefix) "$" (name n))))]
    (walk/postwalk
     (fn [form]
       (cond
         (and (map? form) (vector? (:ref form)) (= :local (first (:ref form))) (keyword? (second (:ref form))))
         (update-in form [:ref 1] rename)

         (and (map? form) (:component form))
           (let [d (descriptor-of (:component form))
               form (if (map? (:bind form))
                      (update form :bind (fn [binds] (into {} (map (fn [[port local]] [port (rename local)]) binds))))
                      form)
               form (if (keyword? (:result form))
                      (update form :result rename)
                      form)
               form (if (keyword? (:ratio-slot form))
                      (update form :ratio-slot rename)
                      form)]
           (reduce (fn [f field] (cond-> f (keyword? (get f field)) (update field rename)))
                   form (:binds-locals d)))

         :else form))
     body)))

(defn- substitute-inputs
  "Replace {:ref [:input k & path]} anywhere in `body` (value position or
   whole node position) with the resolved input value/subtree -- but ONLY
   when `k` is one of this composite's own declared :inputs. A ref whose
   key isn't in `inputs` is left untouched rather than substituted with
   nil: a domain whose runtime value language also uses :input as a scope
   name for something other than composite parameters (vfx-core's
   :input/:state instance-signal scope, distinct from a composite's own
   declared parameters) can nest a structural node inside a composite body
   that introduces its OWN :input binding at a key the composite never
   declared -- e.g. a :vfx/repeat body reading {:ref [:input :i]} for its
   own loop-bound :index-as, not one of the composite's :inputs. Silently
   substituting nil for that (the previous behavior) broke every such
   nested binding; leaving it alone here defers it to whatever resolves
   values at that domain's own runtime, exactly like a combat composite's
   {:ref [:local ...]} forms are already left alone by this function (a
   combat composite has no reason to ever write {:ref [:input ...]} except
   to mean one of its own declared parameters, so this change is a no-op
   for every existing combat composite)."
  [body inputs]
  (walk/postwalk
   (fn [form]
     (if (and (map? form) (vector? (:ref form)) (= :input (first (:ref form))))
       (let [[_ k & path] (:ref form)]
         (if (contains? inputs k)
           (let [v (get inputs k)]
             (if (seq path) (get-in v (vec path)) v))
           form))
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

(defn- expand-node [descriptor-of node stack budget path]
  (vswap! budget inc)
  (when (> (long @budget) max-nodes)
    (fail :expansion-node-budget-exceeded {:path path :max max-nodes}))
  (when (> (count stack) max-depth)
    (fail :expansion-depth-exceeded {:path path :max max-depth}))
  (if-not (map? node)
    node
    (let [component (:component node)]
      (if (composite? descriptor-of component)
        (do
          (when (contains? stack component)
            (fail :composite-expansion-cycle {:path path :component component :stack (vec stack)}))
          (let [d (descriptor-of component)
                inputs (resolve-inputs d node path)
                ns-prefix (gensym (str (name component) "__"))
                renamed-body (rename-locals descriptor-of (:body d) ns-prefix)
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
            (expand-node descriptor-of expanded-body (conj stack component) budget path)))
        (let [d (descriptor-of component)]
          (when-not d (fail :unknown-component {:path path :component component}))
          (when (and (= :source (:layer d)) (seq stack))
            (fail :source-node-outside-ability {:path path :component component}))
          (as-> node node*
            (reduce (fn [n [key {:keys [kind]}]]
                      (case kind
                        :single (if (map? (get n key))
                                  (assoc n key (expand-node descriptor-of (get n key) stack budget (conj path key)))
                                  n)
                        :seq (if (vector? (get n key))
                               (assoc n key (mapv #(if (map? %) (expand-node descriptor-of % stack budget (conj path key)) %)
                                                   (get n key)))
                               n)
                        :case-map (if (map? (get n key))
                                    (assoc n key (into {} (map (fn [[k v]]
                                                                  [k (if (map? v)
                                                                       (expand-node descriptor-of v stack budget (conj path [key k]))
                                                                       v)])
                                                                (get n key))))
                                    n)
                        n))
                    node* (:children d))
            (reduce (fn [n [key _]]
                      (if (map? (get n key))
                        (assoc n key (expand-node descriptor-of (get n key) stack budget (conj path key)))
                        n))
                    node*
                    (filter (fn [[_ spec]] (= :node (:type spec))) (:inputs d)))))))))

(defn expand-with-environment-and-composites
  "Expand using an immutable environment plus catalog-local composite docs."
  [environment root composites]
  (let [composites (or composites {})
        descriptor-of (fn [id] (or (get composites id)
                                   (registry/descriptor environment id)))]
    (expand-node descriptor-of root #{} (volatile! 0) [:program])))
