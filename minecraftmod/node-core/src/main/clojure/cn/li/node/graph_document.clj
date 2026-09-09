(ns cn.li.node.graph-document
  "V4 persisted graph contract shared by skill and VFX authoring.

   This namespace intentionally validates only the neutral graph shape.  It
   does not know Minecraft, capability descriptors, or how a component is
   executed; those checks belong to the graph compiler's injected
   environment.  Editor presentation state lives under :editor and is never
   part of the semantic document.")

(def schemas #{:ac/skill-v4 :ac/vfx-v4})
(def node-types
  #{:start :component :branch :merge :foreach :repeat :loop-end :end
    :literal :context-ref :parameter-ref :state-ref :local-get :local-set})
(def link-kinds #{:exec :data})
(def graph-stages #{:render})
(def max-loop-bound 256)

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- require! [test message data]
  (when-not test (fail message data)))

(defn- valid-nid? [nid]
  (and (keyword? nid)
       (= "n" (namespace nid))
       (re-matches #"[a-z0-9][a-z0-9-]{2,63}" (name nid))))

(defn- valid-eid? [eid]
  (and (keyword? eid)
       (= "e" (namespace eid))
       (re-matches #"[a-z0-9][a-z0-9-]{2,63}" (name eid))))

(defn- endpoint? [value]
  (and (vector? value) (= 2 (count value))
       (valid-nid? (first value)) (keyword? (second value))))

(defn- validate-parameters! [parameters path]
  (when (some? parameters)
    (require! (map? parameters) "V4 parameters must be a map" {:path path})
    (doseq [[key spec] parameters]
      (require! (and (keyword? key) (map? spec) (contains? spec :type)
                     (contains? spec :default))
                "V4 parameter requires :type and :default"
                {:path (conj path key) :spec spec})))
  parameters)

(defn- validate-fields! [fields path]
  (when (some? fields)
    (require! (map? fields) "V4 field declarations must be a map" {:path path})
    (doseq [[key spec] fields]
      (require! (and (keyword? key) (map? spec) (contains? spec :type))
                "V4 field declaration requires keyword and :type"
                {:path (conj path key) :spec spec})))
  fields)

(defn- validate-node! [nid node path]
  (require! (map? node) "V4 node must be a map" {:path path :node node})
  (require! (= nid (:nid node)) "V4 node :nid must equal its map key"
            {:path path :nid (:nid node)})
  (require! (valid-nid? nid) "V4 node requires nid :n/<lowercase-id>"
            {:path path :nid nid})
  (let [type (:type node)]
    (require! (contains? node-types type) "unknown V4 node type"
              {:path path :type type})
    (cond
      (= :component type)
      (require! (keyword? (:component node))
                "V4 component node requires keyword :component"
                {:path path})

      (contains? #{:context-ref :parameter-ref :state-ref :local-get} type)
      (require! (keyword? (:key node))
                "V4 reference node requires keyword :key" {:path path})

      (= :local-set type)
      (do
        (require! (keyword? (:key node))
                  "V4 local-set requires keyword :key" {:path path})
        (require! (contains? #{:define :assign} (:operation node))
                  "V4 local-set operation must be :define or :assign"
                  {:path path :operation (:operation node)}))

      (= :foreach type)
      (require! (and (integer? (:limit node))
                     (<= 1 (:limit node) max-loop-bound))
                "V4 foreach limit must be an integer from 1 to 256"
                {:path path :limit (:limit node)})

      (= :repeat type)
      (require! (and (integer? (:count node))
                     (<= 1 (:count node) max-loop-bound))
                "V4 repeat count must be an integer from 1 to 256"
                {:path path :count (:count node)})

      :else nil))
  node)

(defn- outgoing [links nid kind]
  (filter #(and (= kind (:kind %)) (= nid (first (:from %)))) links))

(defn- incoming [links nid kind]
  (filter #(and (= kind (:kind %)) (= nid (first (:to %)))) links))

(defn- validate-cycle-free! [nodes links]
  ;; Loop back edges are the only legal cycles.  Removing them makes the
  ;; remaining execution graph a DAG, which is sufficient for deterministic
  ;; compilation and for diagnosing accidental ordinary cycles.
  (let [forward (remove #(or (not= :exec (:kind %))
                            (= :loop-back (second (:to %)))) links)
        edges (reduce (fn [m {:keys [from to]}]
                        (update m (first from) (fnil conj []) (first to)))
                      {} forward)]
    (letfn [(visit [nid visiting visited]
              (cond
                (contains? visiting nid) (fail "V4 execution graph contains an ordinary cycle"
                                                {:node nid})
                (contains? visited nid) visited
                :else (reduce (fn [seen next]
                                (visit next (conj visiting nid) seen))
                              (conj visited nid) (get edges nid []))))]
      (reduce (fn [seen nid] (visit nid #{} seen)) #{} (keys nodes)))))

(defn validate-graph!
  "Validate one graph and return it unchanged.  Disconnected nodes are
   allowed for editor drafts; only nodes reachable from :start are required
   to form a terminating executable control graph."
  [graph path]
  (require! (map? graph) "V4 graph must be a map" {:path path})
  (let [nodes (:nodes graph)
        links (:links graph)]
    (require! (map? nodes) "V4 graph :nodes must be a map" {:path (conj path :nodes)})
    (require! (vector? links) "V4 graph :links must be a vector" {:path (conj path :links)})
    (doseq [[nid node] nodes]
      (validate-node! nid node (conj path :nodes nid)))
    (let [ids (map :id links)]
      (require! (= (count ids) (count (set ids)))
                "V4 link IDs must be unique" {:path (conj path :links)}))
    (doseq [[idx link] (map-indexed vector links)]
      (require! (map? link) "V4 link must be a map" {:path (conj path :links idx)})
      (require! (valid-eid? (:id link)) "V4 link requires id :e/<lowercase-id>"
                {:path (conj path :links idx) :id (:id link)})
      (require! (contains? link-kinds (:kind link)) "unknown V4 link kind"
                {:path (conj path :links idx) :kind (:kind link)})
      (require! (and (endpoint? (:from link)) (endpoint? (:to link)))
                "V4 link endpoints must be [node-id port]"
                {:path (conj path :links idx) :link link})
      (doseq [endpoint [(:from link) (:to link)]]
        (require! (contains? nodes (first endpoint))
                  "V4 link references an unknown node"
                  {:path (conj path :links idx) :node (first endpoint)})))
    (let [starts (filter #(= :start (:type (val %))) nodes)
          starts (map key starts)]
      (require! (= 1 (count starts)) "V4 graph requires exactly one start node"
                {:path path :starts (vec starts)}))
    (doseq [[nid node] nodes]
      (let [outs (vec (outgoing links nid :exec))
            ins (vec (incoming links nid :exec))
            type (:type node)]
        (cond
          (= :start type)
          (do (require! (empty? ins) "V4 start cannot have an exec predecessor" {:node nid})
              (require! (= 1 (count outs)) "V4 start requires one successor" {:node nid}))
          (= :branch type)
          (do
            (doseq [port [:true :false]]
              (require! (= 1 (count (filter #(= port (second (:from %))) outs)))
                        "V4 branch requires true and false outputs" {:node nid :port port}))
            (require! (= 1 (count ins)) "V4 branch requires one exec input" {:node nid}))
          (= :merge type)
          (do
            (require! (>= (count ins) 2) "V4 merge requires at least two predecessors" {:node nid})
            (require! (= 1 (count outs)) "V4 merge requires one successor" {:node nid}))
          (contains? #{:foreach :repeat} type)
          (do
            (require! (= 1 (count (filter #(= :in (second (:to %))) ins)))
                      "V4 loop requires one exec input" {:node nid})
            (doseq [port [:body :completed]]
              (require! (= 1 (count (filter #(= port (second (:from %))) outs)))
                        "V4 loop requires body and completed outputs" {:node nid :port port})))
          (= :loop-end type)
          (do
            (require! (= 1 (count ins)) "V4 loop-end requires one exec input" {:node nid})
            (require! (= 1 (count outs)) "V4 loop-end requires one continue output" {:node nid})
            (require! (= :loop-back (second (:to (first outs))))
                      "V4 loop-end must connect to a loop-back input" {:node nid}))
          (= :end type)
          (do
            (require! (empty? outs) "V4 end cannot have an exec successor" {:node nid})
            (require! (<= (count ins) 1)
                      "V4 end cannot merge multiple predecessors without a merge node"
                      {:node nid}))
          :else
          ;; component/local-set and data nodes are ordinary execution nodes
          ;; only when connected to the exec graph; the compiler validates
          ;; descriptor-specific execution requirements.  A node may not be
          ;; an implicit fan-in point: all multi-way convergence must be
          ;; represented by an explicit :merge node.
          (do
            (require! (<= (count outs) 1) "V4 ordinary node may have one successor" {:node nid})
            (require! (<= (count ins) 1)
                      "V4 ordinary node cannot merge multiple predecessors without a merge node"
                      {:node nid}))))
      (let [data-ins (incoming links nid :data)
            by-port (vals (group-by #(second (:to %)) data-ins))]
        (doseq [port-links by-port]
          (require! (<= (count port-links) 1)
                    "V4 data input may have only one predecessor"
                    {:node nid :port (second (:to (first port-links)))}))))
    (let [loop-edges (filter #(= :loop-back (second (:to %))) links)]
      (doseq [link loop-edges]
        (let [controller (first (:to link))
              source (first (:from link))]
          (require! (= :loop-end (:type (get nodes source)))
                    "V4 loop-back must originate at loop-end" {:link link})
          (require! (contains? #{:foreach :repeat} (:type (get nodes controller)))
                    "V4 loop-back must target foreach/repeat" {:link link}))))
    (validate-cycle-free! nodes links))
  graph)

(defn validate-document!
  "Validate a V4 skill or VFX document and return it unchanged."
  [document]
  (require! (map? document) "V4 document must be a map" {})
  (let [schema (:schema document)]
    (require! (contains? schemas schema) "unsupported V4 document schema"
              {:schema schema :asset (:id document)})
    (require! (keyword? (:id document)) "V4 document requires keyword :id" {:schema schema})
    (require! (not (or (string? (:program document)) (string? (:scene document))))
              "V4 document cannot contain a legacy string program or scene"
              {:schema schema :asset (:id document)})
    (case schema
      :ac/skill-v4
      (do
        (require! (map? (:skill document)) "skill-v4 requires :skill" {})
        (require! (map? (:activation document)) "skill-v4 requires :activation" {})
        (validate-parameters! (:parameters document) [:parameters])
        (validate-fields! (:state document) [:state]))

      :ac/vfx-v4
      (do
        (require! (map? (:lifecycle document)) "vfx-v4 requires :lifecycle" {})
        (require! (= #{:render} (set (keys (:graphs document))))
                  "vfx-v4 currently exposes only a render graph" {})
        (validate-fields! (:inputs document) [:inputs])
        (validate-parameters! (:parameters document) [:parameters])
        (require! (empty? (or (:state document) {}))
                  "vfx-v4 state is represented by context and must be empty" {})))
    (let [graphs (:graphs document)]
      (require! (map? graphs) "V4 document requires :graphs map" {:schema schema})
      (require! (seq graphs) "V4 document requires at least one graph" {:schema schema})
      (doseq [[graph-id graph] graphs]
        (require! (keyword? graph-id) "V4 graph IDs must be keywords" {:graph graph-id})
        (when (= :ac/vfx-v4 schema)
          (require! (= :render graph-id) "V4 VFX graph must be :render" {:graph graph-id}))
        (validate-graph! graph [:graphs graph-id])))
    document))

(defn kind [document]
  (case (:schema (validate-document! document))
    :ac/skill-v4 :skill
    :ac/vfx-v4 :vfx))

(defn semantic-document [document]
  (dissoc (validate-document! document) :editor))
