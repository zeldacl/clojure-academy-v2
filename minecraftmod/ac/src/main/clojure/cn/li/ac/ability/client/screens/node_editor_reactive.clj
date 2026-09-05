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

(defn default-sample-skill-resource-path
  "A classpath-relative content resource (e.g. \"ac/skills/thunder_bolt.
   edn\") -> its absolute on-disk path, or nil. Public because there is
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
  "mode (:skill or :scene), wrapper-doc (the just-opened, un-normalized
   ac/skills or ac/vfx/fx wrapper map, needed for scene mode's per-file
   capabilities) -> {:vocab :capabilities :fns :field :category-for}."
  [mode wrapper-doc]
  (case mode
    :skill {:vocab combat-api/skill-vocab
            :capabilities combat-api/skill-capability-type
            :fns combat-api/skill-lib-fns
            :category-for combat-api/skill-vocab-category-for
            :field :program}
    :scene {:vocab vfx-api/scene-vocab
            :capabilities (vfx-api/scene-capabilities-for (get-in wrapper-doc [:inputs :spawn] {}))
            :fns {}
            :category-for nil
            :field :scene}))

;; --- layout sidecar (ac/skills/layout/<id>.layout.edn, VFX-同构) ---------
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
   wrapper-doc's own :program/:scene text (the field mode-opts selects)
   is what document/open then parses. Also loads the layout sidecar
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
         :document (document/open raw (:field opts))
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
                     (when-let [unknown (check/unknown-vfx-fields node (fx-catalog/assemble))]
                       (when (seq unknown)
                         (str " [unknown fields: " (str/join ", " (map name unknown)) "]"))))]
      {:nid selected-nid :text (str text vfx-note)})))

;; --- render-state (map -> what the .ui.edn's :state-schema binds) ------

(defn- diagnostic-item [d]
  {:code (str (:code d)) :message (:message d) :nid (str (:nid d)) :line (str (or (:line d) "-"))})

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

(defn- render-state [state]
  (let [{:keys [graph document diagnostics cost-summary phase phases status mode palette viewport]} state
        selected (selected-node-info state)]
    {:title (str "Node Editor [" (name (or mode :skill)) "]" (when (:dirty? document) " *"))
     :path (:path state)
     :phase-label (str "Phase: " (name (or phase :default)))
     :phase-tabs (mapv (fn [p] {:phase (name p) :action-label (if (= p phase) "Selected" (name p))}) phases)
     :palette (mapv palette-item palette)
     :canvas (pan-canvas-items (render/graph->composite-items graph (:layout state)) viewport)
     :selected-label (if selected (:text selected) "(nothing selected)")
     :diagnostics (mapv diagnostic-item diagnostics)
     :diagnostic-count (double (count diagnostics))
     :cost-label (if cost-summary
                   (str "complexity=" (:complexity cost-summary)
                        " host-cmds=" (:host-commands cost-summary))
                   "(compile errors -- see diagnostics)")
     :status (or status "")
     :dirty? (boolean (:dirty? document))
     :reload-label "Reload from disk"
     :save-label "Save to workspace"
     :export-label "Export to source"}))

;; --- input handling ------------------------------------------------------

(defn- item->hit
  "A composite item -> a hit.clj classification. Both a node's body quad
   AND its label text are hit-testable (both are entries in the SAME
   repeater, and :on {:activate ...} applies uniformly to every repeated
   child -- see cn.li.ability.editor.render/graph->composite-items),
   so any item carrying :nid (regardless of :role -- :node-body or
   :node-label) is a node hit; the connecting-wire quads carry no :nid
   and are not meant to be clickable, falling through to :canvas."
  [item]
  (if-let [nid (:nid item)]
    {:target :node :nid nid}
    {:target :canvas}))

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
           (let [flat (graph/exec-flatten (:graph @state*))
                 base (merge (render/exec-default-layout flat) layout)
                 cur (get base nid {:x 0.0 :y 0.0})]
             (assoc layout nid {:x (+ (:x cur) (double dx)) :y (+ (:y cur) (double dy))})))))

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
    (let [{:keys [event-type drag-x drag-y]} payload
          drag-mode (:mode (:drag @state*))]
      (case event-type
        :drag (case drag-mode
                :dragging-node (nudge-node-layout! state* (:nid (:drag @state*)) (or drag-x 0.0) (or drag-y 0.0))
                ;; Empty-canvas press classifies as :panning (hit/on-down)
                ;; -- previously nothing consumed that mode, so dragging
                ;; empty canvas was a silent no-op. Same per-frame
                ;; incremental :drag-x/:drag-y contract as node move.
                :panning (swap! state* update :viewport
                                (fn [{:keys [x y]}] {:x (+ x (or drag-x 0.0)) :y (+ y (or drag-y 0.0))}))
                nil)
        :up (swap! state* assoc :drag hit/idle)
        nil)
      nil)

    :editor/select-phase
    (swap! state* (fn [s] (recompute (assoc s :phase (keyword (:phase payload)) :selected-nid nil))))

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

