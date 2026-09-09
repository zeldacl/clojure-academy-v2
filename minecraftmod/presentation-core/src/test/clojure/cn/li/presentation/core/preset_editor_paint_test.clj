(ns cn.li.presentation.core.preset-editor-paint-test
  "Preset editor golden must paint bound slot icons via :slot-icon composite."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [cn.li.presentation.core.runtime :as runtime]
            [cn.li.presentation.core.nodetable :as nodetable])
  (:import [cn.li.presentation.core HostGeometry]
           [cn.li.mcmod.runtime.ui UiOp]))

(use-fixtures :each
  (fn [f] (nodetable/clear-tables-for-test!) (f)))

(defn- preset-editor-golden-file
  []
  (first (filter #(.isFile ^java.io.File %)
                 [(io/file "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/preset-editor.uic.edn")
                  (io/file ".." "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/preset-editor.uic.edn")
                  (io/file ".." "minecraftmod" "docs/06-gui/presentation/golden/assets/academy/presentation-compiled/academy.app/preset-editor.uic.edn")])))

(defn- slot-icon [src]
  {:kind :image :src src :x 0.0 :y 0.0 :w 26.5 :h 26.5
   :rgba (unchecked-int 0xFFFFFFFF)})

(deftest preset-editor-bound-slot-paints-icon-and-name
  (let [art-file (preset-editor-golden-file)]
    (is (some? art-file) "preset-editor golden artifact must be on disk")
    (let [artifact (edn/read-string (slurp art-file))
          icon (slot-icon "academy:textures/abilities/electromaster/skills/arc_gen.png")
          slot {:kind :slot :preset-index 0 :slot-index 0 :index 0
                :skill-id :arc-gen :skill-name "Arc Gen"
                :slot-icon icon :has-icon? true :icon-items [icon]
                :selected? false :tint [1.0 1.0 1.0 1.0]
                :sx 3.1 :sy 1.5 :sw 110.0 :sh 34.8
                :icon-x 2.2 :icon-y 3.8 :icon-s 26.5
                :text-x 36.2 :text-y 10.0 :text-w 62.5 :text-h 15.0
                :font-size 10.0}
          card {:kind :card :index 0 :title "Preset #1" :active? true
                :x 211.9 :y 69.25 :w 116.2 :h 141.5
                :tint [1.0 1.0 1.0 1.0] :font-size 10.0
                :title-x 18.1 :title-y -15.0 :title-w 80.0 :title-h 15.0
                :slots [slot nil nil nil]}
          state {:title "Preset Edit"
                 :cards [card]
                 :selector-visible? false
                 :selector-x 0.0 :selector-y 0.0
                 :selector-w 0.0 :selector-h 0.0
                 :selector-tip-x 0.0 :selector-tip-y 0.0
                 :selector-hint "" :selector-hint-w 0.0
                 :selector-skills []}
          rt (runtime/create-runtime)
          mount (runtime/mount!
                 rt {:host {:stage :screen} :artifact artifact :state state})
          _ (runtime/update-host! rt mount (HostGeometry. 0.0 0.0 854 480 1.0))
          dl (-> (runtime/extract-stage! rt :screen {:width 854 :height 480})
                 :mounts first :commands)
          n (.count dl)
          ops (mapv #(aget (.op dl) %) (range n))
          texts (into []
                      (keep (fn [i]
                              (when (= UiOp/TEXT (aget (.op dl) (int i)))
                                (str (aget (.aux dl) (int i))))))
                      (range n))]
      (is (pos? n) "preset editor must emit draw commands")
      (is (some #(= UiOp/IMAGE %) ops) "slot skill icon IMAGE must paint")
      (is (some #(= "Arc Gen" %) texts) "slot skill name TEXT must paint"))))
