(ns cn.li.mc1201.gui.cgui.input-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.events :as events]
            [cn.li.mc1201.gui.cgui.input :as input]))

(deftest mouse-click-routes-to-interactive-ancestor-test
  (let [root (cgui-core/create-widget :pos [0 0] :size [100 100])
        parent (cgui-core/create-widget :pos [10 10] :size [40 40])
        child (cgui-core/create-widget :pos [5 5] :size [20 20])
        clicks (atom [])]
    (cgui-core/add-widget! root parent)
    (cgui-core/add-widget! parent child)
    (events/on-left-click parent (fn [_] (swap! clicks conj :parent)))
    (with-redefs [cn.li.mcmod.gui.cgui-screen/gain-focus! (fn [& _] nil)]
      (input/mouse-click! root 20 20 0 0 0))
    (is (= [:parent] @clicks))))
