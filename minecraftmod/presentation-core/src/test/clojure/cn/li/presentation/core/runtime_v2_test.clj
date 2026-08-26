(ns cn.li.presentation.core.runtime-v2-test
  (:require [clojure.test :refer :all]
            [cn.li.presentation.core.runtime-v2 :as runtime])
  (:import [cn.li.presentation.core HostGeometry]))

(def sample-artifact
  {:magic :pui2 :schema 2 :view-id :academy/test/runtime
   :semantics {:role :generic :label "test"}})

(deftest runtime-commits-state-before-effects
  (let [effects (atom [])
        rt (runtime/create-runtime)
        mount (runtime/mount!
                rt {:host {:stage :screen}
                    :view-id :academy/test/runtime
                    :artifact sample-artifact
                    :state {:value 0}
                    :reduce (fn [state action payload]
                              (if (= action :increment)
                                {:state (update state :value + payload)
                                 :effects [{:type :record :value (+ (:value state) payload)}]
                                 :event-result :consume}
                                {:state state :effects [] :event-result :pass}))
                    :run-effect! #(swap! effects conj %)} )]
    (is (= :consume (runtime/dispatch! rt mount {:action :increment :payload 2})))
    (is (= [{:type :record :value 2}] @effects))
    (is (= {:role :generic :label "test"} (runtime/semantics rt mount)))
    (runtime/update-host! rt mount (HostGeometry. 10.0 20.0 320 240 1.0))
    (is (= mount (-> (runtime/extract-stage! rt :screen {:time-nanos 1}) :mounts first :handle)))))

(deftest runtime-requires-owner-thread
  (let [foreign-thread (Thread.)
        rt (runtime/create-runtime {:owner-thread foreign-thread})]
    (is (thrown? IllegalStateException (runtime/unmount-all! rt)))))