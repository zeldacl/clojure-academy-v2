(ns cn.li.mcmod.runtime.input-session-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.runtime.input-session :as session]))

(defn encode-intent [intent]
  ((requiring-resolve 'cn.li.mcmod.runtime.fixed-channel/encode-intent) intent))

(defn packet [seq edge]
  (encode-intent {:seq seq :control-id 3 :edge edge :choice "railgun" :client-tick seq}))

(deftest dedupe-and-rate-limit-test
  (let [events (atom [])
        aborted (atom [])
        s (session/create-session {:owner "alice"
                                   :on-intent! #(swap! events conj %)
                                   :on-abort! #(swap! aborted conj %)})]
    (is (= :accepted (:status (session/receive! s (packet 1 :press) 0))))
    (is (= :ignored (:status (session/receive! s (packet 1 :release) 1))))
    (is (= 1 (count @events)))
    (doseq [seq (range 2 41)] (session/receive! s (packet seq :press) 2))
    (is (= :rejected (:status (session/receive! s (packet 41 :press) 3))))
    (is (= :rate-limit (:abort-reason (session/state s))))
    (is (some #{:rate-limit} @aborted))))

(deftest lifecycle-abort-is-idempotent-test
  (let [aborted (atom [])
        s (session/create-session {:on-abort! #(swap! aborted conj %)})]
    (is (= :aborted (:status (session/lifecycle-abort! s :disconnect))))
    (is (= :aborted (:status (session/lifecycle-abort! s :death))))
    (is (= [:disconnect] @aborted))))
