(ns cn.li.node.graph-compile
  "V4 graph lowering.  The persisted graph is authoritative; this small
   adapter emits neutral surface forms with stable node metadata and delegates
   register allocation/type checking to cn.li.node.compile.")

(defn- fail [m d] (throw (ex-info m d)))
(defn- stamp [f nid] (if (seq? f) (with-meta f {:nid (str (namespace nid) "/" (name nid))}) f))
(defn- head [k] (symbol (if-let [n (namespace k)] (str n "/" (name k)) (name k))))
(defn- ref-form [n]
  (let [k (:key n) p (case (:type n) :parameter-ref "$" :state-ref "%" :context-ref "?" "")
        b (if (= :local-get (:type n)) (name k) (str p (when (namespace k) (str (namespace k) "/")) (name k)))]
    (reduce (fn [x f] (list (keyword (name f)) x)) (symbol b) (:path n))))
(defn- data-link [links nid port]
  (some #(when (and (= :data (:kind %)) (= nid (first (:to %))) (= port (second (:to %)))) %) links))
(defn- exec-link [links nid port]
  (some #(when (and (= :exec (:kind %)) (= nid (first (:from %))) (= port (second (:from %)))) %) links))
(defn- target [links nid port] (some-> (exec-link links nid port) :to first))

(declare expr)
(defn expr [ctx nid]
  (let [{:keys [nodes links cache]} ctx]
    (if-let [v (get @cache nid)] v
      (let [n (get nodes nid)
            v (cond
                (= :literal (:type n)) {:pre [] :form (:value n)}
                (contains? #{:context-ref :parameter-ref :state-ref :local-get} (:type n))
                {:pre [] :form (ref-form n)}
                (= :component (:type n))
                (let [parts (for [[p _] (:inputs n) :let [l (data-link links nid p)] :when l]
                              [p (expr ctx (first (:from l)))])
                      ins (into {} (map (fn [[p x]] [p (:form x)]) parts))
                      pre (vec (mapcat (comp :pre second) parts))
                      s (symbol (str "__v4_" (name nid)))
                      call (if (= :value/field (:component n))
                             (list (get ins :field) (get ins :value))
                             (list (head (:component n)) ins))]
                  {:pre (conj pre (stamp (list 'let s call) nid)) :form s})
                :else (fail "unsupported V4 expression node" {:nid nid :type (:type n)}))]
        (swap! cache assoc nid v) v))))

(defn port-expr [ctx nid port]
  (if-let [l (data-link (:links ctx) nid port)] (expr ctx (first (:from l))) {:pre [] :form nil}))

(defn statement [ctx nid]
  (let [n (get (:nodes ctx) nid)]
    (case (:type n)
      :component
      (let [parts (for [[p _] (:inputs n) :let [x (port-expr ctx nid p)]] [p x])
            ins (into {} (map (fn [[p x]] [p (:form x)]) parts))
            pre (vec (mapcat (comp :pre second) parts))
            call (if (= :value/field (:component n)) (list (get ins :field) (get ins :value))
                   (list (head (:component n)) ins))]
        {:pre pre :form (stamp call nid)})
      :local-set
      (let [{:keys [pre form]} (port-expr ctx nid :value)]
        {:pre pre :form (list (if (= :define (:operation n)) 'let 'set!)
                              (symbol (name (:key n))) form)})
      :end {:pre [] :form (stamp (list 'finish (or (:result n) {})) nid)}
      (fail "unsupported V4 statement node" {:nid nid :type (:type n)}))))

(defn collect [ctx start stops]
  (loop [nid start out [] seen #{}]
    (cond
      (nil? nid) {:forms out :next nil}
      (contains? stops nid) {:forms out :next nid}
      (contains? seen nid) (fail "ordinary cycle in V4 graph" {:nid nid})
      :else
      (let [n (get (:nodes ctx) nid) t (:type n)]
        (cond
          (= :start t) (recur (target (:links ctx) nid :out) out (conj seen nid))
          (= :merge t) {:forms out :next nid}
          (= :branch t)
          (let [c (port-expr ctx nid :condition)
                tr (collect ctx (target (:links ctx) nid :true) stops)
                fr (collect ctx (target (:links ctx) nid :false) stops)
                m (or (:next tr) (:next fr))
                f (list* 'if (:form c) (:forms tr) (:forms fr))]
            (recur (when m (target (:links ctx) m :out)) (into out (concat (:pre c) [f])) (conj seen nid)))
          (= :foreach t)
          (let [c (port-expr ctx nid :collection)
                body (collect ctx (target (:links ctx) nid :body) (conj stops nid))
                b (symbol (name (or (:as n) :item)))
                f (list* 'each b (:form c) (:forms body))]
            (recur (target (:links ctx) nid :completed) (into out (concat (:pre c) [f])) (conj seen nid)))
          :else
          (let [{:keys [pre form]} (statement ctx nid)
                nx (target (:links ctx) nid :out)]
            (if (= :end t) {:forms (into out (concat pre [form])) :next nil}
              (recur nx (into out (concat pre [form])) (conj seen nid)))))))))

(defn graph-entry [g]
  (let [ctx {:nodes (:nodes g) :links (:links g) :cache (atom {})}
        start (some (fn [[id n]] (when (= :start (:type n)) id)) (:nodes g))]
    (:forms (collect ctx start #{}))))

(defn skill->core [document]
  ((requiring-resolve 'cn.li.node.graph-document/validate-document!) document)
  (when-not (= :ac/skill-v4 (:schema document)) (fail "expected :ac/skill-v4" {:schema (:schema document)}))
  {:kind :ability :id (:id document) :activation (get-in document [:activation :mode])
   :tunables (into {} (map (fn [[k v]] [k {:type (:type v)}]) (:parameters document)))
   :state (into {} (map (fn [[k v]] [k (select-keys v [:type :default])]) (:state document)))
   :entry-triggers (into {} (map (fn [[k v]] [k (:on v)]) (:graphs document)))
   :entries (into {} (map (fn [[k v]] [k (graph-entry v)]) (:graphs document)))})

(defn compile-skill! [document opts mode]
  ((requiring-resolve 'cn.li.node.compile/compile-program) (skill->core document) opts mode))
