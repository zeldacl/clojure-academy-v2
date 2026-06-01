(ns cn.li.mcmod.gui.xml-parser-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.xml-parser :as parser]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]))

(deftest get-widget-contract-test
  (testing "root-name match returns root directly; find fallback works; main fallback returns doc"
    (let [doc {:doc true}]
      (with-redefs [cgui-core/get-name (fn [_] "  main ")
                    cgui-core/find-widget (fn [_ _] :not-used)]
        (is (= doc (parser/get-widget doc "main"))))
      (with-redefs [cgui-core/get-name (fn [_] "root")
                    cgui-core/find-widget (fn [_ name] (when (= name "x") {:widget :x}))]
        (is (= {:widget :x} (parser/get-widget doc "x"))))
      (with-redefs [cgui-core/get-name (fn [_] "root")
                    cgui-core/find-widget (fn [_ _] nil)]
        (is (= doc (parser/get-widget doc "main")))))))
