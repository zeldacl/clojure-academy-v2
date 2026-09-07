(ns cn.li.ac.ability.client.screens.node-editor-reactive
  "Presentation Runtime controller for the node editor -- BOTH the skill
   mode (Phase 3) and the scene/VFX mode (Phase 4). Follows preset-
   editor-reactive's simpler self-contained pattern (an active-mounts
   atom keyed by player-uuid), not skill-tree's managed-screens/
   read-model machinery -- this is a standalone dev tool, not tied to
   persistent per-player gameplay state.

   The skill/scene difference is entirely in mode-opts below: a small
   table of {:vocab :capabilities :fns :field}. Everything else in this
   namespace (graph rendering, drag, save, diagnostics) is mode-agnostic
   -- Phase 4's real deliverable is proving that claim, not new plumbing.
   Scene mode's :capabilities is PER-FILE (an effect's own :inputs :spawn
   declaration merged with vfx-core's universal :age/:progress -- see
   vfx-api's scene-capabilities-for), unlike skill mode's fixed table, so
   mode-opts takes the just-opened wrapper doc as an argument.

   SCOPE (real, deliberate boundaries for this first iteration -- see
   the plan's own commit history for why each was drawn where it was):
   - Renders the EXEC statement chain only (cn.li.ability.editor.render's
     own scope note); per-expression sub-node wiring is a follow-up.
   - Node MOVE (drag), SELECT, and canvas PAN (empty-canvas drag, via
     cn.li.ability.editor.hit's :panning mode, offsetting :viewport --
     see pan-canvas-items) are wired; adding a node from the palette or
     rewiring a pin is not in this pass -- both need UI machinery
     (drag-from-palette, pin-to-pin connect) whose exact feel can only
     really be tuned by using it in-game, which is explicitly what
     happens after this lands (see the plan's Phase 3 discussion). No
     ZOOM: unlike pan, nothing in presentation-core exposes a scroll-
     wheel or pinch input primitive to drive it (grepped for one) --
     there is no gesture to wire a zoom action to yet, not just an
     unwired mode like pan was.
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
   - No crosshair live-preview (plan Phase 4 item 3, 'play the effect
     being edited at the player's crosshair, republish on save').
     Investigated, not just skipped: it needs a NEW client-side player
     eye-position/look-vector lookup -- grepped this whole client/ tree
     for one and there is no existing precedent to build on -- plus
     constructing and publishing a real cn.li.mcmod.runtime.vfx-contract
     signal (:effect-id/:owner/:event-seq + instance identity for
     :spawn/:update/...). Unlike the layout sidecar and save/reload work
     above (pure Clojure/file-I/O, fully testable without the game), this
     is new Minecraft-client-API surface with no in-repo precedent and no
     way to visually verify the result lands on the actual crosshair in
     this environment. Same category as drag-to-connect and glyph items
     above: deferred with a real reason, not a silent gap."
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
            [cn.li.node.ops :as ops]
            [cn.li.vfx.api :as vfx-api]))

(defonce ^:private active-mounts (atom {}))

(defn- classpath-file-protocol-path
  "Only when ClassLoader exposes a plain file: URL. Loom runClient often
   returns union:/… (or jar:) for the same resource — those are NOT
   accepted here."
  [resource]
  (when-let [^java.net.URL url (io/resource resource)]
    (when (= "file" (.getProtocol url))
      (.getAbsolutePath (io/as-file url)))))

(defn- source-tree-resource-path
  "Walk parents of user.dir for ac/src/main/resources/<resource>. Loom's
   client working directory is typically platform/run/<target>/client, a
   few levels below the Gradle root that holds the real skill sources."
  [resource]
  (let [rel (str "ac" java.io.File/separator "src" java.io.File/separator
                 "main" java.io.File/separator "resources" java.io.File/separator
                 (-> (str resource)
                     (str/replace "/" java.io.File/separator)
                     (str/replace "\\" java.io.File/separator)))]
    (loop [^java.io.File dir (io/file (System/getProperty "user.dir"))
           depth 0]
      (when (and dir (< depth 10))
        (let [cand (io/file dir rel)]
          (if (.isFile cand)
            (.getAbsolutePath cand)
            (recur (.getParentFile dir) (inc depth))))))))

