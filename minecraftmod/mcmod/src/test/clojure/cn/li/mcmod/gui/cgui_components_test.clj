(ns cn.li.mcmod.gui.cgui-components-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]
            [cn.li.mcmod.gui.cgui-events :as cgui-events]
            [cn.li.mcmod.gui.cgui-screen :as cgui-screen]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.platform.resource :as res]))

(deftest cgui-focus-and-events-test
  (let [cgui (cgui-screen/create-cgui)
        root (cgui-screen/get-root cgui)
        child (cgui-core/create-widget :name "inner")
        gained (atom [])]
    (cgui-screen/cgui-add-widget! cgui child)
    (cgui-events/listen-widget-event! child :gain-focus (fn [e] (swap! gained conj e)))
    (cgui-screen/gain-focus! root child)
    (is (identical? child (cgui-screen/get-focus root)))
    (is (= 1 (count @gained)))
    (cgui-screen/remove-focus! root)
    (is (nil? (cgui-screen/get-focus root)))))

(deftest cgui-find-widget-by-path-test
  (let [root (cgui-core/create-container :name "root")
        mid (cgui-core/create-container :name "mid")
        leaf (cgui-core/create-widget :name "leaf")]
    (cgui-core/add-widget! mid leaf)
    (cgui-core/add-widget! root mid)
    (is (identical? leaf (cgui-core/find-widget root "mid/leaf")))))

(deftest add-texture-component-with-resource-fn-test
  (binding [res/*resource-location-fn* (fn [ns path] {:ns ns :path path})]
    (let [w (cgui-core/create-widget)
          _ (comp/add-component! w (comp/texture "demo:textures/a.png"))
          c (comp/get-drawtexture-component w)]
      (is (some? c))
      (is (= :drawtexture (:kind c)))
      (is (= {:ns "demo" :path "textures/a.png"} (:texture @(:state c)))))))
