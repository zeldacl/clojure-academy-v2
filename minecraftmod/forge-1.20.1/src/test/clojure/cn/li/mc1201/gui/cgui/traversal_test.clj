(ns cn.li.mc1201.gui.cgui.traversal-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mc1201.gui.cgui.traversal :as traversal]))

(deftest hit-test-interactive-prefers-parent-click-handler-test
  (let [root (cgui-core/create-widget :pos [0 0] :size [100 100])
        parent (cgui-core/create-widget :pos [10 10] :size [40 40])
        child (cgui-core/create-widget :pos [5 5] :size [20 20])
        clicks (atom [])]
    (cgui-core/add-widget! root parent)
    (cgui-core/add-widget! parent child)
    (events/on-left-click parent (fn [_] (swap! clicks conj :parent)))
    (let [hit (traversal/hit-test-interactive root 20 20 0 0 :left-click)]
      (is (= parent hit))
      (events/emit-widget-event! hit :left-click {:x 20 :y 20})
      (is (= [:parent] @clicks)))))

(deftest hit-path-includes-deepest-widget-test
  (let [root (cgui-core/create-widget :pos [0 0] :size [100 100])
        child (cgui-core/create-widget :pos [10 10] :size [20 20])]
    (cgui-core/add-widget! root child)
    (let [path (traversal/hit-path root 15 15 0 0)]
      (is (contains? (set path) root))
      (is (= child (last path))))))
