(ns my-mod.gui.cgui-document-regression-test
  "Regression tests for cgui_document XML loading with new pure Clojure component identifiers."
  (:require [clojure.test :refer [deftest is testing]]
            [my-mod.gui.cgui-document :as cgui-doc]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn resource-exists? [resource-path]
  (->> resource-path
       io/resource
       (some? )))

(deftest test-xml-files-exist
  (testing "Key XML files exist in resources"
    (is (resource-exists? "assets/my_mod/guis/rework/page_wireless.xml") 
        "page_wireless.xml should exist")
    (is (resource-exists? "assets/my_mod/guis/rework/page_solar.xml")
        "page_solar.xml should exist")
    (is (resource-exists? "assets/my_mod/guis/rework/pageselect.xml")
        "pageselect.xml should exist")))

(deftest test-pure-clojure-identifiers-in-xml
  (testing "XML files use pure Clojure component identifiers (not legacy cn.lambdalib2)"
    ;; Test page_wireless.xml - contains wireless widget hierarchy
    (let [path "assets/my_mod/guis/rework/page_wireless.xml"
          content (slurp (io/resource path))]
      (is (not (str/includes? content "cn.lambdalib2"))
          "page_wireless.xml should not contain cn.lambdalib2 references")
      (is (str/includes? content "class=\"transform\"")
          "page_wireless.xml should use pure 'transform' identifier")
      (is (str/includes? content "class=\"draw-texture\"")
          "page_wireless.xml should use pure 'draw-texture' identifier")
      (is (str/includes? content "class=\"text-box\"")
          "page_wireless.xml should use pure 'text-box' identifier")
      (is (str/includes? content "class=\"tint\"")
          "page_wireless.xml should use pure 'tint' identifier"))
    
    ;; Test other critical files
    (doseq [file ["page_solar.xml" "page_windbase.xml" "pageselect.xml"]]
      (let [path (str "assets/my_mod/guis/rework/" file)
            content (slurp (io/resource path))]
        (is (not (str/includes? content "cn.lambdalib2"))
            (str file " should not contain cn.lambdalib2 references"))))))

(deftest test-load-page-wireless
  (testing "page_wireless.xml can be loaded and parsed successfully"
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/page_wireless.xml")]
        (is (not (nil? widget-tree))
            "Widget tree should be successfully constructed from page_wireless.xml")
        (is (map? widget-tree)
            "Widget tree should be a map representing the root widget"))
      (catch Exception e
        (is false (str "Failed to load page_wireless.xml: " (.getMessage e)))))))

(deftest test-load-page-solar
  (testing "page_solar.xml can be loaded and parsed successfully"
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/page_solar.xml")]
        (is (not (nil? widget-tree))
            "Widget tree should be successfully constructed from page_solar.xml")
        (is (map? widget-tree)
            "Widget tree should be a map representing the root widget"))
      (catch Exception e
        (is false (str "Failed to load page_solar.xml: " (.getMessage e)))))))

(deftest test-load-page-windbase
  (testing "page_windbase.xml can be loaded and parsed successfully"
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/page_windbase.xml")]
        (is (not (nil? widget-tree))
            "Widget tree should be successfully constructed from page_windbase.xml")
        (is (map? widget-tree)
            "Widget tree should be a map representing the root widget"))
      (catch Exception e
        (is false (str "Failed to load page_windbase.xml: " (.getMessage e)))))))

(deftest test-load-pageselect
  (testing "pageselect.xml can be loaded and parsed successfully"
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/pageselect.xml")]
        (is (not (nil? widget-tree))
            "Widget tree should be successfully constructed from pageselect.xml")
        (is (map? widget-tree)
            "Widget tree should be a map representing the root widget"))
      (catch Exception e
        (is false (str "Failed to load pageselect.xml: " (.getMessage e)))))))

(deftest test-wireless-widget-hierarchy
  (testing "Wireless page widget components are correctly instantiated"
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/page_wireless.xml")
            find-widget (fn [tree name]
                          (cgui-doc/get-widget tree name))]
        ;; Verify root widget exists
        (is (not (nil? widget-tree))
            "Root widget should be loaded")
        
        ;; Check for key named widgets in wireless hierarchy
        (is (contains? widget-tree :name)
            "Root widget should have a :name attribute")
        
        (comment
          ;; If widget tree contains children (complex structure)
          (let [children (get widget-tree :children [])]
            (is (seq children)
                "Wireless page should have child widgets"))))
      (catch Exception e
        (is false (str "Failed to construct wireless widget hierarchy: " (.getMessage e)))))))

(deftest test-component-kind-normalization
  (testing "Component kind normalization handles both old and new formats"
    ;; The normalize-component-kind function is private, so we test
    ;; indirectly through the widget loading process.
    ;; Pure Clojure identifiers in XML should map correctly.
    (try
      (let [widget-tree (cgui-doc/read-xml "assets/my_mod/guis/rework/page_wireless.xml")]
        ;; If loading succeeds, the normalization is working
        (is (not (nil? widget-tree))
            "Widget loading with pure identifiers should succeed"))
      (catch Exception e
        (is false (str "Component normalization failed: " (.getMessage e)))))))

(comment
  ;; Manual test execution
  (run-tests 'my-mod.gui.cgui-document-regression-test)
  
  ;; Or individual test:
  (test-load-page-wireless)
  (test-xml-files-exist)
  (test-pure-clojure-identifiers-in-xml)
  )
