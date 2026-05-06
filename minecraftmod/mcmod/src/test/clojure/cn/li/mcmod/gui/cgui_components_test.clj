(ns cn.li.mcmod.gui.cgui-components-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.cgui :as cgui]
            [cn.li.mcmod.gui.components :as comp]
            [cn.li.mcmod.platform.resource :as res]))

(deftest cgui-focus-and-events-test
  (let [cgui (cgui/create-cgui)
        root (cgui/get-root cgui)
        child (cgui/create-widget :name "inner")
        gained (atom [])]
    (cgui/cgui-add-widget! cgui child)
    (cgui/listen-widget-event! child :gain-focus (fn [e] (swap! gained conj e)))
    (cgui/gain-focus! root child)
    (is (identical? child (cgui/get-focus root)))
    (is (= 1 (count @gained)))
    (cgui/remove-focus! root)
    (is (nil? (cgui/get-focus root)))))

(deftest cgui-find-widget-by-path-test
  (let [root (cgui/create-container :name "root")
        mid (cgui/create-container :name "mid")
        leaf (cgui/create-widget :name "leaf")]
    (cgui/add-widget! mid leaf)
    (cgui/add-widget! root mid)
    (is (identical? leaf (cgui/find-widget root "mid/leaf")))))

(deftest add-texture-component-with-resource-fn-test
  (binding [res/*resource-location-fn* (fn [ns path] {:ns ns :path path})]
    (let [w (cgui/create-widget)
          _ (comp/add-component! w (comp/texture "demo:textures/a.png"))
          c (comp/get-drawtexture-component w)]
      (is (some? c))
      (is (= :drawtexture (:kind c)))
      (is (= {:ns "demo" :path "textures/a.png"} (:texture @(:state c)))))))