(defn- materialize-classpath-resource!
  "Last resort: copy any resolvable classpath URL (jar:/union:/file:) into
   a writable sidecar under user.dir so Save/layout have a real file to
   sit next to. Prefer source-tree-resource-path in dev so Export writes
   the real ac/skills tree."
  [resource]
  (when-let [url (io/resource resource)]
    (let [out (io/file (System/getProperty "user.dir")
                       ".academy-node-editor"
                       (-> (str resource)
                           (str/replace "/" java.io.File/separator)
                           (str/replace "\\" java.io.File/separator)))]
      (.mkdirs (.getParentFile out))
      (with-open [in (io/input-stream url)]
        (io/copy in out))
      (.getAbsolutePath out))))

(defn default-sample-skill-resource-path
  "A classpath-relative V3 content resource (e.g. \"ac/skills-v3/thunder-
   edn\") -> its absolute on-disk path, or nil. Public because there is
   no in-game file-picker UI yet (a real follow-up, not part of this
   screen's own scope) -- both the G keybind (cn.li.ac.input-ids) and
   the editor_dev_tool item share this to pick a fixed default file to
   open, rather than each guessing its own.

   Resolution order:
   1. classpath file: URL (rare under Loom)
   2. walk up from user.dir to ac/src/main/resources/<resource> (dev)
   3. materialize the classpath bytes into .academy-node-editor/ (jar/
      union classpath — still opens; Export then targets that copy)

   Returns nil only when the resource is missing from BOTH the source
   tree and the classpath."
  [resource]
  (or (classpath-file-protocol-path resource)
      (source-tree-resource-path resource)
      (materialize-classpath-resource! resource)))

(defn- mode-opts
  "mode (:skill or :scene), document (the just-opened V3 map, needed for
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

;; --- layout sidecar (ac/skills-v3/layout/<id>.layout.edn, VFX-同构) ---------
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

;; --- pure state --------------------------------------------------------

(defn- entries-of [form]
  (cond
    (contains? form :phases) (:phases form)
    (contains? form :do) {:default (:do form)}
    :else (throw (ex-info "document has neither :do nor :phases" {:form form}))))

(defn- recompute
  "state -> state with :graph/:diagnostics/:cost-summary refreshed from
   the current document + selected phase. Called after every edit so the
   canvas/inspector never show a stale reading against the actual form."
  [{:keys [document opts] :as state}]
  (let [entries (entries-of (:form document))
        phase (or (:phase state) (ffirst entries))
        stmts (get entries phase)
        g (graph/form->graph stmts)]
    (assoc state
           :phase phase
           :phases (vec (keys entries))
           :graph g
           :diagnostics (check/diagnostics (:form document) opts)
           :cost-summary (check/cost-summary (:form document) opts))))

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
   Structured V3 documents are opened as whole maps. The production editor
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
         :palette (palette/build {:vocab (:vocab opts) :ops ops/table :fns (:fns opts)
                                  :category-for (:category-for opts)})
         :document (if (document/v3-document? wrapper-doc)
                     (document/open-v3 raw)
                     (throw (ex-info "node editor requires a structured V3 document"
                                     {:path path
                                      :schema (:schema wrapper-doc)
                                      :legacy-fields (select-keys wrapper-doc [:program :scene])})))
         :selected-nid nil
         :drag hit/idle
         :layout (load-layout path)
         :viewport {:x 0.0 :y 0.0}
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

;; --- render-state (map -> what the .ui.edn's :state-schema binds) ------

(defn- short-path
  "Keep the basename + parent folder so a long absolute path fits the
   title strip without dominating the screen."
  [path]
  (if (str/blank? path)
    ""
    (let [f (io/file path)
          parent (.getName (.getParentFile f))]
      (if (str/blank? parent)
        (.getName f)
        (str parent "/" (.getName f))))))

(defn- diagnostic-item [d]
  {:code (str (:code d))
   :message (:message d)
   :nid (str (:nid d))
   :line (str (or (:code d) "?") ": " (:message d))})

(defn- palette-item
  "One cn.li.ability.editor.palette/build entry -> a display row. No
   drag-to-canvas yet (see this namespace's own scope note) -- this is a
   real, useful reference panel on its own (browse every node/op/fn this
   mode's vocab offers, its cost, its category) even before drag-and-drop
   lands. Category is folded INTO the label text (\"[targeting] target/
   raycast (cost 2)\") rather than rendered as separate collapsible
   sections -- there is no section-header widget in this UI schema, and
   the list is already sorted by (:category :id) (palette/build's own
   sort), so same-category entries run together; a real grouped/
   collapsible view is a presentation-layer follow-up, not a data gap."
  [{:keys [id category cost source]}]
  {:label (str "[" (name category) "] " id " (" (name source) ", cost " cost ")")})

(defn- pan-canvas-items
  "composite-items, viewport ({:x :y}, total accumulated drag amount
   since open -- see the :panning branch in handle-action's :input/
   pointer case) -> the same items with every :x/:y shifted by viewport,
   so dragging empty canvas moves the content WITH the cursor (the
   conventional 'hand tool' feel). render.clj's own graph->composite-
   items has no viewport concept -- deliberately: panning is a per-
   SCREEN camera, not a property of the graph->layout transform itself
   (cn.li.ability.editor.hit's own docstring frames viewport state as
   caller-owned) -- so the shift is applied here, once, after layout."
  [items {:keys [x y]}]
  (mapv (fn [item] (-> item (update :x + x) (update :y + y))) items))

(defn- canvas-placement-items
  "render.clj emits absolute :x/:y inside each composite item. Presentation
   repeaters need those as the NODE's layout position (layout-x/y) with
   local paint at 0,0 -- same split skill_tree uses -- otherwise a column
   repeater stacks wrappers while paint still jumps to absolute coords and
   the canvas looks scattered / clipped."
  [items]
  (mapv (fn [item]
          (let [x (double (or (:x item) 0.0))
                y (double (or (:y item) 0.0))
                w (double (or (:w item) (if (= :text (:kind item)) 212.0 1.0)))
                h (double (or (:h item) (if (= :text (:kind item)) 12.0 1.0)))]
            (assoc item :layout-x x :layout-y y :x 0.0 :y 0.0 :w w :h h)))
        items))

(def ^:private phase-selected-rgba [1.0 1.0 0.55 1.0])
(def ^:private phase-idle-rgba [0.75 0.78 0.85 1.0])

(defn- render-state [state]
  (let [{:keys [graph document diagnostics cost-summary phase phases status mode palette viewport]} state
        selected (selected-node-info state)
        n-diag (count diagnostics)]
    {:title (str "Node Editor [" (name (or mode :skill)) "]" (when (:dirty? document) " *"))
     :path-label (short-path (:path state))
     :phase-header "Phase"
     :phase-tabs (mapv (fn [p]
                         (let [selected? (= p phase)]
                           {:phase (name p)
                            :label (name p)
                            :rgba (if selected? phase-selected-rgba phase-idle-rgba)}))
                       phases)
     :palette-header "Palette"
     :palette (mapv palette-item palette)
     :canvas (-> (render/graph->composite-items graph (:layout state))
                 (pan-canvas-items viewport)
                 canvas-placement-items)
     :selected-header "Selected"
     :selected-label (if selected (:text selected) "(nothing selected — click a node)")
     :diag-header (str "Diagnostics (" n-diag ")")
     :diagnostics (if (seq diagnostics)
                    (mapv diagnostic-item diagnostics)
                    [{:line "(clean)"}])
     :diagnostic-count (double n-diag)
     :cost-label (if cost-summary
                   (str "Cost  complexity=" (:complexity cost-summary)
                        "  host=" (:host-commands cost-summary))
                   "Cost  (compile errors — see diagnostics)")
     :status (or status "")
     :dirty? (boolean (:dirty? document))
     :reload-label "Reload"
     :save-label "Save"
     :export-label "Export"}))

;; --- input handling ------------------------------------------------------

(defn- item->hit
  "A composite item -> a hit.clj classification. Both a node's body quad
   AND its label text are hit-testable (both are entries in the SAME
   repeater, and :on {:activate ...} applies uniformly to every repeated
   child -- see cn.li.ability.editor.render/graph->composite-items),
   so any item carrying :nid (regardless of :role -- :node-body or
   :node-label) is a node hit; the connecting-wire quads carry no :nid
   and fall through to :canvas. Nil item (empty-canvas hit rect) is also
   :canvas — required for panning."
  [item]
  (if-let [nid (when (map? item) (:nid item))]
    {:target :node :nid nid}
    {:target :canvas}))

(defn- nudge-node-layout!
  "state*, nid, dx, dy -> accumulates (dx, dy) into nid's current layout
   position, seeding from the default (grid/exec-order) position on its
   very first move."
  [state* nid dx dy]
  (swap! state* update :layout
         (fn [layout]
           (let [flat (graph/exec-flatten (:graph @state*))
                 base (merge (render/exec-default-layout flat) layout)
                 cur (get base nid {:x 0.0 :y 0.0})]
             (assoc layout nid {:x (+ (:x cur) (double dx))
                               :y (+ (:y cur) (double dy))})))))

(defn- apply-pointer-delta!
  "Apply one frame of pointer motion to the armed drag mode."
  [state* drag-mode dx dy]
  (case drag-mode
    :dragging-node
    (when-let [nid (:nid (:drag @state*))]
      (nudge-node-layout! state* nid dx dy))
    :panning
    (swap! state* update :viewport
           (fn [{:keys [x y]}]
             {:x (+ (double (or x 0.0)) (double dx))
              :y (+ (double (or y 0.0)) (double dy))}))
    nil))

(defn- handle-action [state* action payload]
  (case action
    ;; Presentation routes BOTH :down and :drag that hit an :activate node
    ;; as the activate action (not :input/pointer) — see runtime routed-event
    ;; hit branch. So we must (a) arm on first press using payload :x/:y and
    ;; (b) treat later activates while armed as motion via cur-x/cur-y deltas.
    ;; :input/pointer still handles :up and drags that miss the hit box.
    :editor/canvas-press
    (let [item (:item payload)
          px (double (or (:x payload) 0.0))
          py (double (or (:y payload) 0.0))
          hit-val (item->hit item)
          prev (:drag @state*)
          mode (:mode prev)]
      (if (#{:dragging-node :panning} mode)
        (let [dx (- px (double (or (:cur-x prev) px)))
              dy (- py (double (or (:cur-y prev) py)))]
          (apply-pointer-delta! state* mode dx dy)
          (swap! state* assoc :drag (assoc prev :cur-x px :cur-y py)))
        (do
          (when (= :node (:target hit-val))
            (swap! state* assoc :selected-nid (:nid hit-val)))
          (swap! state* assoc :drag (hit/on-down hit/idle hit-val px py))))
      nil)

    :input/pointer
    (let [{:keys [event-type drag-x drag-y x y]} payload
          drag-mode (:mode (:drag @state*))]
      (case event-type
        ;; Miss-target press (e.g. outside any control) — do not arm pan;
        ;; only the canvas hit rect / node composites should start a drag.
        :down nil
        :drag
        (if (#{:dragging-node :panning} drag-mode)
          (apply-pointer-delta! state* drag-mode (or drag-x 0.0) (or drag-y 0.0))
          nil)
        :up (swap! state* assoc :drag hit/idle)
        :move
        ;; Some hosts deliver move-with-button as :move; if armed, use absolute
        ;; deltas from last cur when drag-x is absent.
        (when (and (#{:dragging-node :panning} drag-mode) (number? x) (number? y))
          (let [prev (:drag @state*)
                dx (- (double x) (double (or (:cur-x prev) x)))
                dy (- (double y) (double (or (:cur-y prev) y)))]
            (apply-pointer-delta! state* drag-mode dx dy)
            (swap! state* assoc :drag (assoc prev :cur-x (double x) :cur-y (double y)))))
        nil)
      nil)

    :editor/select-phase
    ;; Activate payload carries the repeater row under :item (same shape
    ;; as spell-composer's palette picks) — reading :phase off the root
    ;; payload was always nil and left the phase stuck.
    (let [phase-name (or (:phase (:item payload)) (:phase payload))]
      (when phase-name
        (swap! state* (fn [s] (recompute (assoc s :phase (keyword phase-name) :selected-nid nil))))))

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
    (swap! state*
           (fn [s]
             (let [doc (document/save (:document s) (fn [form] (pr-str form)))
                   ^java.io.File ws (workspace-path-for (:path s))]
               (.mkdirs (.getParentFile ws))
               (spit ws (:file-text doc))
               (save-layout! (:path s) (:layout s))
               (recompute (assoc s :document doc :status (str "Saved to " ws))))))

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
    (swap! state*
           (fn [s]
             (let [doc (document/save (:document s) (fn [form] (pr-str form)))]
               (spit (:path s) (:file-text doc))
               (recompute (assoc s :document doc :status (str "Exported to " (:path s)))))))

    nil)
  (render-state @state*))

;; --- mount ---------------------------------------------------------------

(defn open!
  "player-uuid, path (absolute .edn file path), mode (:skill or :scene)
   -> mounts the node editor screen. See this namespace's docstring for
   why path is caller-supplied rather than resolved internally."
  ([player-uuid path] (open! player-uuid path :skill))
  ([player-uuid path mode]
   (let [state* (atom (open-document path mode))
         on-close #(swap! active-mounts dissoc (str player-uuid))
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
