(ns cn.li.ac.ability.client.fx-registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]))

(defn- reset-fixture [f]
  (fx-registry/call-with-fx-registry-runtime
    (fx-registry/create-fx-registry-runtime)
    (fn []
      (fx-registry/reset-fx-registry-for-test!)
      (try
        (f)
        (finally
          (fx-registry/reset-fx-registry-for-test!))))))

(use-fixtures :each reset-fixture)

(deftest register-dispatch-freeze-snapshot-test
  (let [calls* (atom [])
        handler (fn [ctx-id channel payload]
                  (swap! calls* conj [ctx-id channel payload])
                  nil)]
    (fx-registry/register-fx-channel! :unit/a handler)
    (fx-registry/register-fx-channel! :unit/a (fn [& _] :ignored-duplicate))
    (fx-registry/register-fx-channels! [:unit/b :unit/c] handler)
    (is (= #{:unit/a :unit/b :unit/c}
           (fx-registry/registered-channels)))
    (is (= {:handlers {:unit/a handler
                       :unit/b handler
                       :unit/c handler}
            :frozen? false}
           (fx-registry/fx-registry-snapshot)))
    (is (true? (fx-registry/dispatch-fx-channel! "ctx-1" :unit/a {:k 1})))
    (is (false? (fx-registry/dispatch-fx-channel! "ctx-1" :unit/none {:k 2})))
    (is (= [["ctx-1" :unit/a {:k 1}]] @calls*))
    (fx-registry/freeze-fx-registry!)
    (is (:frozen? (fx-registry/fx-registry-snapshot)))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"frozen"
          (fx-registry/register-fx-channel! :unit/d handler)))))

(deftest fx-registry-runtime-isolation-test
  (let [runtime-a (fx-registry/create-fx-registry-runtime)
        runtime-b (fx-registry/create-fx-registry-runtime)
        handler-a (fn [& _] nil)
        handler-b (fn [& _] nil)]
    (fx-registry/call-with-fx-registry-runtime
      runtime-a
      (fn []
        (fx-registry/register-fx-channel! :iso/a handler-a)
        (is (= #{:iso/a}
               (fx-registry/registered-channels)))))
    (fx-registry/call-with-fx-registry-runtime
      runtime-b
      (fn []
        (is (empty? (fx-registry/registered-channels)))
        (fx-registry/register-fx-channel! :iso/b handler-b)
        (is (= #{:iso/b}
               (fx-registry/registered-channels)))))
    (fx-registry/call-with-fx-registry-runtime
      runtime-a
      (fn []
        (is (= #{:iso/a}
               (fx-registry/registered-channels)))))))
