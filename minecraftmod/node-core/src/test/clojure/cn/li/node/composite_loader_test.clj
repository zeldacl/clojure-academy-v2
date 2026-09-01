(ns cn.li.node.composite-loader-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.node.composite-loader :as loader]))

(defn- fake-loader [documents]
  (fn [path] (get documents path)))

(deftest load-documents-returns-normalized-composites-test
  (let [docs (fake-loader
              {"manifest.edn" {:schema-version 1
                               :documents [{:kind :composite :id :test/a :resource "a.edn"}]}
               "a.edn" {:kind :composite :id :test/a :revision 1 :layer :composite
                         :inputs {} :outputs {} :body {:component :x}}})
        result (loader/load-documents {:manifest-resource "manifest.edn" :document-loader docs})]
    (is (= :composite (get-in result [:documents :test/a :layer])))))

(deftest load-documents-rejects-invalid-inputs-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"document-loader is required"
                        (loader/load-documents {:manifest-resource "manifest.edn"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid composite manifest"
                        (loader/load-documents {:manifest-resource "bad.edn"
                                                :document-loader (fake-loader {"bad.edn" {}})})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mismatch"
                        (loader/load-documents
                         {:manifest-resource "manifest.edn"
                          :document-loader (fake-loader
                                            {"manifest.edn" {:schema-version 1
                                                             :documents [{:kind :composite :id :test/a :resource "a.edn"}]}
                                             "a.edn" {:id :test/wrong :revision 1 :layer :composite
                                                       :inputs {} :body {}}})}))))

(deftest load-manifest-rejects-wrong-schema-version-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported composite manifest schema version"
                        (loader/load-documents
                         {:manifest-resource "manifest.edn"
                          :document-loader (fake-loader
                                            {"manifest.edn" {:schema-version 2 :documents []}})}))))

(deftest load-manifest-rejects-missing-schema-version-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported composite manifest schema version"
                        (loader/load-documents
                         {:manifest-resource "manifest.edn"
                          :document-loader (fake-loader
                                            {"manifest.edn" {:documents []}})}))))

(deftest load-manifest-rejects-duplicate-ids-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"duplicate ids"
                        (loader/load-documents
                         {:manifest-resource "manifest.edn"
                          :document-loader (fake-loader
                                            {"manifest.edn"
                                             {:schema-version 1
                                              :documents [{:kind :composite :id :test/a :resource "a.edn"}
                                                         {:kind :composite :id :test/a :resource "b.edn"}]}})}))))
