(ns cn.li.node.composite-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.flow :as flow]
            [cn.li.node.composite :as composite]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    (flow/install!)
    (registry/register-primitive!
     {:id :test/query :revision 1 :inputs {:origin {:type :vec3}} :outputs {:hit {:type :hit-result}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/resolve :revision 1 :inputs {:hit {:type :hit-result}} :outputs {:destination {:type :destination}}
      :impl (fn [_ _] {})})
    (registry/register-composite!
     {:id :test/query-destination :revision 1 :layer :mid
      :inputs {:origin {:type :vec3}}
      :outputs {:destination {:type :destination :from [:local :dest]}}
      :body {:component :flow/sequence
             :steps [{:component :test/query :origin {:ref [:input :origin]} :bind {:hit :h}}
                     {:component :test/resolve :hit {:ref [:local :h]} :bind {:destination :dest}}]}})
    (registry/register-composite!
     {:id :test/no-output :revision 1 :layer :mid
      :inputs {}
      :outputs {}
      :body {:component :test/query :origin {:vec3 [0.0 0.0 0.0]}}})
    (registry/register-composite!
     {:id :test/source :revision 1 :layer :source :outputs {:value {:type :double}}})
    ;; Mimics a domain whose own runtime value language also uses :input as
    ;; a scope name for something other than composite parameters (vfx-
    ;; core's :input instance-signal scope) -- a structural primitive that
    ;; introduces its own :input-keyed binding for its body, at a key the
    ;; enclosing composite never declares.
    (registry/register-primitive!
     {:id :test/native-scope :revision 1 :children {:body {:kind :single}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/emit :revision 1 :inputs {:value {:type :any}} :impl (fn [_ _] {})})
    (f)
    (registry/reset-for-test!)))

(deftest expand-substitutes-inputs-and-produces-only-primitives-test
  (let [expanded (composite/expand {:component :test/query-destination :origin {:vec3 [1.0 2.0 3.0]}
                                    :bind {:destination :final}})]
    ;; The whole thing collapses into a :flow/sequence of real primitives --
    ;; no :test/query-destination id survives expansion.
    (is (= :flow/sequence (:component expanded)))
    (is (every? (fn [step] (not= :test/query-destination (:component step))) (:steps expanded)))
    ;; origin substituted as a literal into the first real query.
    (is (= {:vec3 [1.0 2.0 3.0]} (:origin (first (:steps expanded)))))
    ;; the requested output bind becomes a final :data/bind step.
    (let [last-step (last (:steps expanded))]
      (is (= :data/bind (:component last-step)))
      (is (= :final (:to last-step))))))

(deftest expand-with-no-output-request-does-not-add-bind-step-test
  (let [expanded (composite/expand {:component :test/no-output})]
    (is (= :test/query (:component expanded)))))

(deftest two-call-sites-get-independently-renamed-locals-test
  (let [a (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bind {:destination :a}})
        b (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bind {:destination :b}})
        internal-name (fn [expanded] (get-in expanded [:steps 0 :bind :hit]))]
    (is (not= (internal-name a) (internal-name b)))))

(deftest unknown-composite-input-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-composite-input"
       (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]} :bogus 1}))))

(deftest missing-required-input-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"missing-required-field"
       (composite/expand {:component :test/query-destination}))))

(deftest unknown-output-port-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-output-port"
       (composite/expand {:component :test/query-destination :origin {:vec3 [0.0 0.0 0.0]}
                          :bind {:not-a-port :x}}))))

(deftest source-node-inside-composite-body-throws-test
  (registry/register-composite!
   {:id :test/bad-composite :revision 1 :layer :mid :inputs {} :outputs {}
    :body {:component :test/source}})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"source-node-outside-ability"
       (composite/expand {:component :test/bad-composite}))))

