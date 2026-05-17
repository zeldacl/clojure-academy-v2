(ns cn.li.mcmod.gui.xml-parser-data-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.xml-parser :as parser]))

(defn- elem
  ([tag] {:tag tag :content []})
  ([tag content] {:tag tag :content content}))

(deftest xml-parser-core-test
  (testing "parse-* functions parse valid component and element data"
    (let [transform-node {:tag :Component
                          :content [(elem :width ["180"])
                                    (elem :height ["200"])
                                    (elem :x ["2"])
                                    (elem :y ["3"])
                                    (elem :scale ["1.5"])
                                    (elem :doesDraw ["true"])]}
          texture-node {:tag :Component
                        :content [(elem :texture ["academy:block/a"])
                                  (elem :color [(elem :red ["1"])
                                                (elem :green ["2"])
                                                (elem :blue ["3"])
                                                (elem :alpha ["4"])])
                                  (elem :zLevel ["2.5"])]}
          slot-node {:tag :Slot
                     :content [(elem :index ["4"]) (elem :x ["5"]) (elem :y ["6"])
                               (elem :filter ["energy"]) (elem :tooltip ["tip"])]}
          button-node {:tag :Button
                       :attrs {:name "ok"}
                       :content [(elem :x ["1"]) (elem :y ["2"]) (elem :width ["9"]) (elem :height ["10"])
                                 (elem :texture ["academy:ui/btn"]) (elem :scale ["0.5"])
                                 (elem :pageId ["main"]) (elem :tooltip ["hello"])]}
          label-node {:tag :Label
                      :content [(elem :x ["8"]) (elem :y ["9"]) (elem :text ["txt"]) (elem :color ["#010203"])]}
          transform (parser/parse-transform transform-node)
          tex (parser/parse-draw-texture texture-node)
          slot (parser/parse-slot slot-node)
          button (parser/parse-button button-node)
          label (parser/parse-label label-node)]
      (is (= 180.0 (:width transform)))
      (is (= 200.0 (:height transform)))
      (is (= true (:does-draw transform)))
      (is (= "block/a" (:texture tex)))
      (is (= {:red 1 :green 2 :blue 3 :alpha 4} (:color tex)))
      (is (= 2.5 (:z-level tex)))
      (is (= {:index 4 :x 5 :y 6 :filter :energy :tooltip "tip"} slot))
      (is (= "ok" (:id button)))
      (is (= "ui/btn" (:texture button)))
      (is (= :main (:page-id button)))
      (is (= "hello" (:tooltip button)))
      (is (= 66051 (:color label))))))

(deftest xml-parser-edge-cases-test
  (testing "defaults are used when optional fields are missing"
    (is (= {:width 176.0 :height 187.0 :x 0.0 :y 0.0 :scale 1.0 :does-draw true}
           (parser/parse-transform (elem :Component []))))
    (is (= {:texture nil
            :color {:red 255 :green 255 :blue 255 :alpha 255}
            :z-level 0.0}
           (parser/parse-draw-texture (elem :Component []))))
    (is (= "button" (:id (parser/parse-button (elem :Button [])))))
    (is (= 0x404040 (:color (parser/parse-label (elem :Label [])))))
    (is (= [] (:states (parser/parse-animation (elem :Animation [])))))
    (is (= "assets/my_mod/gui/layouts/page.xml"
           (parser/resolve-gui-layout-path "page")))))

(deftest xml-widget-contract-test
  (testing "parse-widget and xml-to-dsl-spec collect nested controls recursively"
    (let [root {:tag :Widget
                :attrs {:name "main"}
                :content [{:tag :Component
                           :attrs {:class "Transform"}
                           :content [(elem :width ["188"]) (elem :height ["166"])]}
                          {:tag :Component
                           :attrs {:class "DrawTexture"}
                           :content [(elem :texture ["academy:gui/bg"])]}
                          {:tag :Slot :content [(elem :index ["0"]) (elem :x ["1"]) (elem :y ["2"])]}
                          {:tag :Button :attrs {:name "b0"} :content [(elem :x ["5"]) (elem :y ["6"])]}
                          {:tag :Label :content [(elem :text ["hello"])]}
                          {:tag :Widget
                           :attrs {:name "child"}
                           :content [{:tag :Slot :content [(elem :index ["1"]) (elem :x ["3"]) (elem :y ["4"])]}
                                     {:tag :Button :attrs {:name "b1"} :content []}
                                     {:tag :Label :content [(elem :text ["world"])]}]}]}
          tree (parser/parse-widget root)
          spec (parser/xml-to-dsl-spec tree "gui-id")]
      (is (= "main" (:name tree)))
      (is (= 1 (count (:children tree))))
      (is (= 2 (count (get-in spec [:layout :slots]))))
      (is (= 2 (count (get-in spec [:layout :buttons]))))
      (is (= 2 (count (get-in spec [:layout :labels]))))
      (is (= "gui-id" (:id spec)))
      (is (= 188.0 (get-in spec [:layout :width])))
      (is (= 166.0 (get-in spec [:layout :height])))
      (is (= "gui/bg" (get-in spec [:layout :background])))
      (is (map? (:widget-tree spec))))))
