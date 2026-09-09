(ns cn.li.ac.ability.client.screens.node-editor-reactive
  "Presentation Runtime controller for the node editor -- both the skill
   mode and the scene/VFX mode. Follows preset-
   editor-reactive's simpler self-contained pattern (an active-mounts
   atom keyed by player-uuid), not skill-tree's managed-screens/
   read-model machinery -- this is a standalone dev tool, not tied to
   persistent per-player gameplay state.

   The skill/scene difference is entirely in mode-opts below: a small
   table of {:vocab :capabilities :fns :field}. Everything else in this
   namespace (graph rendering, drag, save, diagnostics) is mode-agnostic
   -- the scene/VFX mode's deliverable is proving that claim, not new plumbing.
   Scene mode's :capabilities is PER-FILE (an effect's own :inputs :spawn
   declaration merged with vfx-core's universal :age/:progress -- see
   vfx-api's scene-capabilities-for), unlike skill mode's fixed table, so
   mode-opts takes the just-opened wrapper doc as an argument.

   SCOPE (real, deliberate boundaries for this first iteration -- see
   the plan's own commit history for why each was drawn where it was):
    - Renders the EXEC statement chain as the primary flow, plus referenced
      pure-expression nodes in a secondary column; semantic input/output
      pins can be dragged to update expression references.
    - Node MOVE (drag), SELECT, and canvas PAN (empty-canvas drag, via
      cn.li.ability.editor.hit's :panning mode, offsetting :viewport --
      see pan-canvas-items) are wired. Palette clicks and drag-and-drop now
      insert a call plus default literal inputs; a ghost/drop-zone state gives
      feedback and a click with no movement remains the shortcut. The existing
      neutral :scroll event drives cursor-anchored 50%-200% zoom; pinch remains
      deferred until Presentation exposes a distinct gesture contract.
    - open! takes an EXPLICIT absolute file path from the caller, not a
     guessed game-directory/source-tree location: resolving 'where does
     the mod's source tree live relative to the running game' is itself
     a runtime detail this environment cannot verify without launching
     the game, so it is left as a caller-supplied parameter rather than
     guessed at and shipped unverified. :editor/save writes a workspace
     sibling and is preferentially reloaded by a later open! of the same
     path; :editor/export is the separate, explicit action that
     overwrites the real source file at that path (document/save's own
     docstring documents this as the intended two-path design).
   - Scene preview is deliberately isolated from production: the Preview
     action starts a short-lived client-vfx-v2 runtime using the scene's
     declared inputs, ticks it from the screen refresh hook, and tears it
     down on stop/close. It is not crosshair placement yet; the preview is
     a lifecycle/safety slice until a client camera anchor contract exists.
      Skill mode never exposes this action."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.vfx.fx-catalog :as fx-catalog]
            [cn.li.ability.editor.document :as document]
            [cn.li.ability.editor.graph :as graph]
            [cn.li.ability.editor.check :as check]
            [cn.li.ability.editor.render :as render]
            [cn.li.ability.editor.hit :as hit]
            [cn.li.ability.editor.palette :as palette]
            [cn.li.combat.api :as combat-api]
            [cn.li.ability.client-vfx-v2 :as vfx-client]
            [cn.li.node.api :as node-api]
            [cn.li.node.cost :as node-cost]
            [cn.li.node.ops :as ops]
            [cn.li.vfx.api :as vfx-api])
  (:import [java.nio.file Files StandardCopyOption]))

(defonce ^:private active-mounts (atom {}))

