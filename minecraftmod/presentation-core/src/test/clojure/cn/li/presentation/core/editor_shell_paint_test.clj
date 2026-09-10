(ns cn.li.presentation.core.editor-shell-paint-test
  "Paint-level coverage for academy/shared/editor_shell.edn as consumed by
   node_editor.ui.edn (node-editor/spell-composer UI refactor, P1/P2).

   The load-bearing claim this file exists to regress: LayoutKernel never
   reads :visible during measure/arrange, so the shell CANNOT collapse a
   panel by hiding it -- it drives the panel's WIDTH to zero instead, and
   the stage panel is supposed to actually occupy the reclaimed space.
   A draw-command COUNT alone cannot prove that (fewer commands only
   proves something wasn't painted, not that the space came back), so the
   collapse test below asserts a canvas item's painted ABSOLUTE X really
   does shift left by the palette's whole width.

   Three layers keep this honest without duplicating each other:
     - cn.li.ability.editor.chrome-test  : does panel-geometry compute the
                                           right numbers?
     - node-editor-reactive-test         : does the controller feed those
                                           numbers into :shell-* state?
     - this file                         : given those numbers in state,
                                           does the compiled artifact lay
                                           out the way they imply?
   So this file supplies :shell-* values directly and never reimplements
   chrome/panel-geometry (which lives in ability-runtime and must not be
   depended on from presentation-core anyway -- see
   verifyPresentationDependencyDirection)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.nodetable :as nodetable])
  (:import [cn.li.presentation.core HostGeometry]))

(use-fixtures :each
  (fn [f] (nodetable/clear-tables-for-test!) (f)))

(defn- node-editor-golden-file
  []
  (first (filter #(.isFile ^java.io.File %)
                 [(io/file "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/node-editor.uic.edn")
                  (io/file ".." "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/node-editor.uic.edn")
                  (io/file ".." "minecraftmod" "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/node-editor.uic.edn")])))

;; node_editor_reactive.clj's own design box and open-widths, and
;; chrome.clj's header/footer/diagnostics bands. Mirrored (not imported)
;; per the namespace docstring; chrome-test owns proving these are what
;; panel-geometry actually produces.
(def ^:private design-w 560)
(def ^:private design-h 380)
(def ^:private header-h 48.0)
(def ^:private footer-h 32.0)
(def ^:private diagnostics-open-h 28.0)
(def ^:private palette-open-w 130.0)
(def ^:private inspector-open-w 180.0)

;; A canvas node at a known graph-local position: its painted absolute x is
;; (stage origin + this), which is exactly what the collapse test reads.
(def ^:private canvas-item-local-x 10.0)
(def ^:private canvas-item-w 20.0)
(def ^:private canvas-item-h 21.0)

