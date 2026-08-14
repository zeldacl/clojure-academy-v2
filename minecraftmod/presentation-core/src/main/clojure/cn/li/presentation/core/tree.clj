(ns cn.li.presentation.core.tree
  "Clojure-owned retained presentation tree.

   RNodes are deliberately plain records/maps: identity comes from a stable
   key, while transient hover/focus/animation state lives on the retained node
   and is not copied from content specs."
  (:import [java.util UUID]))

(defrecord RNode [type key props children state subscriptions])

(defn- valid-key? [k]
  (or (keyword? k) (string? k) (integer? k) (uuid? k)))

(defn- child-specs [spec]
  (vec (or (:children spec) [])))

(defn- assert-keyed-children! [specs]
  (let [keys (mapv :key specs)]
    (when (some #(not (valid-key? %)) keys)
      (throw (ex-info "presentation children require stable keys"
                      {:keys keys})))
    (when (not= (count keys) (count (distinct keys)))
      (throw (ex-info "duplicate presentation child key" {:keys keys})))
    keys))

(defn node
  "Create a retained node from a declarative Clojure spec.

   `:state` and `:subscriptions` are runtime-owned values and are initialized
   once; later reconciles only replace props and child order."
  ([spec] (node spec nil))
  ([spec parent]
   (let [children (child-specs spec)]
     (assert-keyed-children! children)
     (->RNode (:type spec) (:key spec) (dissoc spec :children :state :subscriptions)
              (mapv #(node % nil) children)
              (atom (or (:state spec) {}))
              (atom (vec (or (:subscriptions spec) [])))))))

(defn- close-subscription! [subscription]
  (cond
    (fn? subscription) (subscription)
    (instance? java.lang.AutoCloseable subscription) (.close ^java.lang.AutoCloseable subscription)
    :else nil))

(defn dispose!
  "Recursively dispose a retained subtree exactly once."
  [^RNode node]
  (doseq [child (:children node)] (dispose! child))
  (doseq [subscription @(:subscriptions node)]
    (try (close-subscription! subscription)
         (catch Throwable _)))
  (reset! (:subscriptions node) []))

(defn- same-node? [^RNode old spec]
  (and old (= (:key old) (:key spec)) (= (:type old) (:type spec))))

(declare reconcile-node)

(defn- reconcile-children [old-children new-specs]
  (assert-keyed-children! new-specs)
  (let [old-by-key (into {} (map (juxt :key identity) old-children))
        result (mapv (fn [spec]
                       (if-let [old (get old-by-key (:key spec))]
                         (if (same-node? old spec)
                           (reconcile-node old spec)
                           (do (dispose! old) (node spec)))
                         (node spec)))
                     new-specs)
        retained (set (map :key new-specs))]
    (doseq [old old-children]
      (when-not (contains? retained (:key old)) (dispose! old)))
    result))

(defn reconcile-node
  "Reconcile a new declarative spec into a retained node.

   The returned node is the same object when type/key are stable. Children are
   reused by key, so reordering a list does not recreate ViewModel state or
   subscriptions."
  [^RNode old spec]
  (when-not (same-node? old spec)
    (throw (ex-info "root key/type changed during reconcile"
                    {:old [(:type old) (:key old)]
                     :new [(:type spec) (:key spec)]})))
  (let [children (child-specs spec)
        props (dissoc spec :children :state :subscriptions)
        next-children (reconcile-children (:children old) children)]
    (assert-keyed-children! children)
    (if (and (= (:props old) props)
             (= (count (:children old)) (count next-children))
             (every? true? (map identical? (:children old) next-children)))
      old
      (assoc old :props props :children next-children))))

(defn reconcile
  "Reconcile a possibly nil root. Returns {:node node :created? bool}."
  [old spec]
  (if (nil? old)
    {:node (node spec) :created? true}
    (if (same-node? old spec)
      {:node (reconcile-node old spec) :created? false}
      (do (dispose! old) {:node (node spec) :created? true}))))

(defn find-by-key [^RNode root key]
  (when root
    (if (= key (:key root))
      root
      (some #(find-by-key % key) (:children root)))))