(defn default-sample-skill-resource-path
  "A classpath-relative V4 content resource (e.g. \"ac/skills-v4/thunder-bolt.edn\") -> its absolute on-disk path, or nil. Public because there is
   no in-game file-picker UI yet (a real follow-up, not part of this
   screen's own scope) -- both the G keybind (cn.li.ac.input-ids) and
   the editor_dev_tool item share this to pick a fixed default file to
   open, rather than each guessing its own.

   Every other content reader in this mod (cn.li.ac.ability.skills-
   catalog's own load-resource) goes through (io/resource ...) + slurp
   precisely because slurp reads equally well from a dev-run's on-disk
   resources or a packaged jar's zip entries -- but this screen needs to
   WRITE sibling files (editor-workspace/, layout/) next to the opened
   file, which only makes sense against a real file:// resource, not a
   jar entry. Returns nil rather than guessing at a working directory
   (see this namespace's own docstring on why open! never does that
   itself) when the resource is not on disk, so the caller can report a
   clear reason instead of failing deep inside io/file."
  [resource]
  (when-let [^java.net.URL url (io/resource resource)]
    (when (= "file" (.getProtocol url))
      (.getAbsolutePath (io/as-file url)))))

(defn- mode-opts
  "mode (:skill or :scene), document (the just-opened V4 map, needed for
   scene mode's per-file capabilities) -> {:vocab :capabilities :fns
   :category-for}."
  [mode wrapper-doc]
  (case mode
    :skill {:vocab combat-api/skill-vocab
            :capabilities combat-api/skill-capability-type
            :fns combat-api/skill-lib-fns
            :category-for combat-api/skill-vocab-category-for}
    :scene {:vocab vfx-api/scene-vocab
            :capabilities
            (let [decls (or (get-in wrapper-doc [:inputs :spawn])
                            (:inputs wrapper-doc)
                            {})
                  types (into {}
                              (map (fn [[key spec]]
                                     [key (if (map? spec) (:type spec) spec)]))
                              decls)]
              (vfx-api/scene-capabilities-for types))
            :fns {}
            :category-for nil}))

;; --- layout sidecar (ac/skills-v4/layout/<id>.layout.edn, VFX-同构) ---------
;;
;; Deliberately a SIBLING file next to the opened document, derived only
;; from `path` (which the caller already resolved -- see this namespace's
;; own docstring on why open!/export! never try to resolve a game/source-
;; tree directory themselves): <dir>/layout/<basename>.layout.edn. Never
;; read by any runtime dispatch path -- see verifyEditorLayoutSidecarNotLoaded.

(defn- layout-path-for ^java.io.File [^String path]
  (let [f (io/file path)
        dir (io/file (.getParentFile f) "layout")]
    (io/file dir (str (.getName f) ".layout.edn"))))

(defn- load-layout [path]
  (let [^java.io.File f (layout-path-for path)]
    (if (.isFile f)
      (try (binding [*read-eval* false] (read-string (slurp f)))
           (catch Throwable _ {}))
      {})))

(defn- save-layout! [path layout]
  (let [^java.io.File f (layout-path-for path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str layout))))

;; --- workspace save (an ACTUAL file write, not just an in-memory mutation) -
;;
;; A sibling copy next to the opened file, same reasoning as the layout
;; sidecar above: derived from `path` alone, no game-directory guess.
;; Explicit "export to source tree" (export! below) is the only action
;; that ever overwrites the file `path` itself.

(defn- workspace-path-for ^java.io.File [^String path]
  (let [f (io/file path)]
    (io/file (.getParentFile f) "editor-workspace" (.getName f))))


(defn- file-signature [path]
  (let [^java.io.File f (io/file path)]
    (when (.isFile f)
      {:length (.length f) :last-modified (.lastModified f)
       :hash (hash (slurp f))})))

(defn- atomic-write! [^java.io.File target text]
  (.mkdirs (.getParentFile target))
  (let [tmp (io/file (.getParentFile target) (str "." (.getName target) ".tmp-" (System/nanoTime)))]
    (try
      (spit tmp text)
      (try
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array StandardCopyOption
                                [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (catch java.nio.file.AtomicMoveNotSupportedException _
          (Files/move (.toPath tmp) (.toPath target)
                      (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))
      (finally
        (when (.exists tmp) (.delete tmp))))))
(defn- preview-value [type]
  (case type
    :float 0.0
    :double 0.0
    :int 1
    :long 1
    :bool false
    :boolean false
    :vec3 {:x 0.0 :y 1.0 :z 0.0}
    :resource-id "minecraft:air"
    :string ""
    nil))

(defn- preview-params [wrapper-doc]
  (into {} (map (fn [[key spec]]
                  [key (preview-value (if (map? spec) (:type spec) spec))]))
             (or (:inputs wrapper-doc) {})))

;; --- pure state --------------------------------------------------------

(defn- entries-of [form]
  (cond
    (contains? form :phases) (:phases form)
    (contains? form :do) {:default (:do form)}
    :else (throw (ex-info "document has neither :do nor :phases" {:form form}))))

(defn- recompute
  "Refresh graph state from either a V4 persisted graph or legacy surface form."
  [{:keys [document opts] :as state}]
  (if (:v4? document)
    (let [form (:form document)
          graphs (:graphs form)
          phase (or (:phase state) (first (keys graphs)))
          result (try
                   (let [{:keys [ir diagnostics]} (if (= :ac/vfx-v4 (:schema form))
                                                     (node-api/compile-v4-vfx-document! form opts :collect)
                                                     (node-api/compile-v4-skill-document! form opts :collect))]
                     {:diagnostics (vec diagnostics)
                      :cost-summary (when (empty? diagnostics)
                                      (node-cost/analyze ir (:vocab opts)))})
                   (catch Throwable error
                     {:diagnostics [{:code :v4-graph-invalid
                                     :message (.getMessage error)
                                     :nid nil}]
                      :cost-summary nil}))]
      (assoc state :phase phase :phases (vec (keys graphs)) :graph (get graphs phase)
             :diagnostics (:diagnostics result) :cost-summary (:cost-summary result)))
    (let [entries (entries-of (:form document)) phase (or (:phase state) (ffirst entries)) stmts (get entries phase)]
      (assoc state :phase phase :phases (vec (keys entries)) :graph (graph/form->graph stmts)
             :diagnostics (check/diagnostics (:form document) opts) :cost-summary (check/cost-summary (:form document) opts)))))
(def ^:private v4-fixed-palette
  (mapv (fn [type]
          {:id (keyword "node" (name type)) :fixed-type type :params {}
           :category :control :label (name type) :source :fixed})
        [:start :branch :merge :foreach :repeat :loop-end :end
         :literal :context-ref :parameter-ref :state-ref :local-get :local-set]))
(def ^:private v4-fixed-param-specs
  {:foreach {:limit {:type :int :default 256 :min 1 :max 256}}
   :repeat {:count {:type :int :default 1 :min 1 :max 256}}
   :literal {:value {:type :any :default nil}}
   :context-ref {:key {:type :keyword :default :value}}
   :parameter-ref {:key {:type :keyword :default :value}}
   :state-ref {:key {:type :keyword :default :value}}
   :local-get {:key {:type :keyword :default :value}}
   :local-set {:key {:type :keyword :default :value}
               :operation {:type :keyword :default :assign :choices [:define :assign]}}})
(defn open-document
  "path (absolute file path), mode (:skill or :scene) -> a fresh editor
   state. `path` is always the identity used for the layout/workspace
   sidecars and for a later :editor/export -- but the CONTENT actually
   read comes from the workspace sidecar (editor-workspace/<basename>)
   when one exists, falling back to `path` itself otherwise: a prior
   :editor/save wrote there, and this is what makes that write actually
   mean something across screen close/reopen instead of silently
   reverting to stale source on the next open (document/save's own
   docstring calls this out as the intended two-path design: workspace
   write by default, explicit :editor/export publishes to source).
   Structured V4 documents are opened as whole maps. The production editor
   intentionally rejects legacy :program/:scene wrappers; migration is an
   explicit offline step, never an implicit editor fallback. Also loads the layout sidecar
   (node positions from a prior session, if any) and builds the mode's
   palette once (vocab is static per mode, no need to recompute it on
   every edit)."
  [path mode]
  (let [^java.io.File ws (workspace-path-for path)
        source (if (.isFile ws) (.getAbsolutePath ws) path)
        raw (slurp source)
        wrapper-doc (binding [*read-eval* false] (read-string raw))
        opts (mode-opts mode wrapper-doc)]
    (-> {:path path
         :mode mode
         :opts opts
         :palette (vec (concat v4-fixed-palette (palette/build {:vocab (:vocab opts) :ops ops/table :fns (:fns opts)
                                  :category-for (:category-for opts)})))
         :document (cond
                     (document/v4-document? wrapper-doc) (document/open-v4 raw)
                     :else (throw (ex-info "node editor requires a V4 graph document" {:path path :schema (:schema wrapper-doc)})))
         :selected-nid nil
         :param-drafts {}
         :palette-query ""
         :palette-collapsed #{}
         :palette-recent []
         :palette-drag nil
         :ghost nil
         :canvas-viewport? true
         :drag hit/idle
         :layout (load-layout path)
         :viewport {:x 0.0 :y 0.0}
         :zoom 1.0
         :preview-active? false
         :preview-label "Preview off"
         :source-signature (file-signature path)
         :workspace-signature (when (.isFile ws) (file-signature ws))
         :status (if (.isFile ws) "Loaded (from workspace)" "Loaded")}
        recompute)))

(defn- selected-node-info [{:keys [graph selected-nid mode]}]
  (when (and selected-nid (get (:nodes graph) selected-nid))
    (let [node (get (:nodes graph) selected-nid)
          text (graph/stmt-text (:nodes graph) selected-nid)
          vfx-note (when (and (= :skill mode) (= :vfx! (:stmt node)))
                     (when-let [unknown (check/unknown-vfx-fields node (:by-id (fx-catalog/assemble)))]
                       (when (seq unknown)
                         (str " [unknown fields: " (str/join ", " (map name unknown)) "]"))))]
      {:nid selected-nid :text (str text vfx-note)})))

(defn- node-op-id [node]
  (let [op (:op node)]
    (if (symbol? op) (keyword (namespace op) (name op)) op)))

(defn- node-input-refs [node]
  (case (:stmt node)
    :call (:args node)
    :event! (:fields node)
    :vfx! (:fields node)
    {}))

(defn- payload-keyword [value]
  (cond
    (keyword? value) value
    (string? value) (when (seq value) (keyword value))
    :else nil))

(defn- vec3-values [value]
  (let [v (cond
            (and (map? value) (vector? (:vec3 value))) (:vec3 value)
            (vector? value) value
            :else nil)]
    (when (and (= 3 (count v)) (every? number? v)
               (every? #(Double/isFinite (double %)) v))
      (mapv double v))))

(defn- axis-draft-key [draft-key axis]
  (keyword (str (name draft-key) "-" (name axis))))

(defn- legacy-selected-param-fields
  "state -> display/edit fields for expression inputs of the selected node.
   Literal, vector-literal and map-literal expressions are locally editable;
   references/calls remain read-only so wiring stays the explicit operation.
   Keyword choices and vec3 axes are opt-in schema controls; without metadata
   they retain the safe text-editor fallback."
  [{:keys [graph selected-nid palette param-drafts]}]
  (if-let [node (get-in graph [:nodes selected-nid])]
    (let [refs (node-input-refs node)
          specs (:params (or (some #(when (= (node-op-id node) (:id %)) %) palette) {}))]
      (mapv (fn [[key ref]]
              (let [data (get-in graph [:nodes ref])
                    descriptor (get specs key)
                    type (:type descriptor)
                    draft-key (keyword (str "node-editor-param-"
                                            (-> (str selected-nid)
                                                (str/replace #"[^A-Za-z0-9_-]" "-"))
                                            "-" (name key)))
                    rendered (try (graph/expr-text (:nodes graph) ref)
                                  (catch Throwable _ (pr-str (:value data))))
                    editable? (and (= :data (:kind data))
                                   (contains? #{:literal :vec-lit :map-lit} (:expr data)))
                    bool? (contains? #{:bool :boolean} type)
                    numeric? (contains? #{:int :long :float :double} type)
                    choices (vec (filter keyword? (:choices descriptor)))
                    choice? (and editable? (= :keyword type) (seq choices))
                    current-vec3 (vec3-values (:value data))
                    vec3-editor? (and editable? (= :vec3 type) current-vec3)
                    vec3-components (when vec3-editor?
                                      (mapv (fn [[axis value]]
                                              {:nid selected-nid
                                               :param-key key
                                               :data-nid ref
                                               :axis axis
                                               :axis-label (str (name axis) ":")
                                               :draft-key (axis-draft-key draft-key axis)
                                               :value (str (or (get param-drafts [selected-nid key axis]) value))})
                                            (map vector [:x :y :z] current-vec3)))
                    bool-value? (= "true" (str/lower-case (str rendered)))]
                {:nid selected-nid
                 :param-key key
                 :data-nid ref
                 :draft-key draft-key
                 :type type
                 :label (str (name key) (when type
                                       (str " [" (name type) "]")))
                 :value (or (get param-drafts [selected-nid key]) rendered)
                 :editable? editable?
                 :toggle? (and editable? bool?)
                 :choice? choice?
                 :choices choices
                 :choice-next-label "Next"
                 :text-editor? (and (or (not bool?) (not editable?))
                                    (not choice?)
                                    (not vec3-editor?))
                 :vec3-editor? (boolean vec3-editor?)
                 :vec3-components (vec (or vec3-components []))
                 :stepper? (and editable? numeric?)
                 :control-label (if bool-value? "On" "Off")
                 :decrement-label "−"
                 :increment-label "+"}))
            refs))
    []))
(defn- selected-param-fields
  "Return editable fields for either legacy surface nodes or V4 component inputs."
  [{:keys [graph selected-nid palette param-drafts]}]
  (if-let [node (get-in graph [:nodes selected-nid])]
    (if (= :component (:type node))
      (let [entry (some #(when (= (:component node) (:id %)) %) palette)
            specs (:params entry)
            links (:links graph)
            port-keys (vec (distinct
                            (concat (keys (or (:inputs node) {}))
                                    (keep (fn [l]
                                            (when (and (= :data (:kind l))
                                                       (= selected-nid (first (:to l))))
                                              (second (:to l))))
                                          links))))
            wired? (fn [key]
                     (boolean (some (fn [l]
                                      (and (= :data (:kind l))
                                           (= selected-nid (first (:to l)))
                                           (= key (second (:to l)))))
                                    links)))]
        (mapv (fn [key]
                (let [value (get-in node [:inputs key])
                      wired (wired? key)
                      descriptor (or (get specs key)
                                     {:type (cond (number? value) :double
                                                  (boolean? value) :boolean
                                                  (string? value) :string
                                                  :else :any)})
                      type (:type descriptor)
                      choices (vec (filter keyword? (:choices descriptor)))
                      draft-key (keyword (str "node-editor-param-" (name selected-nid) "-" (name key)))]
                  {:nid selected-nid :param-key key :data-nid nil :draft-key draft-key
                   :type type :label (str (name key) (when type (str " [" (name type) "]")))
                   :value (if wired "wired" (or (get param-drafts [selected-nid key]) (pr-str value)))
                   :editable? (not wired) :toggle? (and (not wired) (contains? #{:bool :boolean} type) (empty? choices))
                   :choice? (seq choices) :choices choices :choice-next-label "Next"
                   :text-editor? (and (not wired) (not (or (contains? #{:bool :boolean} type) (seq choices))))
                   :vec3-editor? false :vec3-components []
                   :stepper? (and (not wired) (contains? #{:int :long :float :double} type) (number? value))
                   :control-label (if (= true value) "On" "Off")
                   :decrement-label "−" :increment-label "+"}))
              port-keys))
      (if-let [fixed-specs (get v4-fixed-param-specs (:type node))]
        (mapv (fn [[key descriptor]]
                (let [value (get node key)
                      type (:type descriptor)
                      choices (vec (filter keyword? (:choices descriptor)))
                      draft-key (keyword (str "node-editor-param-" (name selected-nid) "-" (name key)))]
                  {:nid selected-nid :param-key key :data-nid nil :draft-key draft-key
                   :type type :label (str (name key) (when type (str " [" (name type) "]")))
                   :value (or (get param-drafts [selected-nid key]) (pr-str value))
                   :editable? true :toggle? false
                   :choice? (seq choices) :choices choices :choice-next-label "Next"
                   :text-editor? (not (seq choices)) :vec3-editor? false :vec3-components []
                   :stepper? (and (contains? #{:int :long :float :double} type) (number? value))
                   :control-label "" :decrement-label "−" :increment-label "+"}))
              fixed-specs)
        (legacy-selected-param-fields {:graph graph :selected-nid selected-nid :palette palette :param-drafts param-drafts})))
    []))
(defn- parse-editor-value [descriptor raw]
  (let [type (:type descriptor)
        text (str/trim (str raw))
        read-edn (fn [] (binding [*read-eval* false] (read-string text)))]
    (case type
      (:float :double)
      (try (let [n (Double/parseDouble text)] (when (Double/isFinite n) (double n)))
           (catch Exception _ nil))
      (:int :long)
      (try (Long/parseLong text) (catch Exception _ nil))
      (:bool :boolean)
      (case (str/lower-case text) "true" true "false" false nil)
      :vec3
      (try (let [v (read-edn)]
             (when (and (vector? v) (= 3 (count v)) (every? number? v)
                        (every? #(Double/isFinite (double %)) v))
               (mapv double v)))
           (catch Exception _ nil))
      :string text
      :keyword
      (or (try
            (let [v (read-edn)] (when (keyword? v) v))
            (catch Exception _ nil))
          (when (seq text) (keyword text)))
      (try (read-edn) (catch Exception _ nil)))))

(defn- editor-value-within-bounds?
  "Return true when an already parsed editor value satisfies the optional
   numeric bounds carried by its schema descriptor. Scalar numbers and vec3
   components use the same min/max contract; non-numeric values are valid
   only when the descriptor does not declare numeric bounds. Keeping this
   check after parsing prevents NaN/infinite or out-of-range values from
   reaching graph/document mutation."
  [descriptor value]
  (let [min-value (:min descriptor)
        max-value (:max descriptor)
        values (cond
                 (number? value) [value]
                 (and (vector? value) (every? number? value)) value
                 :else nil)
        has-bounds? (or (number? min-value) (number? max-value))]
    (if (seq values)
      (and (or (not (number? min-value))
               (every? #(>= (double %) (double min-value)) values))
           (or (not (number? max-value))
               (every? #(<= (double %) (double max-value)) values)))
      (not has-bounds?))))
;; --- render-state (map -> what the .ui.edn's :state-schema binds) ------

(declare ui-label)

(defn- diagnostic-item [d]
  {:code (ui-label (:code d) 82.0)
   :message (ui-label (:message d) 292.0)
   :nid (str (:nid d)) :line (str (or (:line d) "-"))})

(defn- ui-label
  "Keep single-line editor labels inside their fixed layout box.

   Presentation V4 currently clips but does not implement generic text
   ellipsizing.  The two editors therefore bound only display labels here;
   source text, diagnostics data and input drafts remain lossless in state.
   Use the installed font bridge when available and a deterministic fallback
   for headless tests."
  [value max-width]
  (let [s (str (or value ""))
        measure (fn [text]
                  (double (or (bridge/font-width-optional text)
                              (* 4.8 (count text)))))]
    (if (or (str/blank? s) (<= (measure s) (double max-width)))
      s
      (let [suffix "..."]
        (loop [n (count s)]
          (let [candidate (str (subs s 0 n) suffix)]
            (cond
              (<= (measure candidate) (double max-width)) candidate
              (zero? n) suffix
              :else (recur (dec n)))))))))

(defn- palette-item
  "One cn.li.ability.editor.palette/build entry -> a display row. The same
   row is both a browseable reference and a drag source for canvas insertion.
   Category is folded into the label text (\"[targeting] target/raycast (cost 2)\").
   The palette row model adds collapsible category headers while preserving
   palette/build's stable (:category :id) ordering."
  [{:keys [id category cost source]}]
  {:id id :source source
   :label (ui-label (str "[" (name category) "] " id " (" (name source) ", cost " cost ")") 432.0)})

(defn- palette-search-text [entry]
  (str/lower-case
   (str (:id entry) " " (:category entry) " " (:source entry) " "
        (:i18n entry) " " (:doc entry))))

(defn- palette-visible-entries [palette query]
  (let [q (str/lower-case (str/trim (str (or query ""))))]
    (filterv #(or (str/blank? q)
                  (str/includes? (palette-search-text %) q)) palette)))

(defn- palette-entry-row [entry recent?]
  (assoc (palette-item entry)
         :row-type :entry :entry? true :header? false :recent? recent?))

(defn- palette-header-row [category count collapsed]
  {:row-type :category
   :category category
   :header? true
   :entry? false
   :toggleable? true
   :collapsed? collapsed
             :header-label (ui-label (str (if collapsed "▶ " "▼ ") (name category) " (" count ")") 432.0)})

(defn- palette-rows
  "Build the compact palette presentation model: optional recent group,
   stable category groups, and entries hidden by a collapsed category. The
   source palette remains immutable and sorted; query/fold/recent are view
   state only, so document and graph contracts never depend on UI ordering."
  [palette query collapsed recent]
  (let [visible (palette-visible-entries palette query)
        by-category (group-by :category visible)
        categories (->> visible (map :category) distinct vec)
        by-id (into {} (map (juxt :id identity) visible))
        recent-entries (->> recent (keep by-id) distinct vec)
        recent-collapsed? (contains? collapsed :recent)
        recent-rows (when (seq recent-entries)
                      (into [(palette-header-row :recent (count recent-entries) recent-collapsed?)]
                            (when-not recent-collapsed?
                              (map #(palette-entry-row % true) recent-entries))))
        category-rows (mapcat (fn [category]
                                (let [entries (get by-category category [])
                                      folded? (contains? collapsed category)]
                                  (cons (palette-header-row category (count entries) folded?)
                                        (when-not folded?
                                          (map #(palette-entry-row % false) entries)))))
                              categories)]
    (vec (if (seq visible)
           (concat recent-rows category-rows)
           [{:row-type :empty :category :empty :header? true :entry? false
             :toggleable? false :header-label "No matching palette entries"}]))))

(defn- remember-palette! [state* id]
  (when id
    (swap! state* update :palette-recent
           (fn [ids]
             (vec (take 8 (cons id (remove #{id} (or ids [])))))))))
(defn- pan-canvas-items
  "composite-items, viewport ({:x :y}, total accumulated drag amount
   since open -- see the :panning branch in handle-action's :input/
   pointer case) -> the same items with every :x/:y shifted by viewport,
   so dragging empty canvas moves the content WITH the cursor (the
   conventional 'hand tool' feel). Kept as a pure helper for callers and
   tests; render-state uses the combined zoom transform below."
  [items {:keys [x y]}]
  (mapv (fn [item] (-> item (update :x + x) (update :y + y))) items))

(defn- transform-canvas-items
  "Apply the screen camera to graph-local composite items. Layout values
   remain unscaled and are saved exactly as authored; only the rendered
   geometry is transformed. viewport is the post-zoom translation, so a
   zoom operation can preserve the point beneath the cursor."
  [items {:keys [x y]} zoom]
  (let [zoom (double (or zoom 1.0))
        vx (double (or x 0.0))
        vy (double (or y 0.0))]
    (mapv (fn [item]
            (reduce (fn [m key]
                      (if (number? (get m key))
                        (assoc m key
                               (double (if (#{:x :y} key)
                                          (+ (if (= :x key) vx vy)
                                             (* zoom (double (get m key))))
                                          (* zoom (double (get m key))))))
                        m))
                    item [:x :y :w :h :x0 :y0 :x1 :y1]))
          items)))

(declare ghost-items)

(defn- render-state [state]
  (let [{:keys [graph document diagnostics cost-summary phase phases status mode palette viewport zoom ghost
                palette-query palette-collapsed palette-recent canvas-viewport?]} state
        selected (selected-node-info state)
        selected-params (selected-param-fields state)
        ;; Repeater text inputs need a stable state-backed draft. The
        ;; Presentation runtime rewrites focus to this per-field key so
        ;; character/backspace/Enter events carry the current value.
        draft-items (mapcat (fn [field]
                             (cons field (:vec3-components field)))
                           selected-params)
        draft-state (into {}
                          (keep (fn [{:keys [draft-key value text-editor? axis]}]
                                  (when (and draft-key (or text-editor? axis))
                                    [draft-key (str value)])))
                          draft-items)
        raw-canvas (into (render/graph->composite-items graph (:layout state))
                         (when ghost (ghost-items ghost)))
        canvas-viewport? (boolean canvas-viewport?)]
    (merge draft-state
           {:title (str "Node Editor [" (name (or mode :skill)) "]" (when (:dirty? document) " *"))
     :path (:path state)
     :phase-label (str "Phase: " (name (or phase :default)))
     :phase-tabs (mapv (fn [p] {:phase (name p) :action-label (if (= p phase) "Selected" (name p))}) phases)
     :palette (mapv palette-item palette)
     :palette-query (or palette-query "")
     :palette-rows (palette-rows palette palette-query palette-collapsed palette-recent)
     :palette-search-label "Filter palette"
     :palette-clear-label "Clear"
     :canvas (transform-canvas-items raw-canvas viewport zoom)
     :canvas-viewport? canvas-viewport?
     :canvas-compact-visible? (not canvas-viewport?)
     :canvas-viewport-visible? canvas-viewport?
     :canvas-viewport-label (if canvas-viewport? "Close viewport (Esc)" "Expand canvas")
     :canvas-viewport-title "Canvas viewport"
     :selected-label (ui-label (if selected (:text selected) "(nothing selected)") 456.0)
     :selected-params selected-params
     :diagnostics (mapv diagnostic-item diagnostics)
     :diagnostic-count (double (count diagnostics))
     :cost-label (if cost-summary
                   (str "complexity=" (:complexity cost-summary)
                        " host-cmds=" (:host-commands cost-summary))
                   "(compile errors -- see diagnostics)")
     :zoom-label (format "Zoom %.0f%%" (* 100.0 (double (or zoom 1.0))))
     :zoom-reset-label "Reset zoom"
     :status (ui-label (or status "") 456.0)
     :preview-active? (boolean (:preview-active? state))
     :preview-label (or (:preview-label state) "Preview off")
     :preview-toggle-label (if (:preview-active? state) "Stop preview" "Preview")
     :dirty? (boolean (:dirty? document))
     :reload-label "Reload from disk"
     :save-label "Save to workspace"
     :export-label "Export to source"
     :undo-label "Undo"
     :redo-label "Redo"})))
;; --- input handling ------------------------------------------------------

(defn- item->hit
  "Resolve a composite item into the pure canvas interaction classification."
  [item]
  (cond
    (= :pin (:role item)) (select-keys item [:target :nid :pin :key])
    (:nid item) {:target :node :nid (:nid item)}
    :else {:target :canvas}))

(defn- nudge-node-layout!
  "state*, nid, dx, dy -> accumulates (dx, dy) into nid's current layout
   position, seeding from the default (grid/exec-order) position on its
   very first move. dx/dy are the presentation runtime's own PER-FRAME
   INCREMENTAL :drag-x/:drag-y (see runtime.clj's scroll-offset handling
   for the same field, added there directly rather than diffed from a
   remembered press position) -- not a start-position delta, so this
   needs no memory of where the drag began, only where the node
   currently sits."
  [state* nid dx dy]
  (swap! state* update :layout
         (fn [layout]
            (let [current-graph (:graph @state*)
                  flat (graph/exec-flatten current-graph)
                  data-ids (->> (:nodes current-graph)
                                (keep (fn [[id node]] (when (= :data (:kind node)) id))))
                  base (if (:v4? (:document @state*))
                         (render/resolve-layout layout (keys (:nodes current-graph)))
                         (merge (render/exec-default-layout flat)
                                (render/expr-default-layout data-ids)
                                layout))
                  cur (get base nid {:x 0.0 :y 0.0})]
               (assoc layout nid {:x (+ (:x cur) (double dx)) :y (+ (:y cur) (double dy))})))))


(defn- install-graph!
  [state* edited-graph]
  (try
    (swap! state*
           (fn [s]
             (let [doc (:document s) form (:form doc)
                   new-form (if (:v4? doc)
                              (assoc-in form [:graphs (:phase s)] edited-graph)
                              (let [stmts (graph/graph->form edited-graph)]
                                (if (contains? form :phases) (assoc-in form [:phases (:phase s)] stmts) (assoc form :do stmts))))]
               (recompute (assoc s :document (document/edit doc new-form) :status "Graph updated.")))))
    (catch Throwable error (swap! state* assoc :status (str "Cannot apply graph edit: " (.getMessage error))))))
(declare install-graph!)

(defn- legacy-param-submit [state* payload]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        raw (or (:value payload) (:value item) (:text payload))
        node (get-in @state* [:graph :nodes nid])
        refs (node-input-refs node)
        data-nid (get refs key)
        data (get-in @state* [:graph :nodes data-nid])
        entry (some #(when (= (node-op-id node) (:id %)) %) (:palette @state*))
        descriptor (or (get-in entry [:params key])
                       {:type (cond (number? (:value data)) :double
                                    (boolean? (:value data)) :boolean
                                    (string? (:value data)) :string
                                    :else :any)})
        parsed (parse-editor-value descriptor raw)]
    (cond
      (nil? node) (swap! state* assoc :status "Select a node first.")
      (nil? key) (swap! state* assoc :status "Unknown node parameter.")
      (not (and (= :data (:kind data))
                (contains? #{:literal :vec-lit :map-lit} (:expr data))))
      (swap! state* assoc :status "This input is driven by an expression; connect a literal node instead.")
      (nil? parsed) (swap! state* assoc :status (str "Invalid " (name key) " value."))
      (and (seq (:choices descriptor))
           (not (some #(= parsed %) (:choices descriptor))))
      (swap! state* assoc :status (str "Choose one of the allowed values for " (name key) "."))
      (not (editor-value-within-bounds? descriptor parsed))
      (swap! state* assoc :status (str "Value for " (name key) " is outside the allowed range."))
      :else
      (do
        (install-graph! state* (assoc-in (:graph @state*) [:nodes data-nid]
                                          (-> data
                                              (dissoc :args)
                                              (assoc :expr :literal :value parsed))))
        (swap! state* (fn [s] (-> s
                                   (update :param-drafts dissoc [nid key])
                                   (assoc :status (str "Updated " (name key) ".")))))))))
(defn- param-submit
  "Commit a parameter edit to either a legacy literal node or a V4 inline input."
  [state* payload]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state* ))
        key (:param-key item)
        raw (or (:value payload) (:value item) (:text payload))
        node (get-in @state* [:graph :nodes nid])
        linked? (some #(and (= :data (:kind %)) (= [nid key] (:to %))) (get-in @state* [:graph :links]))]
    (if (= :component (:type node))
      (let [entry (some #(when (= (:component node) (:id %)) %) (:palette @state*))
            descriptor (or (get-in entry [:params key]) {:type :any})
            parsed (parse-editor-value descriptor raw)]
        (cond
          (nil? key) (swap! state* assoc :status "Unknown node parameter.")
          linked? (swap! state* assoc :status "This input is driven by a data wire; edit the source node instead.")
          (nil? parsed) (swap! state* assoc :status (str "Invalid " (name key) " value."))
          (and (seq (:choices descriptor)) (not (some #(= parsed %) (:choices descriptor))))
          (swap! state* assoc :status (str "Choose one of the allowed values for " (name key) "."))
          (not (editor-value-within-bounds? descriptor parsed))
          (swap! state* assoc :status (str "Value for " (name key) " is outside the allowed range."))
          :else (do
                  (install-graph! state* (assoc-in (:graph @state*) [:nodes nid :inputs key] parsed))
                  (swap! state* (fn [s] (-> s (update :param-drafts dissoc [nid key])
                                                (assoc :status (str "Updated " (name key) "."))))))))
       (if-let [descriptor (and (:v4? (:document @state*))
                                (get-in v4-fixed-param-specs [(:type node) key]))]
         (let [parsed (parse-editor-value descriptor raw)]
           (cond
             (nil? key) (swap! state* assoc :status "Unknown node parameter.")
             (nil? parsed) (swap! state* assoc :status (str "Invalid " (name key) " value."))
             (and (seq (:choices descriptor))
                  (not (some #(= parsed %) (:choices descriptor))))
             (swap! state* assoc :status (str "Choose one of the allowed values for " (name key) "."))
             (not (editor-value-within-bounds? descriptor parsed))
             (swap! state* assoc :status (str "Value for " (name key) " is outside the allowed range."))
             :else (do
                     (install-graph! state* (assoc-in (:graph @state*) [:nodes nid key] parsed))
                     (swap! state* (fn [s] (-> s (update :param-drafts dissoc [nid key])
                                                   (assoc :status (str "Updated " (name key) "."))))))))
          (legacy-param-submit state* payload)))))
(defn- param-step [state* payload direction]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        node (get-in @state* [:graph :nodes nid])
        fixed-descriptor (and (:v4? (:document @state*))
                              (get-in v4-fixed-param-specs [(:type node) key]))
        data-nid (or (:data-nid item) (get-in @state* [:graph :nodes nid :args key]))
        data (get-in @state* [:graph :nodes data-nid])
        entry (some #(when (= (node-op-id node) (:id %)) %) (:palette @state*))
        descriptor (or fixed-descriptor (get-in entry [:params key]))
        type (:type descriptor)
        current-value (if fixed-descriptor (get node key) (:value data))
        current (when (number? current-value) (double current-value))
        step (if (contains? #{:float :double} type) 0.1 1.0)
        raw-next (when (some? current) (+ current (* direction step)))
        next-value (cond-> raw-next
                     (number? (:min descriptor)) (max (double (:min descriptor)))
                     (number? (:max descriptor)) (min (double (:max descriptor))))]
    (if (and key (number? next-value))
      (param-submit state* (assoc item :value (if (contains? #{:int :long} type) (str (long next-value)) (str next-value))))
      (swap! state* assoc :status "Only numeric literal inputs support stepper controls."))))

(defn- param-toggle [state* payload]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        data-nid (or (:data-nid item) (get-in @state* [:graph :nodes nid :args key]))
        data (get-in @state* [:graph :nodes data-nid])
        current (= "true" (str/lower-case (str (:value data))))]
    (if (and key (= :data (:kind data))
             (contains? #{:literal :vec-lit :map-lit} (:expr data)))
      (param-submit state* (assoc item :value (str (not current))))
      (swap! state* assoc :status "Only boolean literal inputs support toggle controls."))))
(defn- param-cycle [state* payload direction]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        node (get-in @state* [:graph :nodes nid])
        fixed-descriptor (and (:v4? (:document @state*))
                              (get-in v4-fixed-param-specs [(:type node) key]))
        ref (get (node-input-refs node) key)
        data (get-in @state* [:graph :nodes ref])
        entry (some #(when (= (node-op-id node) (:id %)) %) (:palette @state*))
        descriptor (or fixed-descriptor (get-in entry [:params key]))
        choices (vec (filter keyword? (:choices descriptor)))
        current (parse-editor-value descriptor (if fixed-descriptor (get node key) (or (:value item) (:value data))))
        index (or (first (keep-indexed (fn [i v] (when (= current v) i)) choices)) -1)
        next-value (when (seq choices) (nth choices (mod (+ index direction) (count choices))))]
    (if next-value
      (param-submit state* (assoc item :value (pr-str next-value)))
      (swap! state* assoc :status "This keyword input has no selectable choices."))))

(defn- param-axis-change [state* payload]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        axis (:axis item)
        value (or (:value payload) (:value item) (:text payload))
        node (get-in @state* [:graph :nodes nid])
        refs (node-input-refs node)
        data-nid (get refs key)
        data (get-in @state* [:graph :nodes data-nid])]
    (when (and node key (contains? refs key)
               (contains? #{:x :y :z} axis)
               (= :data (:kind data))
               (contains? #{:literal :vec-lit :map-lit} (:expr data))
               (some? (vec3-values (:value data)))
               (some? value))
      (swap! state* assoc-in [:param-drafts [nid key axis]] (str value)))))

(defn- param-axis-submit [state* payload]
  (let [item (or (:item payload) payload)
        nid (or (:nid item) (:selected-nid @state*))
        key (:param-key item)
        axis (:axis item)
        node (get-in @state* [:graph :nodes nid])
        ref (get (node-input-refs node) key)
        data (get-in @state* [:graph :nodes ref])
        current (vec3-values (:value data))
        raw (or (:value payload) (:value item) (:text payload))
        parsed (parse-editor-value {:type :double} raw)
        index ({:x 0 :y 1 :z 2} axis)]
    (cond
      (nil? current) (swap! state* assoc :status "This input is not an editable vec3 literal.")
      (nil? index) (swap! state* assoc :status "Unknown vec3 axis.")
      (nil? parsed) (swap! state* assoc :status (str "Invalid " (name axis) " value."))
      :else
      (do
        (param-submit state* (assoc item :value (pr-str (assoc current index parsed))))
        (swap! state* update :param-drafts dissoc [nid key axis])))))
(defn- canvas-origin
  "Design-space origin of the active canvas inside node-editor.ui.edn.
   The root stack has an 8px inset on both axes. Compact mode then follows
   the fixed controls (16+16+16+32 = 80px); the viewport overlay starts at
   24px and its inner canvas at 22px. Keeping the full global origin here
   makes screen->canvas-point the inverse of the UI layout, including the
   root inset used by runtime pointer events."
  [state]
  (if (:canvas-viewport? state)
    {:x 8.0 :y 54.0}
    {:x 8.0 :y 88.0}))
(defn- screen->camera-point [state x y]
  (let [{ox :x oy :y} (canvas-origin state)]
    {:x (- (double (or x ox)) (double ox))
     :y (- (double (or y oy)) (double oy))}))

(defn- canvas-pointer?
  "True when a design-space pointer coordinate is inside the active canvas.
   Scroll events outside scroll containers arrive as :input/unknown; they
   must not zoom the graph merely because the host has no other scroll target."
  [state x y]
  (let [{ox :x oy :y} (canvas-origin state)
        width 464.0
        height (if (:canvas-viewport? state) 278.0 128.0)]
    (and (number? x) (number? y)
         (<= ox (double x) (+ ox width))
         (<= oy (double y) (+ oy height)))))

(defn- screen->canvas-point [state x y]
  (let [zoom (double (or (:zoom state) 1.0))
        viewport (:viewport state)
        vx (double (or (:x viewport) 0.0))
        vy (double (or (:y viewport) 0.0))
        {:keys [x y]} (screen->camera-point state x y)]
    {:x (/ (- x vx) zoom)
     :y (/ (- y vy) zoom)}))
(defn- screen->canvas-delta
  "Convert a per-frame pointer delta from screen pixels to graph units.
   Panning deliberately does not use this helper: viewport translation is
   stored in screen-space so an empty-canvas drag follows the cursor."
  [state dx dy]
  (let [zoom (double (or (:zoom state) 1.0))]
    {:dx (/ (double (or dx 0.0)) zoom)
     :dy (/ (double (or dy 0.0)) zoom)}))
(defn- ghost-items [{:keys [id label x y valid?]}]
  (let [x (double (or x 0.0)) y (double (or y 0.0))]
    [{:kind :quad :role :ghost :x x :y y :w 220.0 :h 16.0 :rgba (if valid? 0xAA4CAF50 0xAAE0A23B)}
     {:kind :text :role :ghost-label :x (+ x 4.0) :y (+ y 3.0)
      :text (str "[drop] " (or label id)) :rgba 0xFFFFFFFF}]))

(defn- insert-v4-palette-node [graph entry prefix]
  (let [nid (keyword "n" (str prefix "-call"))
        defaults (into {} (map (fn [[k d]] [k (or (:default d)
                                                   (case (:type d) (:float :double) 0.0
                                                         (:int :long) 0
                                                         (:bool :boolean) false
                                                         :vec3 [0.0 0.0 0.0]
                                                         nil))]) (:params entry)))
        node (if-let [fixed (:fixed-type entry)]
               (merge {:nid nid :type fixed}
                      (case fixed
                        :foreach {:limit 256 :as :item}
                        :repeat {:count 1}
                        :literal {:value nil}
                        (:context-ref :parameter-ref :state-ref :local-get) {:key :value}
                        :local-set {:key :value :operation :assign}
                        {}))
               {:nid nid :type :component :component (:id entry) :inputs defaults})]
    {:graph (assoc-in graph [:nodes nid] node) :nid nid}))
(defn- palette-drop! [state* payload]
  (let [item (:drag-item payload)
        id (:id item)
        drop-zone (:drop-zone payload)]
    (cond
      (nil? id) (swap! state* assoc :palette-drag nil :ghost nil
                       :status "Palette drag lost its source.")
      (not= :node-editor/canvas drop-zone)
      (swap! state* assoc :palette-drag nil :ghost nil
             :status "Drop the palette item on the canvas.")
      :else
      (try
        (let [entry (palette/find-by-id (:palette @state*) id)
              prefix (str "palette-" (System/nanoTime))
              {:keys [graph nid]} (if (:v4? (:document @state*)) (insert-v4-palette-node (:graph @state*) entry prefix) (graph/insert-palette-node (:graph @state*) entry prefix))
              point (screen->canvas-point @state* (:x payload) (:y payload))
              x (:x point)
              y (:y point)]
          (install-graph! state* graph)
          (remember-palette! state* id)
          (swap! state* (fn [s] (-> s
                                    (assoc :selected-nid nid
                                           :palette-drag nil
                                           :ghost nil
                                           :status (str "Added " id "."))
                                    (assoc-in [:layout nid] {:x x :y y}))))
          nil)
        (catch Throwable error
          (swap! state* assoc :palette-drag nil :ghost nil
                 :status (str "Cannot add node: " (.getMessage error))))))))
(def ^:private zoom-min 0.5)
(def ^:private zoom-max 2.0)

(defn- zoom-canvas! [state* payload]
  "Apply a wheel delta to the screen camera. The graph/layout stays in
   document coordinates; viewport is adjusted so the pointer anchor remains
   visually stationary while zoom changes."
  (let [old (double (or (:zoom @state*) 1.0))
        delta (double (or (:delta payload) 0.0))
        next-zoom (-> (* old (Math/pow 1.1 delta))
                      (max zoom-min)
                      (min zoom-max))
        ratio (if (pos? old) (/ next-zoom old) 1.0)
        origin (canvas-origin @state*)
        camera-point (screen->camera-point @state*
                                           (or (:x payload) (+ (:x origin) 232.0))
                                           (or (:y payload) (+ (:y origin) 70.0)))
        anchor-x (double (:x camera-point))
        anchor-y (double (:y camera-point))
        {:keys [x y]} (:viewport @state*)
        vx (double (or x 0.0))
        vy (double (or y 0.0))]
    (swap! state* assoc
           :zoom next-zoom
           :viewport {:x (- anchor-x (* ratio (- anchor-x vx)))
                      :y (- anchor-y (* ratio (- anchor-y vy)))}
           :status (format "Zoom %.0f%%" (* 100.0 next-zoom)))))

(defn- history-action! [state* direction]
  (let [doc (:document @state*)
        history? (if (= direction :undo) (seq (:history doc)) (seq (:future doc)))]
    (if-not history?
      (swap! state* assoc :status (if (= direction :undo) "Nothing to undo." "Nothing to redo."))
      (swap! state*
             (fn [s]
               (let [next-doc ((if (= direction :undo) document/undo document/redo) (:document s))]
                 (recompute (assoc s :document next-doc
                                      :status (if (= direction :undo) "Undid edit." "Redid edit.")))))))))
(defn- select-phase [state raw-phase]
  (let [phase (payload-keyword raw-phase)]
    (if (and phase (some #{phase} (:phases state)))
      (recompute (assoc state :phase phase :selected-nid nil))
      (assoc state :status "Select a valid phase."))))

(defn- remove-v4-node [graph nid]
  (let [node (get-in graph [:nodes nid])]
    (when-not node
      (throw (ex-info "V4 node no longer exists" {:nid nid})))
    (when (= :start (:type node))
      (throw (ex-info "The start node cannot be deleted" {:nid nid})))
    (when (= :end (:type node))
      (throw (ex-info "The end node cannot be deleted" {:nid nid})))
    (-> graph
        (update :nodes dissoc nid)
        (update :links (fn [links]
                         (vec (remove (fn [l]
                                        (or (= nid (first (:from l)))
                                            (= nid (first (:to l)))))
                                      links)))))))

(defn- connect-v4-wire [graph {:keys [from-nid from-pin from-key to-nid to-pin to-key]}]
  (let [nodes (:nodes graph)
        from (get nodes from-nid)
        to (get nodes to-nid)
        data-types #{:literal :context-ref :parameter-ref :state-ref :local-get}
        output? (= :out from-pin)
        input? (= :in to-pin)
        kind (if (or (contains? data-types (:type from)) (= :value from-key)) :data :exec)
        from-port (or from-key :exec)
        to-port (or to-key :in)
        valid-data-target? (case (:type to)
                             :component true
                             :branch (= :condition to-port)
                             :foreach (= :collection to-port)
                             :repeat (= :count to-port)
                             :local-set (= :value to-port)
                             false)
        link-id (keyword "e" (str "editor-" (System/nanoTime)))]
    (when-not (and from to output? input?)
      (throw (ex-info "V4 wire endpoints must be output to input" {:from from-nid :to to-nid})))
    (when (and (= :data kind) (not valid-data-target?))
      (throw (ex-info "V4 data output must target a declared data input" {:to to-nid :port to-port})))
    (let [links (vec (remove #(and (= kind (:kind %)) (= [to-nid to-port] (:to %))) (:links graph)))]
      (assoc graph :links (conj links {:id link-id :kind kind
                                       :from [from-nid from-port] :to [to-nid to-port]})))))
(defn- finish-pointer-drag! [state* payload]
  "Finish a node/pin/empty-canvas drag routed through a canvas :drop.
   Canvas wrappers also carry the palette drop action, so the controller
   must close the pure hit state for non-palette drags instead of treating
   them as a missing palette source."
  (let [{:keys [hit-item]} payload
        {:keys [state action]} (hit/on-up (:drag @state*)
                                          (item->hit hit-item)
                                          (double (or (:x payload) 0.0))
                                          (double (or (:y payload) 0.0)))]
    (when (= :connect-wire (:kind action))
      (try
        (install-graph! state* (if (:v4? (:document @state*)) (connect-v4-wire (:graph @state*) action) (graph/connect-wire (:graph @state*) action)))
        (catch Throwable error
          (swap! state* assoc :status (str "Cannot connect: " (.getMessage error))))))
    (swap! state* assoc :drag state)))

(defn- handle-action [state* action payload]
  (case action
    ;; A composite item's :down (:target/:item/:index only -- this
    ;; presentation runtime does NOT include :x/:y on the CUSTOM
    ;; :activate payload, only on the generic :input/pointer one below;
    ;; see runtime.clj's routed-event, the :pointer case's `hit` branch
    ;; vs its :else fallback). Selects the node and arms dragging; the
    ;; actual movement is driven by :input/pointer's :drag-x/:drag-y.
    :editor/canvas-press
    (let [item (:item payload)
          hit-val (item->hit item)]
      (when (= :node (:target hit-val)) (swap! state* assoc :selected-nid (:nid hit-val)))
      (swap! state* assoc :drag (hit/on-down hit/idle hit-val 0.0 0.0))
      nil)

    :input/pointer
    (let [{:keys [event-type drag-x drag-y drag? drag-item drop-zone x y]} payload
          drag-mode (:mode (:drag @state*))
          palette-preview? (or (some? (:palette-drag @state*))
                               (some? (:ghost @state*)))]
      (cond
        (and drag? (= :up event-type)
             (or (and (map? drag-item) (nil? (:nid drag-item)))
                 (and (nil? drag-item) palette-preview?)))
        (do
          (if (= :node-editor/canvas drop-zone)
            (palette-drop! state* payload)
            (swap! state* assoc :palette-drag nil :ghost nil
                   :status "Drop the palette item on the canvas."))
          nil)

        (and drag? (or (and (map? drag-item) (nil? (:nid drag-item)))
                       (and (nil? drag-item) palette-preview?)))
        (if-not (and (map? drag-item) (some? (:id drag-item))
                     (palette/find-by-id (:palette @state*) (:id drag-item)))
          (swap! state* assoc :palette-drag nil :ghost nil
                 :status "Palette drag lost its source.")
          (let [entry (palette/find-by-id (:palette @state*) (:id drag-item))
                point (screen->canvas-point @state* x y)]
            (swap! state* assoc
                   :palette-drag (assoc point :id (:id drag-item))
                   :ghost {:id (:id drag-item) :label (:label (palette-item entry))
                           :x (:x point) :y (:y point)
                           :valid? (= :node-editor/canvas (:drop-zone payload))})))

        :else
        (case (if (= :move event-type) :drag event-type)
          :drag (case drag-mode
                  :dragging-node (let [{:keys [dx dy]} (screen->canvas-delta @state* drag-x drag-y)]
                                    (nudge-node-layout! state* (:nid (:drag @state*)) dx dy))
                  :panning (swap! state* update :viewport
                                  (fn [{:keys [x y]}] {:x (+ x (or drag-x 0.0)) :y (+ y (or drag-y 0.0))}))
                  nil)
          :up
          (finish-pointer-drag! state* payload)
          nil)))

    :editor/palette-drag-start
    (let [item (:item payload)
          entry (when (and (map? item) (some? (:id item)))
                  (palette/find-by-id (:palette @state*) (:id item)))
          point (screen->canvas-point @state* (:x payload) (:y payload))]
      (if entry
        (swap! state* assoc
               :palette-drag (assoc point :id (:id item))
               :ghost {:id (:id item) :label (:label (palette-item entry))
                       :x (:x point) :y (:y point) :valid? false}
               :status (str "Dragging " (:id item) "."))
        (swap! state* assoc :palette-drag nil :ghost nil
               :status "Palette drag lost its source.")))

    :editor/palette-drop
    (let [item (:drag-item payload)]
      (if (or (some? (:palette-drag @state*))
              (some? (:ghost @state*))
              (and (map? item) (nil? (:nid item))))
        (palette-drop! state* (if (contains? payload :drop-zone)
                                payload
                                (assoc payload :drop-zone :node-editor/canvas)))
        (finish-pointer-drag! state* payload)))

    :editor/palette-search-change
    (swap! state* assoc :palette-query (str (or (:value payload) (:text payload) "")))

    :editor/palette-search-submit
    (swap! state* assoc :palette-query (str (or (:value payload) (:text payload) "")))

    :editor/clear-palette-search
    (swap! state* assoc :palette-query "")

    :editor/toggle-palette-category
    (let [category (or (:category payload) (get-in payload [:item :category]))]
      (when category
        (swap! state* update :palette-collapsed
               (fn [collapsed]
                 (if (contains? collapsed category)
                   (disj collapsed category)
                   (conj (or collapsed #{}) category))))))

    :editor/reset-zoom
    (swap! state* assoc :zoom 1.0 :status "Zoom reset to 100%.")

    :editor/toggle-canvas-viewport
    (let [expanded? (not (:canvas-viewport? @state*))]
      (swap! state* assoc :canvas-viewport? expanded?
             :status (if expanded?
                       "Canvas viewport expanded. Press Esc to close."
                       "Canvas viewport collapsed.")))

    :editor/undo
    (history-action! state* :undo)

    :editor/redo
    (history-action! state* :redo)

    :input/unknown
    (when (and (= :scroll (:type payload))
               (canvas-pointer? @state* (:x payload) (:y payload)))
      (zoom-canvas! state* payload))

    :input/scroll
    (when (= :node-editor/canvas (:target payload))
      (zoom-canvas! state* payload))

    :input/key
    (let [key-code (int (or (:key-code payload) -1))]
      (cond
        (= 256 key-code)
        (if (:canvas-viewport? @state*)
          (swap! state* assoc :canvas-viewport? false :palette-drag nil :ghost nil
                 :status "Canvas viewport collapsed.")
          (swap! state* assoc :palette-drag nil :ghost nil :status "Palette drag cancelled."))

        (contains? #{259 261} key-code)
        (if (and (:v4? (:document @state*)) (:selected-nid @state*))
          (try
            (install-graph! state* (remove-v4-node (:graph @state*) (:selected-nid @state*)))
            (swap! state* assoc :selected-nid nil :status "Node deleted.")
            (catch Throwable error
              (swap! state* assoc :status (str "Cannot delete node: " (.getMessage error)))))
          (swap! state* assoc :status "Select a V4 node to delete."))

        :else nil))
    :editor/toggle-preview
    (if (not= :scene (:mode @state*))
      (swap! state* assoc :status "Preview is available for VFX scene mode only.")
      (if (:preview-active? @state*)
        (do (vfx-client/stop-preview!)
            (swap! state* assoc :preview-active? false :preview-label "Preview off" :status "Preview stopped."))
        (try
          (let [doc (get-in @state* [:document :form])
                effect-id (:id doc)]
            (when-not effect-id (throw (ex-info "VFX document has no :id" {})))
            (vfx-client/start-preview! effect-id (preview-params doc))
            (swap! state* assoc :preview-active? true :preview-label (str "Preview: " effect-id) :status "Preview running."))
          (catch Throwable error
            (swap! state* assoc :status (str "Preview unavailable: " (.getMessage error)))))))

    :editor/add-palette-node
    (let [entry (palette/find-by-id (:palette @state*) (:id (:item payload)))
          prefix (str "palette-" (System/nanoTime))]
      (if-not entry
        (swap! state* assoc :status "Palette entry is no longer available.")
        (try
          (let [{:keys [graph nid]} (if (:v4? (:document @state*)) (insert-v4-palette-node (:graph @state*) entry prefix) (graph/insert-palette-node (:graph @state*) entry prefix))]
            (install-graph! state* graph)
            (remember-palette! state* (:id entry))
            (swap! state* assoc :selected-nid nid :palette-drag nil :ghost nil :status (str "Added " (:id entry) ".")))
          (catch Throwable error
            (swap! state* assoc :status (str "Cannot add node: " (.getMessage error)))))))

    :editor/param-decrement
    (param-step state* payload -1)

    :editor/param-increment
    (param-step state* payload 1)

    :editor/param-toggle
    (param-toggle state* payload)

    :editor/param-cycle
    (param-cycle state* payload 1)

    :editor/param-axis-change
    (param-axis-change state* payload)

    :editor/param-axis-submit
    (param-axis-submit state* payload)

    :editor/param-change
    (let [item (or (:item payload) payload)
          nid (or (:nid item) (:selected-nid @state*))
          key (:param-key item)
          value (or (:value payload) (:value item) (:text payload))
          node (get-in @state* [:graph :nodes nid])
          refs (node-input-refs node)
          v4-editable? (and (:v4? (:document @state*))
                            (or (= :component (:type node))
                                (contains? v4-fixed-param-specs (:type node))))]
      (when (and node key (some? value)
                 (or (contains? refs key) v4-editable?))
        (swap! state* assoc-in [:param-drafts [nid key]] (str value))))

    :editor/param-submit
    (param-submit state* payload)
    :editor/select-phase
    (swap! state* select-phase (or (:phase payload) (get-in payload [:item :phase])))

    ;; Re-reads `path` from disk and rebuilds the whole editor state,
    ;; discarding any in-memory edit that was never saved -- an honest
    ;; "reload from disk", distinct from (and NOT a substitute for)
    ;; hot-reloading the LIVE running skill catalog. That second thing
    ;; was investigated and deliberately NOT wired here: the only
    ;; available mechanism (combat-runtime/reset-final-runtime-v2-for-
    ;; test!) is a bare (reset! final-runtime-v2* nil) with no regard for
    ;; in-flight dispatches on a real server -- repurposing a function
    ;; named -for-test! into a live player-facing button is exactly the
    ;; kind of unverified-in-this-environment risk this session avoids
    ;; taking. A real hot-reload button needs a proper quiesce/drain
    ;; mechanism that does not exist today; the safe alternative (export!
    ;; to source tree, then a real server restart) already works.
    :editor/reload
    (swap! state* (fn [s] (assoc (open-document (:path s) (:mode s)) :status "Reloaded from disk")))

    ;; Writes the DSL text to a workspace sibling file (editor-workspace/,
    ;; next to the opened file -- never the file at `path` itself; only
    ;; export! touches that) AND the current node layout to its sidecar,
    ;; so both survive closing and reopening this screen. Previously this
    ;; action only mutated the in-memory atom and claimed "Saved to
    ;; workspace" without writing anything -- a real, now-fixed bug.
     :editor/save
     (try
       (swap! state*
              (fn [s]
                (let [doc (document/save (:document s) (fn [form] (pr-str form)))
                      ^java.io.File ws (workspace-path-for (:path s))]
                  (atomic-write! ws (:file-text doc))
                  (save-layout! (:path s) (:layout s))
                  (recompute (assoc s :document doc :workspace-signature (file-signature ws)
                                     :status (str "Saved to " ws))))))
       (catch Throwable error
         (swap! state* assoc :status (str "Cannot save: " (.getMessage error)))))

    ;; The other half of document/save's own documented two-path design
    ;; (see that docstring): overwrites the REAL source-tree file at
    ;; `:path` -- the one thing :editor/save above deliberately never
    ;; touches. Previously this existed only as a separate, never-wired,
    ;; never-tested `export!` function taking a player-uuid and an
    ;; explicit target-path -- dead code with zero callers anywhere in
    ;; the tree, not a UI-reachable action. Folded into a plain
    ;; handle-action case instead, same shape as :editor/save, so it is
    ;; both reachable from the screen's action row and directly testable
    ;; the same way.
     :editor/export
     (try
       (swap! state*
              (fn [s]
                (if (not= (:source-signature s) (file-signature (:path s)))
                  (assoc s :status "Source changed on disk; reload before export.")
                  (let [doc (document/save (:document s) (fn [form] (pr-str form)))]
                    (atomic-write! (io/file (:path s)) (:file-text doc))
                    (recompute (assoc s :document doc :source-signature (file-signature (:path s))
                                      :status (str "Exported to " (:path s))))))))
       (catch Throwable error
         (swap! state* assoc :status (str "Cannot export: " (.getMessage error)))))
    nil)
  (render-state @state*))

;; --- mount ---------------------------------------------------------------

(defn screen-tick!
  "Advance only the isolated scene preview; production VFX is sampled by the render seam."
  []
  (doseq [{:keys [state*]} (vals @active-mounts)]
    (when (:preview-active? @state*)
      (vfx-client/tick-preview! 0.05)))
  nil)

(defn open!
  "player-uuid, path (absolute .edn file path), mode (:skill or :scene)
   -> mounts the node editor screen. See this namespace's docstring for
   why path is caller-supplied rather than resolved internally."
  ([player-uuid path] (open! player-uuid path :skill))
  ([player-uuid path mode]
   (let [state* (atom (open-document path mode))
         on-close #(do (when (= :scene (:mode @state*)) (vfx-client/stop-preview!))
                      (swap! active-mounts dissoc (str player-uuid)))
         vm (presentation/mount-view!
             {:view-id :academy.app/node-editor
              :host-kind :screen
              :state (render-state @state*)
              :dispatch-action! (fn [action payload _current] (handle-action state* action payload))
              :on-close on-close})]
     (swap! active-mounts assoc (str player-uuid) {:mount (:mount vm) :state* state*})
     (bridge/call-adapter :presentation-open-screen!
                          (:mount vm) "Node Editor" on-close)
     vm)))






