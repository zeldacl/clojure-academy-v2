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
   - Node MOVE (drag) and SELECT are wired; adding a node from the
     palette or rewiring a pin is not in this pass -- both need UI
     machinery (drag-from-palette, pin-to-pin connect) whose exact feel
     can only really be tuned by using it in-game, which is explicitly
     what happens after this lands (see the plan's Phase 3 discussion).
   - open!/export! take an EXPLICIT absolute file path from the caller,
     not a guessed game-directory/source-tree location: resolving 'where
     does the mod's source tree live relative to the running game' is
     itself a runtime detail this environment cannot verify without
     launching the game, so it is left as a caller-supplied parameter
     rather than guessed at and shipped unverified."
  (:require [clojure.string :as str]
            [cn.li.ac.gui.presentation :as presentation]
            [cn.li.ac.vfx.fx-catalog :as fx-catalog]
            [cn.li.ability.editor.document :as document]
            [cn.li.ability.editor.graph :as graph]
            [cn.li.ability.editor.check :as check]
            [cn.li.ability.editor.render :as render]
            [cn.li.ability.editor.hit :as hit]
            [cn.li.combat.api :as combat-api]
            [cn.li.vfx.api :as vfx-api]))

(defonce ^:private active-mounts (atom {}))

(defn- mode-opts
  "mode (:skill or :scene), wrapper-doc (the just-opened, un-normalized
   ac/skills or ac/vfx/fx wrapper map, needed for scene mode's per-file
   capabilities) -> {:vocab :capabilities :fns :field}."
  [mode wrapper-doc]
  (case mode
    :skill {:vocab combat-api/skill-vocab
            :capabilities combat-api/skill-capability-type
            :fns combat-api/skill-lib-fns
            :field :program}
    :scene {:vocab vfx-api/scene-vocab
            :capabilities (vfx-api/scene-capabilities-for (get-in wrapper-doc [:inputs :spawn] {}))
            :fns {}
            :field :scene}))

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
   state. Reads the raw file text directly (not via classpath resource
   -- see this namespace's own docstring on why open!/export! take an
   explicit path). wrapper-doc's own :program/:scene text (the field
   mode-opts selects) is what document/open then parses."
  [path mode]
  (let [raw (slurp path)
        wrapper-doc (binding [*read-eval* false] (read-string raw))
        opts (mode-opts mode wrapper-doc)]
    (-> {:path path
         :mode mode
         :opts opts
         :document (document/open raw (:field opts))
         :selected-nid nil
         :drag hit/idle
         :layout {}
         :status "Loaded"}
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

(defn- render-state [state]
  (let [{:keys [graph document diagnostics cost-summary phase phases status mode]} state
        selected (selected-node-info state)]
    {:title (str "Node Editor [" (name (or mode :skill)) "]" (when (:dirty? document) " *"))
     :path (:path state)
     :phase-label (str "Phase: " (name (or phase :default)))
     :phase-tabs (mapv (fn [p] {:phase (name p) :action-label (if (= p phase) "Selected" (name p))}) phases)
     :canvas (render/graph->composite-items graph (:layout state))
     :selected-label (if selected (:text selected) "(nothing selected)")
     :diagnostics (mapv diagnostic-item diagnostics)
     :diagnostic-count (double (count diagnostics))
     :cost-label (if cost-summary
                   (str "complexity=" (:complexity cost-summary)
                        " host-cmds=" (:host-commands cost-summary))
                   "(compile errors -- see diagnostics)")
     :status (or status "")
     :dirty? (boolean (:dirty? document))
     :reload-label "Reload"
     :save-label "Save"}))

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
        :drag (when (= :dragging-node drag-mode)
                (nudge-node-layout! state* (:nid (:drag @state*)) (or drag-x 0.0) (or drag-y 0.0)))
        :up (swap! state* assoc :drag hit/idle)
        nil)
      nil)

    :editor/select-phase
    (swap! state* (fn [s] (recompute (assoc s :phase (keyword (:phase payload)) :selected-nid nil))))

    :editor/reload
    (swap! state* (fn [s] (recompute (assoc s :status "Reloaded"))))

    :editor/save
    (swap! state*
           (fn [s]
             (let [doc (document/save (:document s) (fn [form] (pr-str form)))]
               (recompute (assoc s :document doc :status "Saved to workspace")))))

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
         vm (presentation/mount-view!
             {:view-id :academy.app/node-editor
              :host-kind :screen
              :state (render-state @state*)
              :dispatch-action! (fn [action payload _current] (handle-action state* action payload))
              :on-close #(swap! active-mounts dissoc (str player-uuid))})]
     (swap! active-mounts assoc (str player-uuid) {:mount (:mount vm) :state* state*})
     vm)))

(defn export!
  "player-uuid, target-path -> writes the current document's :file-text
   verbatim to target-path (an explicit absolute path -- see namespace
   docstring). Distinct from the default in-memory save (:editor/save
   action) which only updates the document atom; this is the action
   that actually touches disk, and callers should treat it as
   deliberate (overwriting real source content)."
  [player-uuid target-path]
  (when-let [{:keys [state*]} (get @active-mounts (str player-uuid))]
    (spit target-path (:file-text (:document @state*)))
    (swap! state* assoc :status (str "Exported to " target-path))))
