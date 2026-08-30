(ns cn.li.vfx.network-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.vfx.network :as network])
  (:import [cn.li.mcmod.runtime.vfx VfxWireCodec VfxPacketKind VfxLifecyclePacket]))

(deftest lifecycle-wire-roundtrip-test
  (let [packet (network/signal->packet {:op :spawn :world-epoch 7 :instance-id 12
                                        :asset-id 42 :state-seq 3 :event-seq 4
                                        :owner "00000000-0000-0000-0000-000000000001"
                                        :start-server-tick 99 :seed 123})
        decoded (VfxWireCodec/decode (VfxWireCodec/encode packet))]
    (is (instance? VfxLifecyclePacket decoded))
    (is (= VfxPacketKind/SPAWN (.kind decoded)))
    (is (= 12 (.instanceId decoded)))
    (is (= 3 (.stateSequence decoded)))
    (is (= 4 (.eventSequence decoded)))))

(deftest packet-direction-is-closed-test
  (let [hello (network/catalog-hello 9)
        ack (network/catalog-ack 9 true "ok")]
    (is (network/validate-direction! :s2c hello))
    (is (network/validate-direction! :c2s ack))
    (is (thrown? clojure.lang.ExceptionInfo
                 (network/validate-direction! :c2s hello)))))
