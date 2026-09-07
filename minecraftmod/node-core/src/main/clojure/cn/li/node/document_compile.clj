(ns cn.li.node.document-compile
  "V3 document -> the existing register compiler boundary.

   The persisted format is map-shaped and editor-friendly.  The current
   register compiler intentionally remains the execution backend during the
   migration; this lowering is the only place that knows how to translate
   V3 refs/control nodes into its Core AST.  No AC resource is allowed to
   bypass this boundary with a string program.")

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- symbol-for-ref [[scope key & path]]
  (let [prefix (case scope
                 :parameter "$"
                 :context "?"
                 :state "%"
                 :input "?input/"
                 :module-input "?module/"
                 :local ""
                 (fail "unsupported V3 reference scope" {:scope scope :key key}))
        base (if (= :local scope) (name key)
                 (str prefix (when (namespace key) (str (namespace key) "/")) (name key)))]
    (reduce (fn [form field]
              (list (keyword (name field)) form))
            (symbol base)
            path)))

(defn- stamp [form nid]
  (if (and nid (seq? form))
    (with-meta form {:nid (if (keyword? nid)
                            (str (namespace nid) "/" (name nid))
                            (str nid))})
    form))

(declare lower-expr)

(defn- lower-map [m]
  (into {}
        (map (fn [[k v]]
               (let [{:keys [pre form]} (lower-expr v)]
                 (when (seq pre)
                   (fail "V3 component in a literal map needs an explicit bind"
                         {:key k :value v}))
                 [k form])))
        m))

(defn- lower-vector [v]
  (let [parts (mapv lower-expr v)
        pre (vec (mapcat :pre parts))]
    (when (seq pre)
      (fail "V3 component in a literal vector needs an explicit bind"
            {:value v}))
    {:pre [] :form (mapv :form parts)}))

(defn- component-form [node inputs]
  (let [component (:component node)
        head (symbol (if-let [ns (namespace component)]
                       (str ns "/" (name component))
                       (name component)))]
    (stamp (list head inputs) (:nid node))))

(defn lower-expr
  "Return {:pre [surface statements] :form surface expression}.
   Component expressions are A-normalized into a deterministic local so a
   V3 data node can be nested in an input without violating the existing
   compiler's no-side-effect-in-argument rule."
  [value]
  (cond
    (and (map? value) (:ref value))
    {:pre [] :form (symbol-for-ref (:ref value))}

    (and (map? value) (:component value))
    (let [parts (mapv (fn [[k v]] [k (lower-expr v)]) (:inputs value))
          pre (vec (mapcat (comp :pre second) parts))
          inputs (into {} (map (fn [[k part]] [k (:form part)]) parts))
          local (symbol (str "__v3_" (name (:nid value))))]
      {:pre (conj pre (stamp (list 'let local (component-form value inputs)) (:nid value)))
       :form local})

    (map? value)
    {:pre [] :form (lower-map value)}

    (vector? value)
    (lower-vector value)

    :else
    {:pre [] :form value}))

(defn- lower-result-map [m]
  (into {}
        (map (fn [[k v]] [k (:form (lower-expr v))]))
        m))

(declare lower-stmts)

(defn- lower-component-stmt [node]
  (let [parts (mapv (fn [[k v]] [k (lower-expr v)]) (:inputs node))
        pre (vec (mapcat (comp :pre second) parts))
        inputs (into {} (map (fn [[k part]] [k (:form part)]) parts))
        call (component-form node inputs)
        binds (:bind node)]
    (when (> (count binds) 1)
      (fail "V3 lowering currently requires one bound output per statement"
            {:nid (:nid node) :bind binds}))
    (into pre
          (if-let [[_ local] (first binds)]
            [(stamp (list 'let (symbol (name local)) call) (:nid node))]
            [call]))))

(defn- lower-stmt [node]
  (cond
    (:component node) (lower-component-stmt node)

    (:ref node)
    (fail "A V3 reference cannot be a statement" {:nid (:nid node)})

    (= :finish (:flow node))
    (let [result (lower-result-map (:result node))]
      [(stamp (list 'finish result) (:nid node))])

    (= :bind (:flow node))
    (let [{:keys [pre form]} (lower-expr (:value node))]
      (conj pre (stamp (list 'let (symbol (name (:name node))) form) (:nid node))))

    (= :if (:flow node))
    (let [{:keys [pre form]} (lower-expr (:condition node))
          then (lower-stmts (:then node))
          else (lower-stmts (:else node))]
      (conj pre (stamp (list 'if form then else) (:nid node))))

    (= :when (:flow node))
    (let [{:keys [pre form]} (lower-expr (:condition node))
          body (lower-stmts (:do node))]
      (conj pre (stamp (list* 'when form body) (:nid node))))

    (= :foreach (:flow node))
    (let [{:keys [pre form]} (lower-expr (:collection node))
          body (lower-stmts (:do node))]
      (conj pre (stamp (list* 'each (symbol (name (:as node))) form body)
                       (:nid node))))

    :else
    (fail "unknown V3 statement" {:node node})))

(defn lower-stmts [nodes]
  (vec (mapcat lower-stmt nodes)))

(defn- parameter-types [parameters]
  (into {} (map (fn [[key spec]] [key {:type (:type spec)}]) parameters)))

(defn skill->core
  "Validate and lower one skill-v3 document into the normalized shape
   consumed by cn.li.node.compile/compile-program."
  [document]
  ((requiring-resolve 'cn.li.node.document/validate-document!) document)
  (when-not (= :ac/skill-v3 (:schema document))
    (fail "skill->core expects :ac/skill-v3" {:schema (:schema document)}))
  {:kind :ability
   :id (:id document)
   :activation (get-in document [:activation :mode])
   :tunables (parameter-types (:parameters document))
   :state (into {} (map (fn [[k v]] [k (select-keys v [:type :default])]) (:state document)))
   :entry-triggers (into {} (map (fn [[entry spec]] [entry (:on spec)]) (:entries document)))
   :entries
   (into {}
         (map (fn [[entry spec]]
                [entry
                 (let [body (lower-stmts (:do spec))]
                   (if-let [where (:where spec)]
                     (let [{:keys [pre form]} (lower-expr where)]
                       (into pre [(stamp (list* 'when form body) (:nid where))]))
                     body))]))
         (:entries document))})

(defn compile-skill!
  "Compile a skill-v3 document with the existing register backend."
  [document opts mode]
  ;; requiring-resolve keeps the document contract loadable by node-core's
  ;; source checker before the compiler namespace has been AOT-loaded.  The
  ;; runtime dependency remains the same single register compiler.
  ((requiring-resolve 'cn.li.node.compile/compile-program)
   (skill->core document) opts mode))
