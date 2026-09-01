(ns cn.li.mcmod.runtime.presentation-bridge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.runtime.presentation-bridge :as bridge]))

(use-fixtures :each
  (fn [test]
    (bridge/clear-host-for-test!)
    (try (test) (finally (bridge/clear-host-for-test!)))))

(defn complete-host [calls]
  (into {}
        (map (fn [operation]
               [operation (fn [& args]
                            (swap! calls conj [operation args])
                            {:operation operation :args args})])
             bridge/required-host-operations)))

(deftest host-contract-rejects-missing-operations
  (testing "the mcmod seam is explicit rather than silently partial"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"missing operations"
         (bridge/install-host! {:mount! identity})))))

(deftest host-forwards-neutral-lifecycle-operations
  (let [calls (atom [])
        host (complete-host calls)]
    (bridge/install-host! host)
    (is (= :dispatch-input! (:operation (bridge/dispatch-input! "screen" {:type :key}))))
    (is (= :extract-stage! (:operation (bridge/extract-stage! :hud {}))))
    (is (= :unmount! (:operation (bridge/unmount! "screen"))))
    (is (= 3 (count @calls)))))