(defn- editor-state
  [{:keys [palette-entries palette-open? inspector-open? diagnostics]
    :or {palette-entries 0 palette-open? true inspector-open? true diagnostics 0}}]
  (let [palette-w (if palette-open? palette-open-w 0.0)
        inspector-w (if inspector-open? inspector-open-w 0.0)
        diagnostics-h (if (pos? diagnostics) diagnostics-open-h 0.0)
        body-h (- design-h header-h footer-h diagnostics-h)]
    {:title "Node Editor"
     :shell-header-h header-h :shell-footer-h footer-h
     :shell-body-h body-h :shell-diagnostics-h diagnostics-h
     :shell-palette-w palette-w
     :shell-stage-w (- design-w palette-w inspector-w)
     :shell-inspector-w inspector-w
     :phase-label "Phase: default"
     :phase-tabs []
     :palette-query ""
     :palette-clear-label "Clear"
     :palette-rows (mapv (fn [i] {:header? false :entry? true
                                  :id (str "entry-" i) :label (str "Entry " i)
                                  :cost-label "1"
                                  :category-color [1.0 1.0 1.0 1.0]})
                         (range palette-entries))
     :palette-list-h (max 0.0 (- body-h 16.0))
     :palette-open? palette-open? :inspector-open? inspector-open?
     :palette-toggle-label "Hide palette" :inspector-toggle-label "Hide inspector"
     :canvas [{:kind :quad :nid "first"
               :x canvas-item-local-x :y 10.0 :w canvas-item-w :h canvas-item-h
               :local-x 0.0 :local-y 0.0 :rgba 0xFFFFFFFF}]
     :selected-label "(nothing selected)"
     :selected-signature "" :selected-signature-visible? false
     :selected-params []
     :inspector-list-h (max 0.0 (- body-h 18.0))
     :diagnostics (mapv (fn [i] {:code-tag "type-mismatch"
                                 :message (str "diagnostic " i)
                                 :nid (str "n" i) :line "-"})
                        (range diagnostics))
     :cost-label "complexity=0 host-cmds=0"
     :zoom-label "Zoom 100%"
     :zoom-reset-label "Reset zoom"
     :preview-label "Preview off"
     :preview-toggle-label "Preview"
     :reload-label "Reload from disk"
     :save-label "Save to workspace"
     :export-label "Export to source"
     :undo-label "Undo"
     :redo-label "Redo"
     :status ""}))

(defn- draw-list
  [state]
  (nodetable/clear-tables-for-test!)
  (let [artifact (edn/read-string (slurp (node-editor-golden-file)))
        rt (runtime/create-runtime)
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :view-id :academy.app/node-editor
                                  :artifact artifact
                                  :state state})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 design-w design-h 1.0))
    (-> (runtime/extract-stage! rt :screen {:width design-w :height design-h})
        :mounts first :commands)))

(defn- paint-count [state] (.count (draw-list state)))

(defn- canvas-item-x
  "Painted absolute x of the single canvas node quad. geom is a flat
   [x y w h] per command (see CmdBuf.push), so the quad is found by its
   distinctive w/h rather than by a hardcoded command index."
  [state]
  (let [dl (draw-list state)
        geom (.geom dl)]
    (first
     (keep (fn [i]
             (let [g (* 4 i)]
               (when (and (== canvas-item-w (double (aget geom (+ g 2))))
                          (== canvas-item-h (double (aget geom (+ g 3)))))
                 (double (aget geom g)))))
           (range (.count dl))))))

(defn- visible-area-of-text
  "Area of `text`'s draw command AFTER its scissor rect is applied, i.e.
   how much of it a backend would actually put on screen.

   This, not a draw-command count, is what can tell a collapsed panel from
   a merely-not-counted one: PaintKernel emits a command for every node
   regardless of clipping (it attaches a clip index -- see its emit calls),
   so clipped-away content still shows up in .count. Returns nil when no
   command carries that text at all."
  [state text]
  (let [dl (draw-list state)
        geom (.geom dl)
        clips (.clip dl)
        crects (.clipRects dl)]
    (first
     (keep (fn [i]
             (when (= text (aget (.aux dl) i))
               (let [g (* 4 i)
                     x (double (aget geom g)) y (double (aget geom (+ g 1)))
                     w (double (aget geom (+ g 2))) h (double (aget geom (+ g 3)))
                     ci (aget clips i)]
                 (if (neg? ci)
                   (* w h)
                   (let [b (* 4 ci)
                         cx (double (aget crects b)) cy (double (aget crects (+ b 1)))
                         cw (double (aget crects (+ b 2))) ch (double (aget crects (+ b 3)))
                         iw (max 0.0 (- (min (+ x w) (+ cx cw)) (max x cx)))
                         ih (max 0.0 (- (min (+ y h) (+ cy ch)) (max y cy)))]
                     (* iw ih))))))
           (range (.count dl))))))

(deftest golden-artifact-is-on-disk-test
  (is (some? (node-editor-golden-file)) "node-editor golden artifact must be on disk"))

