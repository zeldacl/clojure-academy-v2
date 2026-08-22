(ns cn.li.node.scope-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.node.descriptor :as registry]
            [cn.li.node.scope :as scope]))

(use-fixtures :each
  (fn [f]
    (registry/reset-for-test!)
    ;; A minimal synthetic vocabulary exercising all three :flow kinds plus
    ;; a callback-typed input, independent of node-core's own flow.clj so
    ;; this test proves the checker is genuinely descriptor-driven and not
    ;; secretly hardcoded to the 5 builtins.
    (registry/register-primitive!
     {:id :test/emit :revision 1 :inputs {:value {:type :any}} :outputs {} :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/produce :revision 1 :inputs {} :outputs {:result {:type :double}} :impl (fn [_ _] {:result 1.0})})
    (registry/register-primitive!
     {:id :test/seq :revision 1 :children {:steps {:kind :seq :flow :sequential}} :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/branch :revision 1
      :children {:then {:kind :single :flow :branch} :else {:kind :single :flow :branch}}
      :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/loop :revision 1 :children {:body {:kind :single :flow :closed}} :impl (fn [_ _] {})})
    (registry/register-primitive!
     {:id :test/with-callback :revision 1
      :inputs {:on-each {:type :node :scope {:item {:type :double}}}}
      :impl (fn [_ _] {})})
    (f)
    (registry/reset-for-test!)))

(deftest unbound-local-read-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unbound-local"
       (scope/check! {:component :test/emit :value {:ref [:local :never-bound]}}))))

(deftest sequential-port-threads-binds-forward-test
  (is (nil? (scope/check!
             {:component :test/seq
              :steps [{:component :test/produce :bind {:result :r}}
                      {:component :test/emit :value {:ref [:local :r]}}]}))))

(deftest sequential-order-matters-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unbound-local"
       (scope/check!
        {:component :test/seq
         :steps [{:component :test/emit :value {:ref [:local :r]}}
                 {:component :test/produce :bind {:result :r}}]}))))

(deftest branch-only-binds-name-bound-on-every-path-test
  ;; :then binds :r, :else does not -- the intersection must be empty, so a
  ;; sibling reading :r afterward is a compile error.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unbound-local"
       (scope/check!
        {:component :test/seq
         :steps [{:component :test/branch
                  :then {:component :test/produce :bind {:result :r}}
                  :else {:component :test/produce}}
                 {:component :test/emit :value {:ref [:local :r]}}]}))))

(deftest branch-binds-name-bound-on-every-path-succeeds-test
  (is (nil? (scope/check!
             {:component :test/seq
              :steps [{:component :test/branch
                       :then {:component :test/produce :bind {:result :r}}
                       :else {:component :test/produce :bind {:result :r}}}
                      {:component :test/emit :value {:ref [:local :r]}}]}))))

(deftest closed-port-does-not-leak-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unbound-local"
       (scope/check!
        {:component :test/seq
         :steps [{:component :test/loop
                  :body {:component :test/produce :bind {:result :r}}}
                 {:component :test/emit :value {:ref [:local :r]}}]}))))

(deftest callback-input-sees-declared-scope-test
  (is (nil? (scope/check!
             {:component :test/with-callback
              :on-each {:component :test/emit :value {:ref [:local :item]}}}))))

(deftest callback-input-scope-does-not-leak-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unbound-local"
       (scope/check!
        {:component :test/seq
         :steps [{:component :test/with-callback
                  :on-each {:component :test/emit :value {:ref [:local :item]}}}
                 {:component :test/emit :value {:ref [:local :item]}}]}))))

(deftest unknown-output-port-in-bind-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-output-port"
       (scope/check! {:component :test/produce :bind {:not-a-real-port :x}}))))

(deftest unknown-component-throws-test
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"unknown-component"
       (scope/check! {:component :test/does-not-exist}))))

(deftest seed-bound-is-honored-test
  (is (nil? (scope/check! {:component :test/emit :value {:ref [:local :seeded]}} #{:seeded}))))
