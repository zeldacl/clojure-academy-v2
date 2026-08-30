(ns cn.li.node.composite-loader-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as node]
            [cn.li.node.composite-loader :as loader]))

(use-fixtures :each
  (fn [f]
    (node/reset-for-test!)
    (f)
    (node/reset-for-test!)))

(defn- fake-loader
  "A document-loader backed by a plain map instead of the filesystem/
   classpath -- proves the manifest/fail-closed logic without touching
   safe-edn or real resources (that integration is each domain's thin
   wrapper's own concern, not this generic loader's)."
  [documents]
  (fn [path] (get documents path)))

(deftest install-registers-every-well-formed-document-test
  (let [docs (fake-loader
              {"manifest.edn" {:documents [{:kind :composite :id :test/a :resource "a.edn"}
                                           {:kind :composite :id :test/b :resource "b.edn"}]}
               "a.edn" {:kind :composite :id :test/a :revision 1 :layer :composite :inputs {} :outputs {} :body {:component :x}}
               "b.edn" {:kind :composite :id :test/b :revision 1 :layer :composite :inputs {} :outputs {} :body {:component :y}}})
        result (loader/install! {:manifest-resource "manifest.edn" :document-loader docs})]
    (is (= [:test/a :test/b] (:registered result)))
    (is (empty? (:errors result)))
    (is (= :mid (:layer (node/descriptor :test/a))))
    (is (= :mid (:layer (node/descriptor :test/b))))))

(deftest install-requires-a-document-loader-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"document-loader is required"
                        (loader/install! {:manifest-resource "manifest.edn"}))))

(deftest install-rejects-invalid-manifest-shape-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid composite manifest"
                        (loader/install! {:manifest-resource "bad.edn" :document-loader (fake-loader {"bad.edn" {}})}))))

(deftest install-is-fail-closed-per-document-test
  (let [docs (fake-loader
              {"manifest.edn" {:documents [{:kind :composite :id :test/good :resource "good.edn"}
                                           {:kind :composite :id :test/bad :resource "bad.edn"}
                                           {:kind :composite :id :test/also-good :resource "also-good.edn"}]}
               "good.edn" {:kind :composite :id :test/good :revision 1 :layer :composite :inputs {} :outputs {} :body {:component :x}}
               "bad.edn" {:kind :composite :id :test/wrong-id :revision 1 :layer :composite :inputs {} :outputs {} :body {:component :x}}
               "also-good.edn" {:kind :composite :id :test/also-good :revision 1 :layer :composite :inputs {} :outputs {} :body {:component :x}}})
        result (loader/install! {:manifest-resource "manifest.edn" :document-loader docs})]
    (is (= [:test/good :test/also-good] (:registered result)))
    (is (= 1 (count (:errors result))))
    (is (= :test/bad (:id (first (:errors result)))))
    (is (some? (node/descriptor :test/good)))
    (is (nil? (node/descriptor :test/wrong-id)) "the mismatched document must not have been registered")
    (is (some? (node/descriptor :test/also-good)) "one bad document must not stop the rest from loading")))

(deftest install-rejects-non-composite-manifest-kind-test
  (let [docs (fake-loader {"manifest.edn" {:documents [{:kind :ability :id :test/x :resource "x.edn"}]}})
        result (loader/install! {:manifest-resource "manifest.edn" :document-loader docs})]
    (is (empty? (:registered result)))
    (is (= 1 (count (:errors result))))))

(deftest install-rejects-document-missing-required-composite-shape-test
  (let [docs (fake-loader
              {"manifest.edn" {:documents [{:kind :composite :id :test/malformed :resource "malformed.edn"}]}
               "malformed.edn" {:kind :composite :id :test/malformed :revision 1 :layer :primitive}})
        result (loader/install! {:manifest-resource "manifest.edn" :document-loader docs})]
    (is (empty? (:registered result)))
    (is (= 1 (count (:errors result))))))

