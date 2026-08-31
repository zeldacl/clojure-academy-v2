(ns cn.li.mcmod.runtime.presentation-bridge-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [cn.li.mcmod.runtime.presentation-bridge :as bridge])
  (:import [cn.li.mcmod.runtime UiEditCommand$Remove]))

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

(deftest host-forwards-neutral-edit-and-lifecycle-operations
  (let [calls (atom [])
        host (complete-host calls)
        command (UiEditCommand$Remove. "tree")]
    (bridge/install-host! host)
    (is (= :apply-edit! (:operation (bridge/apply-edit! "screen" command))))
    (is (= :undo-edit! (:operation (bridge/undo-edit! "screen"))))
    (is (= :redo-edit! (:operation (bridge/redo-edit! "screen"))))
    (is (= :reset-edits! (:operation (bridge/reset-edits! "screen"))))
    (is (= 4 (count @calls)))
    (is (= ["screen" command] (second (first @calls))))))

(deftest edit-forwarding-rejects-non-neutral-values
  (bridge/install-host! (complete-host (atom [])))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"UiEditCommand"
       (bridge/apply-edit! "screen" {:target-key "tree"}))))