(deftest each-palette-entry-costs-a-fixed-number-of-draw-commands-test
  (let [n0 (paint-count (editor-state {:palette-entries 0}))
        n2 (paint-count (editor-state {:palette-entries 2}))
        n5 (paint-count (editor-state {:palette-entries 5}))
        per-entry (/ (- n2 n0) 2)]
    (is (pos? n0) "the rest of the editor still paints with an empty palette")
    (is (pos? per-entry) "a palette entry must paint something")
    (testing "the per-entry cost is constant, not growing with list length"
      (is (= (* per-entry 5) (- n5 n0))))))

;; The two halves of a real collapse, and why a draw-command count proves
;; NEITHER (the plan's own wording: "命令变少只证明没画,证明不了空间被收回" --
;; and it turns out the count does not even go down, because PaintKernel
;; emits clipped commands too):
;;   1. the stage must actually MOVE INTO the reclaimed space, and
;;   2. the collapsed panel's own content must actually STOP BEING VISIBLE.
;; (2) is not automatic: binding a container's width to 0 does not shrink
;; its children (LayoutKernel.measureFree:351 constrains children by the
;; INCOMING avail, not by the node's own bound width), so before the shell
;; used :clip wrappers the collapsed palette kept painting at full size
;; straight over the stage. See editor_shell.edn's own comment.
(deftest collapsing-the-palette-hands-its-width-to-the-stage-test
  (let [open (editor-state {:palette-entries 3 :palette-open? true})
        closed (editor-state {:palette-entries 3 :palette-open? false})]
    (is (= (+ palette-open-w canvas-item-local-x) (canvas-item-x open)))
    (is (= canvas-item-local-x (canvas-item-x closed)))
    (is (= palette-open-w (- (canvas-item-x open) (canvas-item-x closed)))
        "the canvas shifts left by exactly the palette's whole width")))

(deftest collapsing-the-palette-actually-hides-its-content-test
  (let [open (editor-state {:palette-entries 3 :palette-open? true})
        closed (editor-state {:palette-entries 3 :palette-open? false})]
    (testing "a palette row is visible while the palette is open"
      (is (pos? (visible-area-of-text open "Entry 0"))))
    (testing "and is scissored to nothing once it collapses"
      (is (zero? (visible-area-of-text closed "Entry 0"))))
    (testing "the search row collapses too -- it is a sibling of the scroll,
              so the scroll's own clip never covered it"
      (is (pos? (visible-area-of-text open "Clear")))
      (is (zero? (visible-area-of-text closed "Clear"))))))

(deftest collapsing-the-inspector-does-not-move-the-stage-origin-test
  ;; The inspector is the LAST panel in the row, so collapsing it grows the
  ;; stage rightward -- the canvas origin must stay put (a regression guard
  ;; against the shell reordering its slots).
  (let [open (editor-state {:inspector-open? true})
        closed (editor-state {:inspector-open? false})]
    (is (= (canvas-item-x open) (canvas-item-x closed)))
    (testing "but its own content still stops being visible"
      (is (pos? (visible-area-of-text open "(nothing selected)")))
      (is (zero? (visible-area-of-text closed "(nothing selected)"))))))

(deftest empty-diagnostics-are-invisible-and-give-their-height-back-test
  (let [none (editor-state {:diagnostics 0})
        some-diags (editor-state {:diagnostics 3})]
    (testing "a diagnostic row is visible only while the band has height"
      (is (pos? (visible-area-of-text some-diags "diagnostic 0")))
      (is (nil? (visible-area-of-text none "diagnostic 0"))
          "an empty :diagnostics vector paints no rows at all"))
    (testing "and the band's height goes to the body instead"
      (is (= 0.0 (:shell-diagnostics-h none)))
      (is (= diagnostics-open-h (:shell-diagnostics-h some-diags)))
      (is (= (+ (:shell-body-h some-diags) diagnostics-open-h)
             (:shell-body-h none))))))
