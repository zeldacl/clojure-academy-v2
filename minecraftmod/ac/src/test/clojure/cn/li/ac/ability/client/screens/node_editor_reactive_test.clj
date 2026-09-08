(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Unit coverage for the node editor screen's PURE logic (document open,
   render-state shaping, layout nudging, workspace save/reload/export) --
   everything reachable without a live presentation-runtime mount.
   open! (the actual mount-view! side) is exercised only by using the
   screen in-game; see the namespace's own docstring for why."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]
            [cn.li.ability.editor.document :as editor-document]
            [cn.li.ability.editor.v3 :as editor-v3]))

(def ^:private thunder-bolt-path "src/main/resources/ac/skills-v3/thunder-bolt.edn")
(def ^:private railgun-path "src/main/resources/ac/skills-v3/railgun.edn")
(def ^:private arc-ring-fade-audio-path "src/main/resources/ac/vfx-v3/arc-ring-fade-audio.edn")
(def ^:private legacy-thunder-bolt-path nil)
(def ^:private multi-stage-vfx
  {:schema :ac/vfx-v3
   :id :editor/multi-stage
   :lifecycle {:mode :transient}
   :system {:spawn [{:nid :n/spawn-root :component :particle/spawn}]
            :update [{:nid :n/update-root :component :particle/update}]
            :render [{:nid :n/render-root :component :particle/render}]}})
(def ^:private v3-thunder-bolt-path "src/main/resources/ac/skills-v3/thunder-bolt.edn")
(def ^:private v3-arc-ring-fade-audio-path "src/main/resources/ac/vfx-v3/arc-ring-fade-audio.edn")
(def ^:private node-editor-ui-path "src/presentation/resources/academy/app/node_editor.ui.edn")

(defn- temp-copy-of
  "Copies `source-path` into a fresh temp directory under the same
   basename, returning the new absolute path as a string -- tests that
   exercise real disk writes (save/reload) must never touch the actual
   source tree file, only a throwaway copy."
  [source-path]
  (let [dir (java.nio.file.Files/createTempDirectory "node-editor-test" (make-array java.nio.file.attribute.FileAttribute 0))
        dest (io/file (.toFile dir) (.getName (io/file source-path)))]
    (io/copy (io/file source-path) dest)
    (.getAbsolutePath dest)))

(deftest open-document-loads-a-real-single-phase-skill-file-test
  (let [state (node-editor/open-document thunder-bolt-path :skill)]
    (is (= [:default] (:phases state)))
    (is (= :default (:phase state)))
    (is (seq (:order (:graph state))))
    (is (= [] (:diagnostics state)))
    (is (some? (:cost-summary state)))))

(deftest open-document-loads-a-real-multi-phase-skill-file-test
  (let [state (node-editor/open-document railgun-path :skill)]
    (is (> (count (:phases state)) 1))
    (is (contains? (set (:phases state)) :start))))

(deftest open-document-loads-a-real-scene-file-test
  (let [state (node-editor/open-document arc-ring-fade-audio-path :scene)]
    (is (= :scene (:mode state)))
    (is (= :ac/vfx-v3 (get-in state [:document :v3-document :schema])))
    (is (seq (:order (:graph state))))
    (is (= [] (:diagnostics state))
        (str "scene file should compile cleanly against its own per-file capabilities: "
             (:diagnostics state)))))

