(ns cn.li.presentation.compiler.artifact
  "Deterministic compiler for Presentation UI artifacts.

   This namespace is build-only. The runtime consumes the serialized EDN
   produced here (a flat, struct-of-arrays node table in pre-order) and
   never loads presentation-compiler.

   Source vocabulary lowers to a small physical opcode set before
   flattening: :row/:column/:stack/:absolute/:clip/:box collapse into one
   generic container shape distinguished only by flags+direction;
   :button/:text-input/:glow-line expand into a small composite subtree of
   real primitives at compile time, so the runtime engine only ever needs
   to understand UiOp's 9 draw primitives plus \"not drawable.\" See
   docs/06-gui/PRESENTATION_V3.md for the full primitive-lowering table."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.presentation.core.artifact-schema :as schema])
  (:import [cn.li.mcmod.runtime.ui UiOp]
           [java.io File]
           [java.math BigInteger]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]))

(def artifact-magic :pui5)
(def artifact-schema 5)
(def ui-source-schema 2)

(def semantic-roles
  #{:generic :heading :button :textbox :dialog :list :list-item :image :slot :progress :scrollbar})

(defn- fail [path message]
  (throw (ex-info (str path ": " message) {:path path})))

;; ============================== flag / mode bits ==============================
;; The five line-format encoding tables (flag-bits/direction->int/justify->
;; int/align->int/size-mode->int) live in presentation-core's artifact_schema
;; (P5) -- this compiler and presentation-core's own test_artifact.clj both
;; consume the same numbers, extracted rather than duplicated.

(def ^:private flags->int schema/flags->int)
(def ^:private size-mode->int schema/size-mode->int)
(def ^:private direction->int schema/direction->int)
(def ^:private justify->int schema/justify->int)
(def ^:private align->int schema/align->int)

(defn- lookup! [table k path]
  (if (contains? table k)
    (get table k)
    (fail path (str "unsupported value " (pr-str k) ", expected one of " (sort (keys table))))))

;; ============================== value parsing ==============================

(defn- parse-size [v]
  (cond
    (nil? v) [:auto 0.0]
    (= :auto v) [:auto 0.0]
    (= :fill v) [:fill 1.0]
    (number? v) [:fixed (double v)]
    (and (vector? v) (= :pct (first v))) [:pct (double (second v))]
    (and (vector? v) (= :weight (first v))) [:weight (double (second v))]
    :else (fail "layout" (str "invalid size spec " (pr-str v)))))

(defn- parse-sides [v]
  (cond
    (nil? v) [0.0 0.0 0.0 0.0]
    (number? v) (let [n (double v)] [n n n n])
    (map? v) [(double (or (:l v) (:left v) 0.0))
              (double (or (:t v) (:top v) 0.0))
              (double (or (:r v) (:right v) 0.0))
              (double (or (:b v) (:bottom v) 0.0))]
    :else (fail "layout" (str "invalid box spec " (pr-str v)))))

(defn- component255 [v default]
  (let [n (double (or v default))]
    (long (if (<= 0.0 n 1.0) (Math/round (* 255.0 n)) (Math/round n)))))

(defn- parse-static-rgba
  "Matches the pre-rewrite paint.clj `rgba` helper's coercion exactly:
   an integer is used as-is, a map/vector's 0..1 components scale to
   0..255, anything else falls back to opaque white."
  [v]
  (cond
    (nil? v) 0xFFFFFFFF
    (integer? v) (bit-and v 0xFFFFFFFF)
    (map? v) (bit-or (bit-shift-left (component255 (:a v) 255) 24)
                     (bit-shift-left (component255 (:r v) 255) 16)
                     (bit-shift-left (component255 (:g v) 255) 8)
                     (component255 (:b v) 255))
    (vector? v) (let [[r g b a] v]
                  (bit-or (bit-shift-left (component255 a 255) 24)
                          (bit-shift-left (component255 r 255) 16)
                          (bit-shift-left (component255 g 255) 8)
                          (component255 b 255)))
    :else 0xFFFFFFFF))

(defn- normalize-attr-key
  "Compile-time bind-attribute aliases. :alpha is an alternate spelling of
   :rgba the pre-rewrite paint.clj accepted (see combat_hud.ui.edn's
   background mask); folding it here means the runtime resolver only ever
   has to know one canonical attribute name per BindAttr slot."
  [k]
  (case k :alpha :rgba k))

;; ============================== node lowering ==============================
;; A "physical" node is a fully-normalized map ready for flattening:
;; {:phys-op int-or-nil :flags #{...} :direction :justify :align-items
;;  :align-self :margin [l t r b] :padding [l t r b] :min-w :min-h :max-w
;;  :max-h :width [mode val] :height [mode val] :gap :aspect :x :y
;;  :font-size :rgba :text :resource :bind {attr-kw path} :on {event action}
;;  :key :children [physical...]}

(def ^:private default-physical
  {:margin [0.0 0.0 0.0 0.0] :padding [0.0 0.0 0.0 0.0]
   :min-w 0.0 :min-h 0.0 :max-w 0.0 :max-h 0.0
   :width [:auto 0.0] :height [:auto 0.0] :gap 0.0 :aspect 0.0
   :x 0.0 :y 0.0 :justify :start :align-items :start :align-self :inherit
   :font-size 8.0 :rgba 0xFFFFFFFF :resource nil :scrollbar nil
   :bind {} :on {} :key nil :text nil :children []})

(defn- base-fields [source]
  (when-not (map? source) (fail "node" (str "must be a map, got " (pr-str source))))
  (let [layout (or (:layout source) {})
        style (or (:style source) {})
        bind (or (:bind source) {})
        on (or (:on source) {})
        scrollbar (let [sb (:scrollbar style)] (when (map? sb) sb))]
    (when-not (map? bind) (fail "node.bind" "must be a map"))
    (when-not (map? on) (fail "node.on" "must be a map"))
    (merge default-physical
           {:margin (parse-sides (:margin layout))
            :padding (parse-sides (:padding layout))
            :min-w (double (or (:min-width layout) 0.0))
            :min-h (double (or (:min-height layout) 0.0))
            :max-w (double (or (:max-width layout) 0.0))
            :max-h (double (or (:max-height layout) 0.0))
            :width (parse-size (:width layout))
            :height (parse-size (:height layout))
            :gap (double (or (:gap layout) 0.0))
            :aspect (double (or (:aspect layout) 0.0))
            :x (double (or (:x layout) 0.0))
            :y (double (or (:y layout) 0.0))
            :justify (or (:justify layout) :start)
            :align-items (or (:align layout) :start)
            :align-self (or (:align-self layout) :inherit)
            :font-size (double (or (:font-size style) 8.0))
            :rgba (parse-static-rgba (:rgba style))
            :resource (:resource style)
            :scrollbar scrollbar
            :bind (into {} (map (fn [[k v]] [(normalize-attr-key k) v])) bind)
            :on on
            :key (:key source)
            :semantics (:semantics source)
            :children (or (:children source) [])})))

(declare lower-node)

(defn- lower-children [physical]
  (mapv lower-node (:children physical)))

(defn- lower-container [physical direction extra-flags]
  (assoc physical
         :phys-op nil
         :flags extra-flags
         :direction direction
         :children (lower-children physical)))

(defn- lower-button [physical]
  (let [bind (:bind physical)
        text-path (get bind :text)]
    (assoc physical
           :phys-op nil :flags #{:hit-testable} :direction :none
           :bind (select-keys bind [:visible])
           :children
           [(merge default-physical
                   {:phys-op UiOp/RECT :flags #{} :direction :none
                    :width [:fill 1.0] :height [:fill 1.0]
                    :rgba (:rgba physical)})
            (merge default-physical
                   {:phys-op UiOp/TEXT :flags #{} :direction :none
                    :x 4.0 :y 4.0
                    :font-size (:font-size physical)
                    :bind (if text-path {:text text-path} {})})])))

(defn- lower-text-input [physical]
  (let [bind (:bind physical)
        text-path (get bind :text)]
    (assoc physical
           :phys-op nil :flags #{:hit-testable :focusable} :direction :none
           :bind (select-keys bind [:visible])
           :children
           [(merge default-physical
                   {:phys-op UiOp/RECT :flags #{} :direction :none
                    :width [:fill 1.0] :height [:fill 1.0]
                    :rgba 0x66000000})
            (merge default-physical
                   {:phys-op UiOp/TEXT :flags #{} :direction :none
                    :x 4.0 :y 4.0
                    :font-size (:font-size physical)
                    :bind (if text-path {:text text-path} {})})])))

(defn- lower-glow-line
  "Approximation: the pre-rewrite :glow-line composed 8 corner/edge glow
   textures plus a center line from :bind x0/x1/line-y/line-w/glow-sz,
   none of which the v3 primitive set models. Renders as a single
   nine-slice spanning the node's own declared box instead - a real,
   accepted visual regression (2 uses, both in tutorial.ui.edn, tracked
   as a known follow-up rather than blocking the schema migration)."
  [physical]
  (assoc physical
         :phys-op UiOp/NINE :flags #{} :direction :none :children []
         :bind (select-keys (:bind physical) [:visible])))

(defn- with-on-hit-testable
  "Any node that declares :on handlers must be hit-testable. :button/:text-input
   already set the flag explicitly; :text/:image/:composite (and peers) used by
   tutorial list rows / tags / arrow buttons only carry :on in source and would
   otherwise compile to flags=0 — HitKernel then never sees them, so hover and
   activate silently no-op (pre-rewrite hit-action keyed off :on directly).
   Scrollbar tracks also need hit-testable so drag/click can drive the linked
   :scroll offset."
  [physical]
  (cond-> physical
    (seq (:on physical)) (update :flags (fnil conj #{}) :hit-testable)
    (map? (:scrollbar physical))
    (-> (update :flags (fnil conj #{}) :hit-testable :scrollbar))))

(defn- lower-node [source]
  (let [physical (base-fields source)
        raw-type (:type source)
        type (if raw-type (keyword (name raw-type)) (fail "node" "missing :type"))
        layout (or (:layout source) {})]
    (with-on-hit-testable
     (case type
       (:row :column) (lower-container physical type #{:has-direction})
       :box (lower-container physical (or (:direction layout) :none)
                             (cond-> #{} (:direction layout) (conj :has-direction)))
       (:absolute :stack) (lower-container physical :none #{})
       :clip (lower-container physical (or (:direction layout) :none)
                              (cond-> #{:has-clip} (:direction layout) (conj :has-direction)))
       :scroll (lower-container physical (or (:direction layout) :column)
                                #{:has-clip :is-scroll :is-collection :has-direction})
       :repeater (lower-container physical (or (:direction layout) :column)
                                  #{:is-collection :has-direction})
       :grid (lower-container physical (or (:direction layout) :row)
                              #{:is-collection :has-direction})
       :portal (assoc physical :phys-op nil :flags #{} :direction :none :children [])

       :rect (assoc physical :phys-op UiOp/RECT :flags #{} :direction :none :children [])
       :line (assoc physical :phys-op UiOp/RECT :flags #{} :direction :none :children [])
       :gradient (assoc physical :phys-op UiOp/GRADIENT :flags #{} :direction :none :children [])
       :image (assoc physical :phys-op UiOp/IMAGE :flags #{} :direction :none :children [])
       :nine-slice (assoc physical :phys-op UiOp/NINE :flags #{} :direction :none :children [])
       :text (assoc physical :phys-op UiOp/TEXT :flags #{} :direction :none :children []
                    :text (:text source))
       (:progress :radial-progress)
       (assoc physical :phys-op UiOp/PROGRESS :flags #{:hit-testable} :direction :none :children [])
       :item-preview (assoc physical :phys-op UiOp/ITEM :flags #{} :direction :none :children [])
       :model-preview (assoc physical :phys-op UiOp/MODEL :flags #{} :direction :none :children [])
       :composite (assoc physical :phys-op UiOp/COMPOSITE :flags #{} :direction :none :children [])
       :slot-anchor (assoc physical :phys-op UiOp/RECT :flags #{} :direction :none :children []
                           :rgba 0x22000000)
       :transform (lower-container physical :none #{})

       :button (lower-button physical)
       :text-input (lower-text-input physical)
       :glow-line (lower-glow-line physical)

       (fail "node.type" (str "unsupported v3 primitive " type))))))

;; ============================== flatten ==============================

(defn- flatten-tree
  "Pre-order DFS. Returns {:rows [...] :parent [...] :first-child [...]
   :next-sibling [...] :child-count [...]} — rows are physical node maps
   with :children stripped, indexed by their position in the vector."
  [root]
  (let [rows (transient [])
        parent (transient [])
        first-child (transient [])
        next-sibling (transient [])
        child-count (transient [])]
    (letfn [(walk [node parent-index]
              (let [idx (count rows)]
                (conj! rows (dissoc node :children))
                (conj! parent parent-index)
                (conj! first-child -1)
                (conj! next-sibling -1)
                (conj! child-count (count (:children node)))
                (let [child-indices (mapv #(walk % idx) (:children node))]
                  (when (seq child-indices)
                    (assoc! first-child idx (first child-indices))
                    (doseq [[a b] (partition 2 1 child-indices)]
                      (assoc! next-sibling a b)))
                  idx)))]
      (walk root -1)
      {:rows (persistent! rows)
       :parent (persistent! parent)
       :first-child (persistent! first-child)
       :next-sibling (persistent! next-sibling)
       :child-count (persistent! child-count)})))

;; ============================== bindings & dep-mask ==============================

(defn- state-path? [v] (and (vector? v) (= :state (first v)) (<= 2 (count v))))

(defn- assign-own-binding-ids
  "For each row, the set of global binding ids its own :bind values name
   (only :state-scoped paths get a global id, matching the pre-rewrite
   collect-binding-paths — :item/:parent-scoped values vary per collection
   instance and don't have one stable path to dedup against)."
  [rows]
  (let [bindings (atom {})
        own (mapv (fn [node]
                    (into #{}
                          (keep (fn [[_ path]]
                                  (when (state-path? path)
                                    (or (get @bindings path)
                                        (let [id (count @bindings)]
                                          (swap! bindings assoc path id)
                                          id)))))
                          (:bind node)))
                  rows)]
    {:own-ids own
     :bindings (->> @bindings (sort-by val) (mapv (fn [[path id]] {:id id :path path})) vec)}))

(defn- assign-action-ids [rows]
  (let [actions (atom {})
        own (mapv (fn [node]
                    (into {}
                          (map (fn [[event action]]
                                 (let [action (if (keyword? action) action (keyword (str action)))
                                       id (or (get @actions action) (count @actions))]
                                   (swap! actions assoc action id)
                                   [event id])))
                          (:on node)))
                  rows)]
    {:own-action-ids own
     :actions (->> @actions (sort-by val) (mapv (fn [[action id]] {:id id :name action})) vec)}))

(defn- compute-dep-masks
  "Post-order subtree closure via a single descending pass: children always
   have a higher pre-order index than their parent, so by the time this
   loop reaches index i every descendant of i (index > i) has already
   folded its bits upward, and mask[i] is fully finalized before it in
   turn folds into mask[parent[i]]. O(n * words), no recursion."
  [rows parent own-ids binding-count]
  (let [n (count rows)
        words (max 1 (int (Math/ceil (/ (max 1 binding-count) 64.0))))
        mask (long-array (* n words))]
    (dotimes [i n]
      (doseq [id (nth own-ids i)]
        (let [w (quot (int id) 64) b (mod (int id) 64) idx (+ (* i words) w)]
          (aset mask idx (bit-or (aget mask idx) (bit-shift-left 1 (int b)))))))
    (doseq [i (range (dec n) 0 -1)]
      (let [p (int (nth parent i))]
        (when (>= p 0)
          (dotimes [w words]
            (let [ci (+ (* i words) w) pi (+ (* p words) w)]
              (aset mask pi (bit-or (aget mask pi) (aget mask ci))))))))
    {:mask (vec mask) :words words}))

;; ============================== table dedup ==============================

(defn- dedup-table
  "Assigns each distinct value (by structural equality) a stable index in
   first-seen order; returns [index-per-row table-vector]."
  [values]
  (let [seen (atom {})
        indices (mapv (fn [v]
                        (if (nil? v)
                          -1
                          (or (get @seen v)
                              (let [id (count @seen)]
                                (swap! seen assoc v id)
                                id))))
                      values)]
    [indices (mapv first (sort-by second @seen))]))

;; ============================== flat-field extraction ==============================

(defn- flat-fields [rows]
  (let [text-values (mapv :text rows)
        [text-idx string-table] (dedup-table text-values)
        resource-values (mapv :resource rows)
        [res-idx resources] (dedup-table resource-values)]
    {:node/op (mapv #(int (or (:phys-op %) -1)) rows)
     :node/flags (mapv #(flags->int (:flags %)) rows)
     :node/box (vec (mapcat (fn [{:keys [margin padding min-w min-h max-w max-h]}]
                              (concat margin padding [min-w min-h max-w max-h]))
                            rows))
     :node/width-mode (mapv #(size-mode->int (first (:width %))) rows)
     :node/width-value (mapv #(second (:width %)) rows)
     :node/height-mode (mapv #(size-mode->int (first (:height %))) rows)
     :node/height-value (mapv #(second (:height %)) rows)
     :node/gap (mapv :gap rows)
     :node/aspect (mapv :aspect rows)
     :node/declared-x (mapv :x rows)
     :node/declared-y (mapv :y rows)
     :node/direction (mapv #(lookup! direction->int (:direction %) "node.layout.direction") rows)
     :node/justify (mapv #(lookup! justify->int (:justify %) "node.layout.justify") rows)
     :node/align-items (mapv #(lookup! align->int (:align-items %) "node.layout.align") rows)
     :node/align-self (mapv #(lookup! align->int (:align-self %) "node.layout.align-self") rows)
     :node/font-size (mapv :font-size rows)
     :node/rgba (mapv :rgba rows)
     :node/text-index (vec text-idx)
     :node/res (vec res-idx)
     :string-table (vec string-table)
     :resources (vec resources)
     :node/key (mapv :key rows)
     :node/bind-map (mapv :bind rows)
     :node/on-map (mapv :on rows)
     :node/scrollbar (mapv :scrollbar rows)
     :node/semantics (mapv (fn [n]
                              (let [s (:semantics n)]
                                (when (some? s)
                                  (when-not (map? s) (fail "node.semantics" "must be a map"))
                                  (when-let [role (:role s)]
                                    (when-not (contains? semantic-roles role)
                                      (fail "node.semantics.role" (str "unsupported role " role))))
                                  s)))
                            rows)}))

;; ============================== compile ==============================

(defn- canonicalize [value]
  (cond
    (map? value) (into (sorted-map) (map (fn [[k v]] [k (canonicalize v)])) value)
    (set? value) (vec (sort-by pr-str (map canonicalize value)))
    (vector? value) (mapv canonicalize value)
    (seq? value) (mapv canonicalize value)
    :else value))

(defn- source-hash [source]
  (let [bytes (.getBytes (pr-str (canonicalize source)) StandardCharsets/UTF_8)
        digest (java.security.MessageDigest/getInstance "SHA-256")]
    (format "%064x" (BigInteger. 1 (.digest digest bytes)))))

(defn- validate-source! [source path]
  (when-not (map? source) (fail path "source must be a map"))
  (when-not (or (:view/id source) (:view-id source)) (fail path "requires :view/id"))
  (when-not (or (:root source) (:nodes source)) (fail path "requires :root"))
  (let [schema (:ui/schema source)]
    (when-not (= ui-source-schema schema)
      (fail (str path ".ui/schema")
            (if (nil? schema)
              (str "required source schema " ui-source-schema)
              (str "unsupported source schema " schema " (v3 sources must declare " ui-source-schema ")")))))
  source)

(defn compile-source [source path]
  (validate-source! source path)
  (let [view-id (or (:view/id source) (:view-id source))
        root-source (or (:root source) (:nodes source))
        physical-root (lower-node root-source)
        flat (flatten-tree physical-root)
        rows (:rows flat)
        {:keys [own-ids bindings]} (assign-own-binding-ids rows)
        {:keys [own-action-ids actions]} (assign-action-ids rows)
        {:keys [mask words]} (compute-dep-masks rows (:parent flat) own-ids (count bindings))
        fields (flat-fields rows)]
    (canonicalize
     (merge fields
            {:magic artifact-magic
             :schema artifact-schema
             :ui/schema ui-source-schema
             :view-id view-id
             :source-hash (source-hash source)
             :host (or (:host source) {})
             :state-schema (or (:state-schema source) {})
             :node-count (count rows)
             :node/parent (:parent flat)
             :node/first-child (:first-child flat)
             :node/next-sibling (:next-sibling flat)
             :node/child-count (:child-count flat)
             :node/dep-mask mask
             :mask-words words
             :binding-count (count bindings)
             :bindings bindings
             :actions actions
             :node/action-ids own-action-ids
             :style-table []
             :focus-order []
             :semantics (or (:semantics source) {})
             :capabilities (or (:capabilities source) (:requires-capabilities source) #{})}))))

;; ============================== directory compilation ==============================

(defn- write-edn! [^Path output value]
  (Files/createDirectories (.getParent output)
                           (make-array java.nio.file.attribute.FileAttribute 0))
  (spit (.toFile output) (str (pr-str value) "\n") :encoding "UTF-8"))

(defn- clear-output! [^File root]
  (when (.exists root)
    (doseq [^File file (reverse (sort-by #(.getPath ^File %) (file-seq root)))]
      (.delete file))))

(defn compile-directory! [source-root output-root content-id]
  (let [source-root (.toPath (io/file source-root))
        output-file (io/file output-root)
        _ (when-not (and content-id (not (str/blank? (str content-id))))
            (fail "content-id" "must be a non-empty identifier"))
        content-id (str content-id)
        _ (clear-output! output-file)
        output-root (.toPath output-file)
        files (->> (file-seq (.toFile source-root))
                   (filter #(.isFile ^File %))
                   (filter #(str/ends-with? (.getName ^File %) ".ui.edn"))
                   (sort-by #(.toString (.toPath ^File %))))
        entries (for [^File file files
                      :let [source (edn/read-string (slurp file :encoding "UTF-8"))
                            relative-source (.replace (.toString (.relativize source-root (.toPath file))) "\\" "/")
                            artifact (assoc (compile-source source relative-source)
                                            :content-id content-id)
                            view-id (:view-id artifact)
                            relative (str "assets/" content-id "/presentation-compiled/"
                                          (name (or (namespace view-id) content-id)) "/"
                                          (name view-id) ".uic.edn")
                            target (.resolve output-root relative)]]
                  (do
                    (write-edn! target artifact)
                    [view-id {:resource relative
                              :content-id content-id
                              :source-hash (:source-hash artifact)
                              :schema artifact-schema
                              :host (:host artifact)}]))
        manifest {:magic :pui5-catalog
                  :schema artifact-schema
                  :content-id content-id
                  :content-ids [content-id]
                  :views (into (sorted-map) entries)
                  :generated-by :presentation-compiler}
        ;; Namespaced by content-id (P5): compile-directory! runs once per
        ;; content module (ac/build.gradle's compilePresentationViews task,
        ;; one per module), and every module's generated resources get
        ;; merged into the SAME mod jar's classpath. A fixed "catalog.edn"
        ;; name meant a second content module's manifest would silently
        ;; overwrite the first's at jar-merge time; presentation-core's
        ;; merge-manifests reads each tenant's own file by this same path.
        manifest-path (.resolve output-root
                                (str "META-INF/presentation/" content-id ".catalog.edn"))]
    (write-edn! manifest-path (canonicalize manifest))
    manifest))
