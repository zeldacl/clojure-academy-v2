(ns cn.li.mcmod.runtime.host-boundary-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.runtime.host :as host]
            [cn.li.mcmod.runtime.damage-boundary :as damage]
            [cn.li.mcmod.runtime.fixed-channel :as channel]
            [cn.li.mcmod.runtime.vfx-contract :as vfx-contract]))
(deftest host-preflight-is-atomic-before-apply-test
  (let [applied (atom []) h (host/create {:queries {} :actions {:entity/damage (fn [phase command _] (if (= :preflight phase) (not= :reject (:mode (:args command))) (swap! applied conj (:id command))))}})]
    (is (= {:ok? false :phase :preflight} (select-keys (host/execute! h [{:id :a :capability :entity/damage :args {:mode :ok}} {:id :b :capability :entity/damage :args {:mode :reject}}] {}) [:ok? :phase])))
    (is (empty? @applied))))
(deftest damage-boundary-begin-complete-test
  (let [completed (atom nil)]
    (damage/install! {:begin (fn [event] {:token :hit-1 :amount (* 2 (:base event))}) :complete (fn [result] (reset! completed result))})
    (let [resolution (damage/begin! {:world-id "w" :source "a" :target "b" :base 3 :type :skill})]
      (is (= 6.0 (:amount resolution))) (damage/complete! resolution true 6.0) (is (= true (:applied? @completed))) (is (= :hit-1 (:token @completed))))
    (damage/clear!)))
(deftest input-edge-roundtrip-is-bounded-test
  (let [packet (channel/encode-intent {:seq 12 :control-id 4 :edge :press :choice "ac:fire" :client-tick 99}) decoded (channel/decode-intent packet)]
    (is (= :input-edge (:type decoded))) (is (= "ac:fire" (:choice decoded))) (is (<= (alength packet) 64))))

(deftest input-edge-rejects-trailing-bytes-test
  (let [packet (channel/encode-intent {:seq 12 :control-id 4 :edge :press :choice nil :client-tick 99})
        oversized (byte-array (inc (alength packet)))]
    (System/arraycopy packet 0 oversized 0 (alength packet))
    (is (thrown? clojure.lang.ExceptionInfo
                 (channel/decode-intent oversized)))))

(deftest catalog-handshake-roundtrip-is-fixed-and-bounded-test
  (let [identity {:schema-version 1 :content-hash "0123456789abcdef"}
        hello (channel/decode-catalog-hello (channel/encode-catalog-hello identity))
        ack (channel/decode-catalog-ack
             (channel/encode-catalog-ack (assoc identity :accepted? false)))]
    (is (= identity (select-keys hello [:schema-version :content-hash])))
    (is (= identity (select-keys ack [:schema-version :content-hash])))
    (is (false? (:accepted? ack)))
    (is (<= (alength (channel/encode-catalog-hello identity)) 140))))

(deftest vfx-contract-accepts-neutral-quad-test
  (is (= :quad (:primitive (vfx-contract/batch
                            {:stage :world-after-translucent
                             :primitive :quad
                             :count 1
                             :payload {:operation :draw-batch}})))))

(deftest vfx-fixed-packet-roundtrip-and-operation-tag-test
  (let [signal {:op :update
                :owner "caster"
                :instance-key ["beam" 7]
                :effect-id :beam
                :event-seq 19
                :params {:length 4.0}
                :mask {:word-count 1 :words [2] :indices [1]}}
        packet (channel/encode-vfx-signal signal)
        decoded (channel/decode-vfx-signal packet)]
    (is (= signal decoded))
    (is (<= (alength packet) 65539))))

(deftest vfx-fixed-packet-rejects-type-mismatch-test
  (let [packet (channel/encode-vfx-signal {:op :spawn :effect-id :beam
                                           :instance-key ["beam" 1]})
        wrong (byte-array (alength packet))]
    (System/arraycopy packet 0 wrong 0 (alength packet))
    ;; packet byte 1 is the fixed packet type; mutate spawn (7) to update (8)
    (aset-byte wrong 1 (byte 8))
    (is (thrown? clojure.lang.ExceptionInfo
                 (channel/decode-vfx-signal wrong)))))

(deftest vfx-fixed-trigger-and-snapshot-operations-roundtrip-test
  (doseq [op [:trigger :snapshot]]
    (let [signal {:op op :owner "caster" :instance-key ["beam" 8]
                  :effect-id :beam :event-seq 20 :params {:length 4.0}}
          decoded (channel/decode-vfx-signal
                   (channel/encode-vfx-signal signal))]
      (is (= signal decoded)))))

(deftest vfx-fixed-remote-instance-id-roundtrip-test
  (let [signal {:op :snapshot :owner "caster" :instance-id 41
                :effect-id :beam :event-seq 20 :params {:length 4.0}}]
    (is (= signal
           (channel/decode-vfx-signal
            (channel/encode-vfx-signal signal))))))

(deftest vfx-fixed-packet-rejects-unbounded-payload-test
  (is (thrown? clojure.lang.ExceptionInfo
               (channel/encode-vfx-signal {:op :spawn
                                           :effect-id :beam
                                           :params (zipmap (range 65) (range 65))})))
  (is (thrown? clojure.lang.ExceptionInfo
               (channel/encode-vfx-signal {:op :update
                                           :effect-id :beam
                                           :mask {:word-count 2 :words [0 0]}})))
)

(deftest combat-feedback-fixed-packet-roundtrip-test
  (let [result {:status :rejected
                :feedback [{:type :catalog-handshake-required}]}
        decoded (channel/decode-combat-feedback
                 (channel/encode-combat-feedback result))]
    (is (= :combat-feedback (:type decoded)))
    (is (= result (select-keys decoded [:status :feedback])))))

(deftest vfx-fixed-frame-bound-test
  (let [large-value (apply str (repeat 600 "x"))
        params (zipmap (range 64) (repeat 64 large-value))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (channel/encode-vfx-signal {:op :spawn
                                           :effect-id :beam
                                           :params params}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (channel/decode-vfx-signal (byte-array 32769)))))
