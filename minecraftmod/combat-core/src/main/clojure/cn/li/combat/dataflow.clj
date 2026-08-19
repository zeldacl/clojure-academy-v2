(ns cn.li.combat.dataflow
  "Compile-time single-direction-dependency check for an expanded ability
   program tree.

   Proves that every {:ref [:slot name ...]} read is reachable only through
   a binding guaranteed to have run strictly before it, in the same or an
   enclosing sequence -- never through a sibling branch that didn't run,
   a loop iteration that hasn't happened yet, or a different loop iteration
   entirely. This is definite-assignment analysis, the same technique a
   language compiler uses to reject `use of possibly-unassigned local
   variable`; it is what turns U1/U2 (see the schema v2 design notes) from
   an authoring convention into a property the compiler checks.

   Wired into `recipe/compile-ability` as a mandatory compile step: every
   ability that loads through the catalog is checked."
  (:require [cn.li.combat.components :as components]
            [clojure.set :as set]))

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- child-position-keys [component]
  (set (keys (:children (components/descriptor component)))))

(defn- collect-slot-refs
  "Every name referenced via {:ref [:slot name ...]} anywhere in `value`."
  [value acc]
  (cond
    (and (map? value) (vector? (:ref value))
         (= :slot (first (:ref value))) (keyword? (second (:ref value))))
    (conj acc (second (:ref value)))
    (map? value) (reduce-kv (fn [acc _ v] (collect-slot-refs v acc)) acc value)
    (vector? value) (reduce (fn [acc v] (collect-slot-refs v acc)) acc value)
    :else acc))

(defn- check-value-refs!
  "Every :slot read in this node's own data (excluding child-node
   positions, which are checked separately with their own bound-set) must
   already be in `bound`."
  [node bound path]
  (let [component (:component node)
        value-data (apply dissoc node :component (child-position-keys component))
        refs (collect-slot-refs value-data #{})
        missing (remove bound refs)]
    (when (seq missing)
      (fail "read of a slot not definitely bound on every reachable path"
            {:path path :component component :missing (vec missing)}))))

(defn- produced-name
  "The slot name this node binds going forward, if its descriptor declares
   a :produces port with a :name-field (e.g. :target/raycast's :result)."
  [node]
  (let [descriptor (components/descriptor (:component node))]
    (some (fn [[_ port]]
            (when-let [field (:name-field port)]
              (get node field)))
          (:produces descriptor))))

(declare analyze-node)

(defn- analyze-child [maybe-child bound in-loop? path key]
  (if (map? maybe-child)
    (analyze-node maybe-child bound in-loop? (conj path key))
    bound))

(defn- analyze-generic
  "Any component without special control-flow shape: its declared children
   (per :children -- :single/:seq/:case-map) are each analyzed against the
   SAME incoming bound-set (they are independent, non-forward-threading
   alternatives, e.g. :host/beam-trace's :block-policy), and its own
   :produces name (if any) is added for the node's successor."
  [node bound in-loop? path]
  (let [component (:component node)
        descriptor (components/descriptor component)]
    (when-not descriptor
      (fail "unknown component" {:path path :component component}))
    (doseq [[key spec] (:children descriptor)]
      (case (:kind spec)
        :single (analyze-child (get node key) bound in-loop? path key)
        :seq (doseq [child (filter map? (get node key))]
               (analyze-node child bound in-loop? (conj path key)))
        :case-map (doseq [[case-key child] (get node key)]
                    (analyze-child child bound in-loop? path [key case-key]))
        nil))
    (if-let [produced (produced-name node)]
      (conj bound produced)
      bound)))

(defn- analyze-node
  "Analyze `node` against the set of slot names definitely bound before it
   runs. Returns the set definitely bound immediately AFTER it runs, for its
   next sibling in the same sequence. `in-loop?` is true only while
   analyzing inside a :flow/foreach :body, which is the only place
   :flow/control is meaningful (U4)."
  [node bound in-loop? path]
  (when-not (map? node)
    (fail "component node must be a map" {:path path :node node}))
  (check-value-refs! node bound path)
  (case (:component node)
    :flow/control
    (do
      (when-not in-loop?
        (fail ":flow/control is only meaningful inside a :flow/foreach body"
              {:path path :signal (:signal node)}))
      bound)

    :flow/sequence
    (reduce (fn [acc [index step]]
              (analyze-node step acc in-loop? (conj path :steps index)))
            bound
            (map-indexed vector (:steps node)))

    :flow/branch
    (let [then-bound (analyze-child (:then node) bound in-loop? path :then)
          else-bound (if (:else node)
                       (analyze-child (:else node) bound in-loop? path :else)
                       bound)]
      (set/intersection then-bound else-bound))

    :flow/window
    (let [pass-bound (analyze-child (:on-pass node) bound in-loop? path :on-pass)
          fail-bound (analyze-child (:on-fail node) bound in-loop? path :on-fail)]
      (set/intersection pass-bound fail-bound))

    :flow/once
    (let [first-bound (if (:on-first node)
                        (analyze-child (:on-first node) bound in-loop? path :on-first)
                        bound)
          dup-bound (if (:on-duplicate node)
                      (analyze-child (:on-duplicate node) bound in-loop? path :on-duplicate)
                      bound)]
      (set/intersection first-bound dup-bound))

    :flow/foreach
    (do
      ;; The loop's own scope -- including the :as binding -- never
      ;; escapes to what follows: the loop may run zero or many times, so
      ;; nothing inside it is ever "definitely" bound afterward.
      (analyze-node (:body node)
                    (cond-> (conj bound (:as node))
                      (:index-as node) (conj (:index-as node)))
                    true
                    (conj path :body))
      bound)

    :flow/phases
    (do
      (doseq [phase [:start :pulse :release :abort]]
        (analyze-child (get node phase) bound in-loop? path phase))
      (doseq [[event-name child] (:events node)]
        (analyze-child child bound in-loop? path [:events event-name]))
      bound)

    :txn/atomic
    (do
      (doseq [guard (:guards node)]
        (analyze-node guard bound in-loop? (conj path :guards)))
      (doseq [reservation (:reservations node)]
        (analyze-node reservation bound in-loop? (conj path :reservations)))
      (let [body-bound (analyze-child (:body node) bound in-loop? path :body)]
        (when (:on-fail node)
          (analyze-node (:on-fail node) bound in-loop? (conj path :on-fail)))
        ;; See vm.clj's :txn/atomic handling: when :on-success is absent,
        ;; :body's own result (which may itself be a terminal :flow/finish)
        ;; is what the transaction produces, so :body's bindings are what
        ;; carry forward.
        (if (:on-success node)
          (analyze-node (:on-success node) body-bound in-loop? (conj path :on-success))
          body-bound)))

    (analyze-generic node bound in-loop? path)))

(defn check-program!
  "Verify the single-direction-dependency invariant for an already-expanded
   ability program tree (post composite-substitution). Throws ex-info,
   naming the exact node path and the missing slot(s), on violation."
  [program]
  (analyze-node program #{} false [:program])
  nil)
