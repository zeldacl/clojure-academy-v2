(ns cn.li.node.scope
  "Generic lexical-scope / definite-assignment checker for node trees.
   Descriptor-driven: does not hardcode any structural component's control-
   flow shape (combat's dataflow.clj, the predecessor of this file, had to
   hardcode a case branch per control construct). A structural component
   declares its own shape via each :children port's :flow tag:

     :sequential -- children run in declared order; each sees everything
                    bound by the ones before it. Only the LAST child's
                    resulting bound set carries forward past this node.
     :branch     -- every :branch-tagged port at this node runs against the
                    SAME incoming bound set (independent alternatives). The
                    bound set carried forward is the INTERSECTION across
                    all of them -- only names bound on every possible path
                    are definitely bound afterward.
     :closed     -- the child runs in its own scope; nothing it binds ever
                    escapes to this node's successor (loop bodies, callback
                    sub-trees, one-shot side branches).

   A component's callback-typed :inputs (:type :node, e.g. a composite's
   :on-each-target) are checked the same way as a :closed child, seeded
   with whatever extra locals the input's own :scope declares (see
   NODE_LANGUAGE.md section 6) -- this is what lets a callback sub-tree see
   {:target ...} without that name ever being a real local anywhere else.

   This is what turns 'a node only reads what it declares' from an
   authoring convention into a property the compiler checks."
  (:require [cn.li.node.environment :as registry]
            [clojure.set :as set]))

(defn- fail [reason data]
  (throw (ex-info (name reason) (assoc data :reason reason))))

(defn- declared-local-fields [node d]
  (set (:binds-locals d)))

(defn- collect-local-refs [value acc]
  (cond
    (and (map? value) (vector? (:ref value))
         (= :local (first (:ref value))) (keyword? (second (:ref value))))
    (conj acc (second (:ref value)))
    (map? value) (reduce-kv (fn [acc _ v] (collect-local-refs v acc)) acc value)
    (vector? value) (reduce (fn [acc v] (collect-local-refs v acc)) acc value)
    :else acc))

(defn- callback-inputs
  "Every :inputs entry declared :type :node -- these are child-node
   positions living in the :inputs namespace rather than :children, and are
   never scanned for value refs the way an ordinary scalar input is."
  [d]
  (into {} (filter (fn [[_ spec]] (= :node (:type spec))) (:inputs d))))

(defn- value-refs
  "Local names read by this node's own non-structural fields: excludes
   :component, :bind, every declared :children key, and every callback
   input key (those are node positions, not values)."
  [node d]
  (let [excluded (into #{:component :bind}
                        (concat (keys (:children d)) (keys (callback-inputs d))))]
    (collect-local-refs (apply dissoc node excluded) #{})))

(defn- own-binds
  "This node's own :bind {port -> local-name}, validated against its
   descriptor's declared :outputs. Returns the set of local names this node
   contributes to its successor's bound set."
  [node d]
  (when-let [binds (:bind node)]
    (let [declared (set (keys (:outputs d)))
          unknown (remove declared (keys binds))]
      (when (seq unknown)
        (fail :unknown-output-port {:component (:component node) :ports (vec unknown)}))))
  ;; Some structural primitives introduce a local through a plain keyword
  ;; field rather than :bind.  A node with child ports (notably foreach) owns
  ;; that name only inside its closed child; a leaf binder such as data/bind
  ;; carries it to the following sibling.
  (let [field-binds (if (seq (:children d))
                      #{}
                      (set (keep (fn [key]
                                   (let [value (get node key)]
                                     (when (keyword? value) value)))
                                 (set (concat (declared-local-fields node d)
                                              [:result :ratio-slot])))))
        ;; Query/source nodes use the compact `:result :local-name` ABI in
        ;; addition to the explicit `:bind {port local}` form.  The runtime
        ;; already normalizes both through node-bind; scope checking must do
        ;; the same or it will reject every result-based ability graph.
        result-bind (when (keyword? (:result node)) #{(:result node)})]
    (set/union (set (vals (:bind node))) field-binds (or result-bind #{}))))

(defn- child-local-binds
  "Names introduced by a node for its closed child ports.  Values are
   declarative keyword local names, never runtime data."
  [node d child-key]
  (set/union
   (set (keep (fn [key]
                (let [value (get node key)]
                  (when (keyword? value) value)))
              (declared-local-fields node d)))
   (get-in d [:child-binds-locals child-key] #{})))

(declare check-node)
(def ^:dynamic *environment* nil)

(defn- run-port
  "Run every child under one :children port against the SAME `bound`,
   independent of each other. Returns a vector of each present child's
   resulting bound set (empty if the port has no children present)."
  [node bound path key kind]
  (case kind
    :single (if-let [child (get node key)] [(check-node child bound (conj path key))] [])
    :seq (mapv (fn [child] (check-node child bound (conj path key)))
               (filterv map? (get node key)))
    :case-map (mapv (fn [[case-key child]] (check-node child bound (conj path [key case-key])))
                     (seq (get node key {})))
    (fail :unknown-port-kind {:path path :key key :kind kind})))

(defn- run-sequential-port [node bound path key kind]
  (case kind
    :single (if-let [child (get node key)] (check-node child bound (conj path key)) bound)
    :seq (reduce (fn [acc child] (check-node child acc (conj path key)))
                 bound (filterv map? (get node key)))
    (fail :unknown-port-kind {:path path :key key :kind kind})))

(defn check-node
  "Check `node` against `bound` (the set of :local names definitely bound
   before it runs). Returns the set definitely bound immediately after it
   runs, for a sibling under a :sequential port."
  [node bound path]
  (when-not (map? node) (fail :not-a-node {:path path}))
  (let [component (:component node)
        d (registry/descriptor *environment* component)]
    (when-not d (fail :unknown-component {:path path :component component}))
    (let [node-local-binds (set (keep (fn [key]
                                       (let [value (get node key)]
                                         (when (keyword? value) value)))
                                     (set (concat (declared-local-fields node d)
                                                  [:result :ratio-slot]))))
          missing* (remove (into bound node-local-binds) (value-refs node d))
          missing missing*]
      (when (seq missing)
        (fail :unbound-local {:path path :component component :missing (vec missing)})))
    (let [ports (:children d)
          sequential (filterv #(= :sequential (:flow (val %))) ports)
          branch (filterv #(= :branch (:flow (val %))) ports)
          closed (filterv #(= :closed (:flow (val %))) ports)
          after-sequential (reduce (fn [acc [key {:keys [kind]}]]
                                     (run-sequential-port node
                                                           (into acc (child-local-binds node d key))
                                                           path key kind))
                                    bound sequential)
          ]
      (doseq [[key {:keys [kind]}] closed]
        (run-port node (into after-sequential (child-local-binds node d key)) path key kind))
      (doseq [[key {:keys [scope]}] (callback-inputs d)]
        (when-let [child (get node key)]
          (check-node child (into after-sequential (keys scope)) (conj path key))))
      (let [results (mapcat (fn [[key {:keys [kind]}]]
                              (run-port node
                                        (into after-sequential (child-local-binds node d key))
                                        path key kind))
                            branch)
            merged (if (seq results) (reduce set/intersection results) after-sequential)]
        (into merged (own-binds node d))))))

(defn check-in-environment!
  "Check a graph against an explicit immutable descriptor environment."
  ([environment root] (check-in-environment! environment root #{}))
  ([environment root seed-bound]
   (binding [*environment* environment]
     (check-node root (set seed-bound) [:program])
     nil)))