(deftest undeclared-input-ref-inside-nested-native-scope-survives-expansion-test
  ;; Regression: substitute-inputs used to replace EVERY {:ref [:input k]}
  ;; against the composite's OWN declared inputs, including k's that
  ;; belong to a domain-native structural primitive's own runtime binding
  ;; (e.g. vfx-core's :vfx/repeat introducing {:ref [:input :i]} for its
  ;; own loop index) -- (get inputs :undeclared-key) is nil, so the whole
  ;; ref node got silently replaced with nil instead of surviving for that
  ;; domain's own runtime resolver to handle later.
  (registry/register-composite!
   {:id :test/wraps-native-scope :revision 1 :layer :mid
    :inputs {:declared {:type :any}}
    :outputs {}
    :body {:component :test/native-scope
           :body {:component :test/emit :value {:ref [:input :undeclared-key]}}}})
  (let [expanded (composite/expand {:component :test/wraps-native-scope :declared 1.0})]
    (is (= {:ref [:input :undeclared-key]} (get-in expanded [:body :value]))
        "an :input ref to a key this composite never declared must survive expansion untouched")))

(deftest declared-input-ref-inside-nested-native-scope-still-substitutes-test
  (registry/register-composite!
   {:id :test/wraps-native-scope-2 :revision 1 :layer :mid
    :inputs {:declared {:type :any}}
    :outputs {}
    :body {:component :test/native-scope
           :body {:component :test/emit :value {:ref [:input :declared]}}}})
  (let [expanded (composite/expand {:component :test/wraps-native-scope-2 :declared 42.0})]
    (is (= 42.0 (get-in expanded [:body :value]))
        "a ref to a genuinely declared input still substitutes as before")))

(deftest flow-foreach-as-binding-stays-in-sync-with-its-readers-after-renaming-test
  ;; Regression: rename-locals renamed every {:ref [:local ...]} read
  ;; consistently, but :flow/foreach's OWN loop-variable binding (:as, a
  ;; bare keyword field, not the generic :bind {port -> local} convention)
  ;; was never renamed to match -- the reader's ref pointed at the renamed
  ;; name while the binder still produced the original, so the two never
  ;; matched at runtime (caught by combat-core's :combat/area-damage
  ;; composite test, which got nil for the loop-bound entity every time).
  (registry/register-composite!
   {:id :test/uses-foreach :revision 1 :layer :mid
    :inputs {:items {:type [:list-of :any]}}
    :outputs {}
    :body {:component :flow/foreach :items {:ref [:input :items]} :as :target
           :body {:component :test/emit :value {:ref [:local :target]}}}})
  (let [expanded (composite/expand {:component :test/uses-foreach :items [1.0 2.0 3.0]})
        as-name (:as expanded)
        read-name (second (get-in expanded [:body :value :ref]))]
    (is (some? as-name))
    (is (= as-name read-name)
        "the renamed :as binding and the renamed reader ref must be the SAME local name")))

(deftest data-bind-to-binding-stays-in-sync-with-its-readers-after-renaming-test
  ;; Same bug class as the :flow/foreach :as test above, one component
  ;; later: :data/bind's :to field introduces a new local the same way
  ;; :flow/foreach's :as does, but :data/bind's own registration lacked
  ;; :binds-locals -- so a write via :to stayed unrenamed while every
  ;; {:ref [:local ...]} read of that name WAS renamed. Caught by
  ;; :combat/break-budget's accumulator-threading composite (:data/bind
  ;; :to :remaining, read back one :flow/foreach iteration later), which
  ;; got nil for :remaining on the very first iteration.
  (registry/register-composite!
   {:id :test/uses-data-bind :revision 1 :layer :mid
    :inputs {}
    :outputs {}
    :body {:component :flow/sequence
           :steps [{:component :data/bind :to :acc :value 1.0}
                   {:component :test/emit :value {:ref [:local :acc]}}]}})
  (let [expanded (composite/expand {:component :test/uses-data-bind})
        bind-to-name (get-in expanded [:steps 0 :to])
        read-name (second (get-in expanded [:steps 1 :value :ref]))]
    (is (some? bind-to-name))
    (is (= bind-to-name read-name)
        "the renamed :data/bind :to and the renamed reader ref must be the SAME local name")))

(deftest cycle-detection-throws-test
  (registry/register-composite!
   {:id :test/cyclic :revision 1 :layer :mid :inputs {} :outputs {}
    :body {:component :test/cyclic}})
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"composite-expansion-cycle"
       (composite/expand {:component :test/cyclic}))))
