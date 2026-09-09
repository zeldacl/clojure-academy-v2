(ns cn.li.presentation.core.developer-console-paint-test
  "The developer console renders through presentation-core like every other
   list: cn.li.ac.block.developer.console supplies labels, developer.ui.edn
   declares the geometry, and the runtime lays it out.

   It did not always. Until the console was rebuilt on this runtime it handed
   the screen per-row {:x :y :w :h}, so none of this was exercised and a layout
   mistake could only be found in game. These tests paint the golden artifact
   from a plain vector of labels; if anyone reintroduces caller-supplied
   coordinates, the one-command-per-row assertions are what break."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.nodetable :as nodetable])
  (:import [cn.li.presentation.core HostGeometry]))

(use-fixtures :each
  (fn [f] (nodetable/clear-tables-for-test!) (f)))

(defn- developer-golden-file
  []
  (first (filter #(.isFile ^java.io.File %)
                 [(io/file "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/developer.uic.edn")
                  (io/file ".." "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/developer.uic.edn")
                  (io/file ".." "minecraftmod" "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/developer.uic.edn")])))

(defn- console-state
  "Only the console half matters here; the rest of the developer screen gets
   the empty/hidden values its state-schema expects, so any change in command
   count between two of these states is the console's doing."
  [line-labels prompt]
  {:title "Developer" :mode "console"
   :ability-name "" :level-label "" :exp-label ""
   :ability-icon-items []
   :level-prog 0.0 :can-upgrade? false :level-label-visible? false
   :energy-ratio 0.0 :sync-rate 0.7
   :wireless-visible false :wireless-node-name ""
   :wireless-page-visible? false
   :skill-tree-visible? false :console-visible? true
   :composite-list []
   :console-lines (mapv (fn [l] {:label l}) line-labels)
   :console-prompt prompt
   :detail-visible? false :selected-detail {}
   :status ""})

(defn- paint-count
  "Draw commands emitted for one developer-screen state. A fresh runtime and a
   cleared node table per call so nothing leaks between measurements."
  [state]
  (nodetable/clear-tables-for-test!)
  (let [artifact (edn/read-string (slurp (developer-golden-file)))
        rt (runtime/create-runtime)
        mount (runtime/mount! rt {:host {:stage :screen}
                                  :view-id :academy.app/developer
                                  :artifact artifact
                                  :state state})]
    (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 400 187 1.0))
    (.count (-> (runtime/extract-stage! rt :screen {:width 400 :height 187})
                :mounts first :commands))))

(deftest console-paints-from-labels-alone
  (is (some? (developer-golden-file)) "developer golden artifact must be on disk")
  (is (pos? (paint-count (console-state ["Academy OS" "booting..." "ready"] "OS > help_")))
      "the console must paint without any caller-supplied coordinates"))

(deftest runtime-emits-one-row-per-label
  (let [n0 (paint-count (console-state [] "OS > _"))
        n3 (paint-count (console-state ["a" "b" "c"] "OS > _"))
        n5 (paint-count (console-state ["a" "b" "c" "d" "e"] "OS > _"))]
    (testing "each extra scrollback line costs exactly one more draw command"
      (is (= 2 (- n5 n3)))
      (is (= 3 (- n3 n0))))
    (testing "an empty scrollback still paints the rest of the screen"
      (is (pos? n0)
          "no output yet must not take the pinned prompt row down with it"))))

(deftest max-window-of-lines-paints
  ;; The controller clamps to console/max-lines (10) and the :scroll is sized
  ;; for exactly that, so a full window is the realistic worst case.
  (let [n0 (paint-count (console-state [] "OS > _"))
        full (paint-count (console-state (mapv #(str "line " %) (range 10)) "OS > _"))]
    (is (= 10 (- full n0)))))

(deftest hiding-the-console-drops-its-rows
  (let [visible (paint-count (console-state ["x" "y"] "OS > _"))
        hidden (paint-count (assoc (console-state ["x" "y"] "OS > _")
                                   :console-visible? false))]
    (is (< hidden visible)
        ":console-visible? false must drop the body rows and the prompt with them")))
