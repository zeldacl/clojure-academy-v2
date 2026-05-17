(ns cn.li.mcmod.gui.xml-parser-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.xml-parser :as parser]
            [clojure.java.io :as io]
            [clojure.xml :as xml]
            [cn.li.mcmod.gui.cgui-core :as cgui-core]))

(deftest parse-xml-layout-contract-test
  (testing "resource missing throws ex-info with :path"
    (with-redefs [io/resource (fn [_] nil)]
      (try
        (parser/parse-xml-layout "assets/my_mod/gui/layouts/missing.xml")
        (is false)
        (catch clojure.lang.ExceptionInfo e
          (is (= "assets/my_mod/gui/layouts/missing.xml" (:path (ex-data e))))))))
  (testing "missing Widget root throws explicit exception"
    (with-redefs [io/resource (fn [_] ::dummy)
                  io/input-stream (fn [_] ::stream)
                  xml/parse (fn [_] {:tag :root :content []})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"No Widget element found"
           (parser/parse-xml-layout "assets/my_mod/gui/layouts/no_widget.xml")))))
  (testing "successful parse delegates to parse-widget result"
    (with-redefs [io/resource (fn [_] ::dummy)
                  io/input-stream (fn [_] ::stream)
                  xml/parse (fn [_] {:tag :root :content [{:tag :Widget :attrs {:name "main"} :content []}]})
                  parser/parse-widget (fn [_] {:name "parsed"})]
      (is (= {:name "parsed"}
             (parser/parse-xml-layout "assets/my_mod/gui/layouts/ok.xml"))))))

(deftest load-gui-from-xml-contract-test
  (testing "load-gui-from-xml composes resolve + parse + convert with compatible arguments"
    (let [calls (atom [])]
      (with-redefs [parser/resolve-gui-layout-path (fn [layout]
                                                     (swap! calls conj [:resolve layout])
                                                     "assets/my_mod/gui/layouts/p.xml")
                    parser/parse-xml-layout (fn [path]
                                              (swap! calls conj [:parse path])
                                              {:name "tree"})
                    parser/xml-to-dsl-spec (fn [tree gui-id]
                                             (swap! calls conj [:convert tree gui-id])
                                             {:id gui-id :widget-tree tree})]
        (is (= {:id "gui/demo" :widget-tree {:name "tree"}}
               (parser/load-gui-from-xml "gui/demo" "p")))
        (is (= [[:resolve "p"]
                [:parse "assets/my_mod/gui/layouts/p.xml"]
                [:convert {:name "tree"} "gui/demo"]]
               @calls))))))

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