(deftest open-document-builds-a-non-empty-palette-test
  (let [skill-state (node-editor/open-document thunder-bolt-path :skill)
        scene-state (node-editor/open-document arc-ring-fade-audio-path :scene)]
    (is (seq (:palette skill-state)))
    (is (some #(= :fn (:source %)) (:palette skill-state))
        "skill mode's palette must include the combat.lib :defn functions")
    (is (seq (:palette scene-state)))
    (is (every? #(not= :uncategorized (:category %)) (:palette skill-state)))))

(deftest render-state-shape-is-consistent-with-the-ui-edn-state-schema-test
  (let [state (node-editor/open-document thunder-bolt-path :skill)
        rendered (#'node-editor/render-state state)]
    (is (string? (:title rendered)))
    (is (.contains ^String (:title rendered) "skill"))
    (is (string? (:phase-label rendered)))
    (is (vector? (:phase-tabs rendered)))
    (is (vector? (:palette rendered)))
    (is (seq (:palette rendered)))
    (is (every? #(string? (:label %)) (:palette rendered)))
    (is (vector? (:canvas rendered)))
    (is (vector? (:diagnostics rendered)))
    (is (number? (:diagnostic-count rendered)))
    (is (string? (:cost-label rendered)))
    (is (boolean? (:dirty? rendered)))
    (is (= "Reload from disk" (:reload-label rendered)))
    (is (= "Save to workspace" (:save-label rendered)))
    (is (= "Export to source" (:export-label rendered)))))

(deftest item->hit-classifies-nid-bearing-items-as-node-hits-test
  (is (= {:target :node :nid "n3"} (#'node-editor/item->hit {:kind :quad :role :node-body :nid "n3"})))
  (is (= {:target :node :nid "n3"} (#'node-editor/item->hit {:kind :text :role :node-label :nid "n3"})))
  (is (= {:target :canvas} (#'node-editor/item->hit {:kind :quad :x 0 :y 0}))))

(deftest nudge-node-layout-accumulates-from-the-default-position-test
  (let [state (node-editor/open-document thunder-bolt-path :skill)
        state* (atom state)
        nid (:nid (first (:order (:graph state))))]
    (#'node-editor/nudge-node-layout! state* nid 10.0 5.0)
    (#'node-editor/nudge-node-layout! state* nid 3.0 2.0)
    (let [pos (get (:layout @state*) nid)]
      (is (= 13.0 (:x pos)))
      (is (= 7.0 (:y pos))))))

(deftest layout-path-is-a-sibling-layout-directory-file-test
  (let [f (#'node-editor/layout-path-for "/a/b/ac/skills-v3/thunder-bolt.edn")]
    (is (= "thunder-bolt.edn.layout.edn" (.getName ^java.io.File f)))
    (is (.endsWith (.getParent ^java.io.File f) "layout"))))

(deftest workspace-path-is-a-sibling-editor-workspace-directory-file-test
  (let [f (#'node-editor/workspace-path-for "/a/b/ac/skills-v3/thunder-bolt.edn")]
    (is (= "thunder-bolt.edn" (.getName ^java.io.File f)))
    (is (.endsWith (.getParent ^java.io.File f) "editor-workspace"))))

(deftest save-layout-then-load-layout-round-trips-test
  (let [path (temp-copy-of thunder-bolt-path)
        layout {"n1" {:x 12.0 :y 34.0}}]
    (#'node-editor/save-layout! path layout)
    (is (= layout (#'node-editor/load-layout path)))))

(deftest load-layout-defaults-to-empty-when-no-sidecar-exists-test
  (let [path (temp-copy-of thunder-bolt-path)]
    (is (= {} (#'node-editor/load-layout path)))))

(deftest editor-save-action-actually-writes-a-workspace-file-and-a-layout-sidecar-test
  (let [path (temp-copy-of thunder-bolt-path)
        state* (atom (node-editor/open-document path :skill))
        nid (:nid (first (:order (:graph @state*))))]
    (#'node-editor/nudge-node-layout! state* nid 5.0 5.0)
    (#'node-editor/handle-action state* :editor/save nil)
    (is (.isFile ^java.io.File (#'node-editor/workspace-path-for path))
        "Save must actually write a workspace file, not just mutate in-memory state")
    (is (.isFile ^java.io.File (#'node-editor/layout-path-for path)))
    (is (= {:x 5.0 :y 5.0} (get (#'node-editor/load-layout path) nid)))))

(deftest editor-reload-action-re-reads-the-file-from-disk-test
  (let [path (temp-copy-of thunder-bolt-path)
        state* (atom (node-editor/open-document path :skill))]
    ;; Simulate an in-memory edit (a node move) that was never saved.
    (#'node-editor/nudge-node-layout! state* (:nid (first (:order (:graph @state*)))) 99.0 99.0)
    (#'node-editor/handle-action state* :editor/reload nil)
    (is (= {} (:layout @state*))
        "reload discards the unsaved in-memory layout and starts fresh from disk")
    (is (= "Reloaded from disk" (:status @state*)))))

(deftest editor-export-action-overwrites-the-real-source-file-test
  (let [path (temp-copy-of thunder-bolt-path)
        state* (atom (node-editor/open-document path :skill))]
    (#'node-editor/handle-action state* :editor/export nil)
    (is (= (:file-text (:document @state*)) (slurp path))
        "export must actually overwrite the file at `path`, not just claim to")
    (is (.contains ^String (:status @state*) "Exported to"))))

(deftest pan-canvas-items-shifts-every-item-by-the-viewport-offset-test
  (is (= [{:x 15.0 :y 24.0 :kind :quad}]
         (#'node-editor/pan-canvas-items [{:x 5.0 :y 20.0 :kind :quad}] {:x 10.0 :y 4.0}))))

(deftest canvas-drag-on-empty-canvas-pans-the-viewport-test
  (let [state* (atom (node-editor/open-document thunder-bolt-path :skill))]
    (#'node-editor/handle-action state* :editor/canvas-press {:item {}})
    (is (= :panning (:mode (:drag @state*)))
        "an item with no :nid classifies as a :canvas hit, arming panning")
    (#'node-editor/handle-action state* :input/pointer {:event-type :drag :drag-x 10.0 :drag-y 4.0})
    (#'node-editor/handle-action state* :input/pointer {:event-type :drag :drag-x 3.0 :drag-y 1.0})
    (is (= {:x 13.0 :y 5.0} (:viewport @state*))
        "viewport accumulates per-frame drag deltas the same way node layout does")
    (#'node-editor/handle-action state* :input/pointer {:event-type :up})
    (is (= :idle (:mode (:drag @state*))))))

(deftest render-state-canvas-reflects-the-accumulated-viewport-test
  (let [state* (atom (node-editor/open-document thunder-bolt-path :skill))
        before (:x (first (:canvas (#'node-editor/render-state @state*))))]
    (#'node-editor/handle-action state* :editor/canvas-press {:item {}})
    (#'node-editor/handle-action state* :input/pointer {:event-type :drag :drag-x 7.0 :drag-y 2.0})
    (let [after (:x (first (:canvas (#'node-editor/render-state @state*))))]
      (is (= (+ before 7.0) after)
          "panning must actually move what render-state hands the .ui.edn canvas, not just internal state"))))

(deftest open-document-prefers-a-saved-workspace-copy-over-the-original-test
  (let [path (temp-copy-of thunder-bolt-path)
        ^java.io.File ws (#'node-editor/workspace-path-for path)]
    (.mkdirs (.getParentFile ws))
    ;; railgun is multi-phase, thunder_bolt (the file actually at `path`)
    ;; is single-phase -- an unmistakable signal of which one got read.
    (io/copy (io/file railgun-path) ws)
    (let [state (node-editor/open-document path :skill)]
      (is (> (count (:phases state)) 1)
          "open-document must read the workspace sidecar, not `path` itself, when one exists")
      (is (.contains ^String (:status state) "workspace")))))

(deftest open-document-loads-a-structured-v3-skill-test
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)]
    (is (true? (:v3? (:document state))))
    (is (= :ac/skill-v3 (get-in state [:document :v3-document :schema])))
    (is (contains? (set (:phases state)) :default))
    (is (seq (:order (:graph state))))
    (is (= [] (:diagnostics state)))
    (is (some? (:cost-summary state)))))

(deftest open-document-rejects-legacy-string-wrapper-test
  (let [legacy (java.io.File/createTempFile "node-editor-legacy" ".edn")]
    (spit legacy "{:id :legacy :program \"(finish {:outcome :performed})\"}")
    (try
      (node-editor/open-document (.getPath legacy) :skill)
      (is false "the production editor must accept structured V3 documents only")
      (catch clojure.lang.ExceptionInfo error
        (is (.contains (.getMessage error) "structured V3 document")))
      (finally (when (.isFile legacy) (.delete legacy))))))

(deftest open-document-loads-a-structured-v3-vfx-test
  (let [state (node-editor/open-document v3-arc-ring-fade-audio-path :scene)]
    (is (true? (:v3? (:document state))))
    (is (= :ac/vfx-v3 (get-in state [:document :v3-document :schema])))
    (is (contains? (set (:phases state)) :render))
    (is (seq (:order (:graph state))))
    (is (= [] (:diagnostics state)))))

(deftest structured-v3-save-keeps-the-map-document-contract-test
  (let [path (temp-copy-of v3-thunder-bolt-path)
        state* (atom (node-editor/open-document path :skill))]
    (#'node-editor/handle-action state* :editor/save nil)
    (let [saved (slurp (#'node-editor/workspace-path-for path))
          parsed (clojure.edn/read-string saved)]
      (is (= :ac/skill-v3 (:schema parsed)))
      (is (map? (:entries parsed)))
      (is (nil? (:program parsed)))
      (is (= saved (:file-text (:document @state*)))))))

(deftest structured-v3-content-round-trip-remains-valid-test
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)
        edited (editor-document/edit (:document state) (:form (:document state)))
        saved (editor-document/save edited pr-str)
        parsed (clojure.edn/read-string (:file-text saved))]
    (is (= :ac/skill-v3 (:schema parsed)))
    (is (map? (:entries parsed)))
    (is (every? map? (mapcat (comp :do val) (:entries parsed))))))

(deftest structured-v3-vfx-content-round-trip-remains-valid-test
  (let [state (node-editor/open-document v3-arc-ring-fade-audio-path :scene)
        edited (editor-document/edit (:document state) (:form (:document state)))
        saved (editor-document/save edited pr-str)
        parsed (clojure.edn/read-string (:file-text saved))]
    (is (= :ac/vfx-v3 (:schema parsed)))
    (is (vector? (get-in parsed [:system :render])))
    (is (every? map? (get-in parsed [:system :render])))))

(deftest structured-v3-vfx-save-preserves-all-system-stages-test
  (let [form (editor-v3/document->form multi-stage-vfx)
        edited (assoc-in form [:phases :spawn]
                         [(list 'finish {:outcome :performed})])
        saved (editor-v3/form->document multi-stage-vfx edited)]
    (is (= :finish (get-in saved [:system :spawn 0 :flow])))
    (is (= :particle/update (get-in saved [:system :update 0 :component])))
    (is (= :particle/render (get-in saved [:system :render 0 :component])))))

(deftest export-refuses-to-overwrite-an-externally-changed-source-test
  (let [path (temp-copy-of v3-thunder-bolt-path)
        state* (atom (node-editor/open-document path :skill))]
    (spit path (str (slurp path) " "))
    (#'node-editor/handle-action state* :editor/export nil)
    (is (.contains ^String (:status @state*) "Source changed on disk"))
    (is (.contains (slurp path) " "))
    (when (.isFile (io/file (#'node-editor/workspace-path-for path)))
      (.delete (io/file (#'node-editor/workspace-path-for path))))
    (when (.isFile (io/file path)) (.delete (io/file path)))))
(deftest expression-pin-hit-and-layout-are-semantic-test
  (is (= {:target :pin :nid "expr-1" :pin :out :key :result}
         (#'node-editor/item->hit {:kind :quad :role :pin :target :pin
                                    :nid "expr-1" :pin :out :key :result})))
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)
        data-id (->> (:nodes (:graph state))
                     (keep (fn [[id node]] (when (= :data (:kind node)) id)))
                     first)
        state* (atom state)]
    (#'node-editor/nudge-node-layout! state* data-id 4.0 3.0)
    (is (= 284.0 (get-in @state* [:layout data-id :x])))
    (is (= 3.0 (get-in @state* [:layout data-id :y])))))
(deftest palette-entry-adds-a-round-trippable-node-test
  (let [state* (atom (node-editor/open-document v3-thunder-bolt-path :skill))
        entry (first (:palette @state*))
        before (count (get-in @state* [:graph :order]))]
    (#'node-editor/handle-action state* :editor/add-palette-node {:item {:id (:id entry)}})
    ;; The executable call enters :order; literal parameters stay expression-only nodes.
    (is (= (inc before) (count (get-in @state* [:graph :order]))))
    (is (some? (:selected-nid @state*)))
    (is (.contains ^String (:status @state*) "Added"))))


(deftest node-inspector-edits-literal-parameter-and-rebuilds-document-test
  (let [state* (atom (node-editor/open-document v3-thunder-bolt-path :skill))
        entry (some #(when (some (fn [[_ d]] (#{:double :float} (:type d))) (:params %)) %)
                    (:palette @state*))
        before (count (get-in @state* [:graph :order]))]
    (#'node-editor/handle-action state* :editor/add-palette-node {:item {:id (:id entry)}})
    (let [field (some #(when (and (:editable? %) (#{:double :float} (:type %))) %) (:selected-params (#'node-editor/render-state @state*)))
          data-nid (:data-nid field)]
      (is (= (inc before) (count (get-in @state* [:graph :order]))))
      (is (true? (:editable? field)))
      (is (keyword? (:draft-key field)))
      (is (= (:value field) (get (#'node-editor/render-state @state*) (:draft-key field))))
      (#'node-editor/handle-action state* :editor/param-change (assoc field :value "3.5"))
      (is (= "3.5" (get-in @state* [:param-drafts [(:nid field) (:param-key field)]])))
      (#'node-editor/handle-action state* :editor/param-submit (assoc field :value "3.5"))
      (is (= 3.5 (get-in @state* [:graph :nodes data-nid :value])))
      (is (true? (get-in @state* [:document :dirty?])))
      (is (.contains ^String (:status @state*) "Updated")))))
(deftest palette-drag-drop-inserts-at-canvas-and-cancel-clears-ghost-test
  (let [state* (atom (node-editor/open-document v3-thunder-bolt-path :skill))
        entry (first (:palette @state*))
        item {:id (:id entry) :label "dragged"}
        before (count (get-in @state* [:graph :order]))]
    (#'node-editor/handle-action state* :editor/palette-drag-start
     {:item item :x 40.0 :y 110.0})
    (is (= (:id entry) (get-in @state* [:palette-drag :id])))
    (is (= (:id entry) (get-in @state* [:ghost :id])))
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :drag :drag? true :drag-item item :x 52.0 :y 124.0 :drop-zone :node-editor/canvas})
    (is (= 52.0 (get-in @state* [:ghost :x])))
    (is (= 44.0 (get-in @state* [:ghost :y])))
    (is (true? (get-in @state* [:ghost :valid?])))
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :up :drag? true :drag-item item :x 52.0 :y 124.0
      :drop-zone :node-editor/canvas})
    (is (= (inc before) (count (get-in @state* [:graph :order]))))
    (is (nil? (:ghost @state*)))
    (#'node-editor/handle-action state* :editor/palette-drag-start
     {:item item :x 40.0 :y 110.0})
    (#'node-editor/handle-action state* :input/key {:key-code 256})
    (is (nil? (:palette-drag @state*)))
    (is (nil? (:ghost @state*)))))

(deftest palette-search-category-collapse-and-recent-rows-test
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)
        palette (:palette state)
        first-entry (first palette)
        category (:category first-entry)
        all-rows (#'node-editor/palette-rows palette "" #{} [])
        filtered (#'node-editor/palette-rows palette (name (:id first-entry)) #{} [])
        collapsed (#'node-editor/palette-rows palette "" #{category} [])
        recent (#'node-editor/palette-rows palette "" #{} [(:id first-entry)])
        needle (str/lower-case (str (:id first-entry)))
        matches (filter :entry? filtered)]
    (is (some :entry? all-rows))
    (is (every? (fn [row]
                  (= needle (str/lower-case (str (:id row))))) matches)
        "search rows must only contain matching entries")
    (is (some #(and (= category (:category %)) (:collapsed? %)) collapsed))
    (is (= (:id first-entry) (:id (some #(when (:recent? %) %) recent))))))
(deftest zoom-is-clamped-and-keeps-pointer-anchor-test
  (let [state* (atom {:zoom 1.0 :viewport {:x 0.0 :y 0.0}})]
    (#'node-editor/zoom-canvas! state* {:delta 1.0 :x 100.0 :y 130.0})
    (is (= 11 (Math/round (* 10.0 (:zoom @state*))))
        "one wheel notch should apply the stable 1.1 zoom step")
    (is (= -10 (Math/round (:x (:viewport @state*))))
        "the cursor x coordinate remains the zoom anchor")
    (is (= -5 (Math/round (:y (:viewport @state*)))))
    (#'node-editor/zoom-canvas! state* {:delta 100.0 :x 100.0 :y 130.0})
    (is (= 2.0 (:zoom @state*)) "zoom has a 200% upper bound")
    (#'node-editor/handle-action state* :editor/reset-zoom nil)
    (is (= 1.0 (:zoom @state*)))) )

(deftest undo-and-redo-rebuild-the-editor-graph-test
  (let [state* (atom (node-editor/open-document v3-thunder-bolt-path :skill))
        entry (first (:palette @state*))
        before (count (get-in @state* [:graph :order]))]
    (#'node-editor/handle-action state* :editor/add-palette-node {:item {:id (:id entry)}})
    (is (= (inc before) (count (get-in @state* [:graph :order]))))
    (#'node-editor/handle-action state* :editor/undo nil)
    (is (= before (count (get-in @state* [:graph :order]))))
    (#'node-editor/handle-action state* :editor/redo nil)
    (is (= (inc before) (count (get-in @state* [:graph :order]))))))

(deftest palette-drop-converts-screen-point-to-layout-coordinates-test
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)
        entry (first (:palette state))
        state* (atom (assoc state :zoom 2.0 :viewport {:x 10.0 :y 20.0}))
        item {:id (:id entry) :label "screen-space"}]
    (#'node-editor/handle-action state* :editor/palette-drag-start
     {:item item :x 110.0 :y 220.0})
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :up :drag? true :drag-item item :x 110.0 :y 220.0
      :drop-zone :node-editor/canvas})
    (let [nid (:selected-nid @state*)
          pos (get-in @state* [:layout nid])]
      (is (= 50 (Math/round (:x pos))))
      (is (= 60 (Math/round (:y pos)))))))
(deftest viewport-screen-point-converts-to-local-graph-coordinates-test
  (let [state (assoc (node-editor/open-document v3-thunder-bolt-path :skill)
                     :canvas-viewport? true
                     :zoom 2.0
                     :viewport {:x 10.0 :y 20.0})
        point (#'node-editor/screen->canvas-point state 110.0 146.0)]
    (is (= 50 (Math/round (:x point))))
    (is (= 40 (Math/round (:y point))))))

(deftest zoomed-node-drag-converts-screen-delta-to-graph-delta-test
  (let [state (node-editor/open-document v3-thunder-bolt-path :skill)
        nid (first (get-in state [:graph :order]))
        state* (atom (assoc state :zoom 2.0 :layout {nid {:x 100.0 :y 40.0}}))]
    (#'node-editor/handle-action state* :editor/canvas-press {:item {:nid nid}})
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :drag :drag-x 4.0 :drag-y 2.0})
    (is (= 102.0 (get-in @state* [:layout nid :x])))
    (is (= 41.0 (get-in @state* [:layout nid :y])))))
(deftest schema-driven-keyword-and-vec3-editor-primitives-test
  (is (= :safe (#'node-editor/parse-editor-value {:type :keyword} ":safe")))
  (is (= :safe (#'node-editor/parse-editor-value {:type :keyword} "safe")))
  (is (= [1.0 2.0 3.0] (#'node-editor/vec3-values [1 2 3])))
  (is (nil? (#'node-editor/vec3-values [1 2])))
  (is (nil? (#'node-editor/vec3-values [1 ##NaN 3]))))
(deftest captured-node-drag-does-not-enter-palette-ghost-path-test
  (let [state* (atom (node-editor/open-document thunder-bolt-path :skill))
        nid (some (fn [[k v]] (when (#{:call :event! :vfx!} (:stmt v)) k)) (get-in @state* [:graph :nodes]))]
    (#'node-editor/handle-action state* :editor/canvas-press {:item {:nid nid}})
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :move :drag? true :drag-item {:nid nid} :drag-x 4.0 :drag-y 2.0})
    (is (nil? (:ghost @state*)))
    (is (= :dragging-node (get-in @state* [:drag :mode])))
    (is (map? (get-in @state* [:layout nid])))
    (#'node-editor/handle-action state* :input/pointer
     {:event-type :up :drag? true :drag-item {:nid nid} :x 4.0 :y 2.0})
    (is (= :idle (get-in @state* [:drag :mode])))))

(deftest canvas-viewport-toggle-expands-and-escape-collapses-test
  (let [state* (atom (node-editor/open-document thunder-bolt-path :skill))]
    (is (false? (:canvas-viewport? @state*)))
    (let [rendered (#'node-editor/render-state @state*)]
      (is (true? (:canvas-compact-visible? rendered)))
      (is (false? (:canvas-viewport-visible? rendered))))
    (#'node-editor/handle-action state* :editor/toggle-canvas-viewport nil)
    (is (true? (:canvas-viewport? @state*)))
    (is (= "Canvas viewport expanded. Press Esc to close." (:status @state*)))
    (let [rendered (#'node-editor/render-state @state*)]
      (is (false? (:canvas-compact-visible? rendered)))
      (is (true? (:canvas-viewport-visible? rendered)))
      (is (= "Canvas viewport" (:canvas-viewport-title rendered))))
    (#'node-editor/handle-action state* :input/key {:key-code 256})
    (is (false? (:canvas-viewport? @state*)))
    (is (= "Canvas viewport collapsed." (:status @state*)))))


(deftest canvas-viewport-layout-fits-compact-and-320x240-design-bounds-test
  (let [ui (binding [*read-eval* false] (read-string (slurp node-editor-ui-path)))
        host (:host ui)
        root-layout (get-in ui [:root :layout])
        root-children (get-in ui [:root :children])
        base (some #(when (= :node-editor/base (:key %)) %) root-children)
        base-flow-height (reduce + (keep #(get-in % [:layout :height]) (get-in base [:children])))
        viewport (some #(when (= :node-editor/canvas-viewport (:key %)) %) root-children)
        all-maps (filter map? (tree-seq coll? seq ui))
        canvas-repeaters (filter #(and (= :repeater (:type %))
                                      (= [:state :canvas] (get-in % [:bind :items]))) all-maps)
        item-hit-wrappers (filter #(and (= :absolute (:type %))
                                      (= [:item :x] (get-in % [:bind :x]))
                                      (= [:item :w] (get-in % [:bind :width]))) all-maps)
        {:keys [x y width height]} (:layout viewport)
        scale (min (/ 320.0 (double (:design-width host)))
                   (/ 240.0 (double (:design-height host))))
        right (* scale (+ (double (:x root-layout)) (double x) (double width)))
        bottom (* scale (+ (double (:y root-layout)) (double y) (double height)))]
    (is (= :stack (:type (:root ui)))
        "root must stack the fixed-height editor base and viewport overlay")
    (is base "base column keeps compact/editor controls in normal flow")
    (is (= [:state :canvas-compact-visible?] (get-in base [:bind :visible]))
        "viewport mode hides the covered base controls from paint/hit/focus")
    (is (<= (double base-flow-height) (double (get-in base [:layout :height])))
        "compact controls must fit inside the fixed base column")
    (is viewport "viewport overlay must remain an explicit absolute child")
    (is (= :absolute (:type viewport)))
    (is (= 2 (count canvas-repeaters))
        "compact and viewport canvases both use a canvas-bound repeater")
    (is (every? #(= :none (get-in % [:layout :direction])) canvas-repeaters)
        "canvas repeaters must overlap items instead of stacking them vertically")
    (is (= 2 (count item-hit-wrappers))
        "each canvas item must have its own absolute hit wrapper")
    (is (= 464 (:width (:layout viewport))))
    (is (= 300 (:height (:layout viewport))))
    (is (<= right 320.0) (str "viewport right edge exceeds 320px design: " right))
    (is (<= bottom 240.0) (str "viewport bottom edge exceeds 240px design: " bottom))))