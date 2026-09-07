(ns cn.li.node.document
  "The persisted AC V3 document contract.

   This namespace deliberately contains no Minecraft or domain dependency.
   It validates the one authoring format shared by skills, VFX systems and
   reusable modules.  Lowering to the existing register compiler is a later
   step; keeping the document boundary independent lets content migration
   fail with precise asset/nid paths before any runtime is touched.")

(def schemas #{:ac/skill-v3 :ac/vfx-v3 :ac/module-v3})
(def ref-scopes #{:parameter :context :state :input :local :module-input})
(def vfx-system-stages #{:spawn :update :render})
(def vfx-lifecycle-modes #{:session :transient})
(def vfx-emitter-stages #{:emitter/spawn :emitter/update
                          :particle/spawn :particle/update :render})

(defn- fail [message data]
  (throw (ex-info message data)))

(defn- path-str [path]
  (if (seq path) (pr-str path) "<document>"))

(defn- require! [test message data]
  (when-not test (fail message data)))

(defn- node-map?
  "Return true for author-visible node maps while ignoring arbitrary data maps.
   Node IDs are document-global: a graph editor cannot address two nodes by the
   same ID without losing layout or connection information."
  [value]
  (and (map? value)
       (contains? value :nid)
       (or (contains? value :component)
           (contains? value :ref)
           (contains? value :flow))))

(defn- collect-nids
  [value path]
  (cond
    (node-map? value)
    (into [{:nid (:nid value) :path path}]
          (mapcat (fn [[key child]]
                    (collect-nids child (conj path key)))
                  value))

    (map? value)
    (mapcat (fn [[key child]]
              (collect-nids child (conj path key)))
            value)

    (vector? value)
    (mapcat (fn [[index child]]
              (collect-nids child (conj path index)))
            (map-indexed vector value))

    :else []))

(defn- validate-unique-nids!
  [document]
  (let [nids (collect-nids (dissoc document :editor) [])
        duplicates (->> nids
                        (group-by :nid)
                        (keep (fn [[nid occurrences]]
                                (when (> (count occurrences) 1)
                                  {:nid nid
                                   :paths (mapv :path occurrences)})))
                        vec)]
    (require! (empty? duplicates)
              "V3 node IDs must be unique within a document"
              {:duplicates duplicates})))

(defn- valid-nid? [nid]
  (and (keyword? nid)
       (= "n" (namespace nid))
       (re-matches #"[a-z0-9][a-z0-9-]{2,31}" (name nid))))

(defn- validate-ref! [value path]
  (when (map? value)
    (when-let [ref (:ref value)]
      (require! (and (vector? ref)
                     (<= 2 (count ref))
                     (contains? ref-scopes (first ref)))
                "invalid V3 reference"
                {:path path :ref ref})
      (doseq [part (rest ref)]
        (require! (or (keyword? part) (string? part) (integer? part))
                  "V3 reference path must be keyword/string/integer"
                  {:path path :ref ref}))))
  value)

(declare validate-node! validate-value!)

(defn- validate-value!
  "Validate a value which may contain nested author-visible nodes.
   Component arguments and input payloads are deliberately recursive: the
   graph editor addresses every nested expression by nid, not only the
   top-level statement."
  [value path]
  (cond
    (and (map? value) (contains? value :nid))
    (validate-node! value path)

    (map? value)
    (do
      (validate-ref! value path)
      (doseq [[key child] value]
        (validate-value! child (conj path key)))
      value)

    (vector? value)
    (do
      (doseq [[index child] (map-indexed vector value)]
        (validate-value! child (conj path index)))
      value)

    :else value))

(defn- validate-node-vector! [nodes path]
  (require! (vector? nodes) "V3 node body must be a vector" {:path path})
  (doseq [[index node] (map-indexed vector nodes)]
    (validate-node! node (conj path index)))
  nodes)

(defn- validate-input-map! [inputs path]
  (when (some? inputs)
    (require! (map? inputs) "V3 :inputs must be a map" {:path path})
    (doseq [[key value] inputs]
      (require! (keyword? key) "V3 input names must be keywords"
                {:path (conj path key) :value key})
      (validate-value! value (conj path key))))
  inputs)

(defn- validate-flow! [node path]
  (let [flow (:flow node)]
    (case flow
      :if
      (do
        (require! (map? (:condition node))
                  "V3 :if requires a condition node" {:path path})
        (validate-node! (:condition node) (conj path :condition))
        (validate-node-vector! (:then node) (conj path :then))
        (validate-node-vector! (:else node) (conj path :else)))

      :when
      (do
        (require! (map? (:condition node))
                  "V3 :when requires a condition node" {:path path})
        (validate-node! (:condition node) (conj path :condition))
        (validate-node-vector! (:do node) (conj path :do)))

      :foreach
      (do
        (require! (map? (:collection node))
                  "V3 :foreach requires a collection expression" {:path path})
        (validate-node! (:collection node) (conj path :collection))
        (require! (keyword? (:as node))
                  "V3 :foreach :as must be a keyword" {:path path})
        (when (:index-as node)
          (require! (keyword? (:index-as node))
                    "V3 :foreach :index-as must be a keyword" {:path path}))
        (require! (and (integer? (:limit node)) (pos? (:limit node)))
                  "V3 :foreach requires a positive static :limit" {:path path})
        (validate-node-vector! (:do node) (conj path :do)))

      :repeat
      (do
        (require! (and (integer? (:count node)) (pos? (:count node)))
                  "V3 :repeat requires a positive static :count" {:path path})
        (validate-node-vector! (:do node) (conj path :do)))

      :finish
      (do
        (require! (map? (:result node))
                  "V3 :finish requires a result map" {:path path})
        (validate-value! (:result node) (conj path :result)))

      :bind
      (do
        (require! (keyword? (:name node))
                  "V3 :bind requires a local :name" {:path path})
        (require! (contains? node :value)
                  "V3 :bind requires a :value" {:path path})
        (validate-value! (:value node) (conj path :value)))

      (fail "unknown V3 flow node" {:path path :flow flow}))))

(defn validate-node!
  "Validate one author-visible node and all structured child nodes.
   Returns the original node for convenient use in a transducer/pipeline."
  [node path]
  (require! (map? node) "V3 node must be a map" {:path path :node node})
  (require! (valid-nid? (:nid node))
            "V3 node requires nid :n/<lowercase-id>"
            {:path path :nid (:nid node)})
  (validate-ref! node path)
  (cond
    (:flow node) (validate-flow! node path)
    (:ref node)
    (do
      (require! (nil? (:component node))
                "V3 reference cannot also be a component node"
                {:path path :node node})
      node)
    (:component node)
    (do
      (require! (keyword? (:component node))
                "V3 :component must be a keyword" {:path path})
      (validate-input-map! (:inputs node) (conj path :inputs))
      (when (some? (:args node))
        (require! (vector? (:args node))
                  "V3 component :args must be a vector" {:path path})
        (validate-value! (:args node) (conj path :args)))
      (when-let [bind (:bind node)]
        (require! (map? bind) "V3 :bind must be a map" {:path path})
        (doseq [[port local] bind]
          (require! (and (keyword? port) (keyword? local))
                    "V3 bind ports and locals must be keywords"
                    {:path (conj path :bind) :port port :local local})))
      node)
    :else
    (fail "V3 node must be a component, ref or flow node"
          {:path path :node node})))

(defn- validate-field-specs! [fields path]
  (when (some? fields)
    (require! (map? fields) "V3 field declarations must be maps" {:path path})
    (doseq [[key spec] fields]
      (require! (and (keyword? key) (map? spec) (contains? spec :type))
                "V3 field declaration requires keyword and :type"
                {:path (conj path key) :spec spec})))
  fields)

(defn- validate-parameters! [parameters path]
  (when (some? parameters)
    (require! (map? parameters) "V3 :parameters must be a map" {:path path})
    (doseq [[key spec] parameters]
      (require! (and (keyword? key) (map? spec) (contains? spec :type)
                     (contains? spec :default))
                "V3 parameter requires :type and :default"
                {:path (conj path key) :spec spec})))
  parameters)

(defn- validate-skill! [document]
  (require! (map? (:skill document)) "skill-v3 requires :skill" {})
  (require! (map? (:activation document)) "skill-v3 requires :activation" {})
  (validate-parameters! (:parameters document) [:parameters])
  (validate-field-specs! (:state document) [:state])
  (let [entries (:entries document)]
    (require! (and (map? entries) (seq entries))
              "skill-v3 requires non-empty :entries" {:path [:entries]})
    (doseq [[entry spec] entries]
      (require! (and (keyword? entry) (map? spec) (keyword? (:on spec)))
                "skill-v3 entry requires keyword :on" {:path [:entries entry]})
      (when (:where spec)
        (validate-node! (:where spec) [:entries entry :where]))
      (validate-node-vector! (:do spec) [:entries entry :do])))
  document)

(defn- validate-stage-map! [stages allowed path]
  (when (some? stages)
    (require! (map? stages) "V3 stage collection must be a map" {:path path})
    (doseq [[stage nodes] stages]
      (require! (contains? allowed stage) "unknown V3 stage" {:path (conj path stage)})
      (validate-node-vector! nodes (conj path stage))))
  stages)

(defn- validate-vfx! [document]
  (validate-field-specs! (:inputs document) [:inputs])
  (validate-parameters! (:parameters document) [:parameters])
  (validate-field-specs! (:state document) [:state])
  (let [lifecycle (:lifecycle document)]
    (require! (map? lifecycle) "vfx-v3 requires :lifecycle" {})
    (require! (contains? vfx-lifecycle-modes (:mode lifecycle))
              "vfx-v3 lifecycle requires :mode :session or :transient"
              {:path [:lifecycle :mode] :mode (:mode lifecycle)}))
  (validate-stage-map! (:system document) vfx-system-stages [:system])
  (when-let [emitters (:emitters document)]
    (require! (vector? emitters) "vfx-v3 :emitters must be a vector" {:path [:emitters]})
    (doseq [[index emitter] (map-indexed vector emitters)]
      (require! (and (map? emitter) (keyword? (:id emitter))
                     (integer? (:capacity emitter)) (pos? (:capacity emitter)))
                "V3 emitter requires keyword :id and positive integer :capacity"
                {:path [:emitters index] :emitter emitter})
      (validate-stage-map! (:stages emitter) vfx-emitter-stages
                           [:emitters index :stages])))
  document)

(defn- validate-module! [document]
  (require! (keyword? (:domain document)) "module-v3 requires keyword :domain" {})
  (require! (contains? #{:pure :flow :particle} (:execution document))
            "module-v3 :execution must be :pure, :flow or :particle"
            {:execution (:execution document)})
  (validate-field-specs! (:inputs document) [:inputs])
  (validate-field-specs! (:outputs document) [:outputs])
  (let [body (:body document)]
    (require! (map? body) "module-v3 requires :body" {:path [:body]})
    (if (= :pure (:execution document))
      (do
        (require! (map? (:value body)) "pure module body requires :value node" {})
        (validate-node! (:value body) [:body :value]))
      (validate-node-vector! (:do body) [:body :do])))
  document)

(defn validate-document!
  "Validate a persisted V3 asset. Returns the original document or throws
   ex-info whose data contains :asset and a precise :path."
  [document]
  (require! (map? document) "V3 document must be a map" {})
  (let [schema (:schema document)
        id (:id document)]
    (require! (contains? schemas schema) "unsupported V3 document schema"
              {:schema schema :asset id})
    (require! (keyword? id) "V3 document requires keyword :id" {:schema schema})
    (require! (not (or (string? (:program document))
                       (string? (:scene document))))
              "V3 document cannot contain a legacy string program or scene"
              {:schema schema :asset id
               :legacy-fields (select-keys document [:program :scene])})
    (case schema
      :ac/skill-v3 (validate-skill! document)
      :ac/vfx-v3 (validate-vfx! document)
      :ac/module-v3 (validate-module! document))
    (validate-unique-nids! document))
  document)

(defn kind [document]
  (case (:schema (validate-document! document))
    :ac/skill-v3 :skill
    :ac/vfx-v3 :vfx
    :ac/module-v3 :module))

(defn semantic-document [document]
  (validate-document! document)
  (dissoc document :editor))
