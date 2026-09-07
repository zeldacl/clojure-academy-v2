(ns cn.li.ac.ability.client.screens.node-editor-reactive-test
  "Unit coverage for the node editor screen's PURE logic (document open,
   render-state shaping, layout nudging, workspace save/reload/export) --
   everything reachable without a live presentation-runtime mount.
   open! (the actual mount-view! side) is exercised only by using the
   screen in-game; see the namespace's own docstring for why."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [cn.li.ac.ability.client.screens.node-editor-reactive :as node-editor]
            [cn.li.ability.editor.document :as editor-document]))

(def ^:private thunder-bolt-path "src/main/resources/ac/skills/thunder_bolt.edn")
(def ^:private railgun-path "src/main/resources/ac/skills/railgun.edn")
(def ^:private arc-ring-fade-audio-path "src/main/resources/ac/vfx/fx/arc_ring_fade_audio.edn")
(def ^:private v3-thunder-bolt-path "src/main/resources/ac/skills-v3/thunder-bolt.edn")
(def ^:private v3-arc-ring-fade-audio-path "src/main/resources/ac/vfx-v3/arc-ring-fade-audio.edn")

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
    (is (= :scene (:field (:opts state))))
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
  (let [f (#'node-editor/layout-path-for "/a/b/ac/skills/thunder_bolt.edn")]
    (is (= "thunder_bolt.edn.layout.edn" (.getName ^java.io.File f)))
    (is (.endsWith (.getParent ^java.io.File f) "layout"))))

(deftest workspace-path-is-a-sibling-editor-workspace-directory-file-test
  (let [f (#'node-editor/workspace-path-for "/a/b/ac/skills/thunder_bolt.edn")]
    (is (= "thunder_bolt.edn" (.getName ^java.io.File f)))
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
