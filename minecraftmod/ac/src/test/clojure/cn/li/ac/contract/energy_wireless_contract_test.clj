(ns cn.li.ac.contract.energy-wireless-contract-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.integration.block.energy-converter.base :as converter-base]
            [cn.li.ac.integration.block.energy-converter.config :as converter-config]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network-membership :as network-membership]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.network-runtime :as network-runtime]
            [cn.li.ac.wireless.config :as network-config]
            [cn.li.ac.wireless.data.world :as wdata]
            [cn.li.mcmod.platform.be :as platform-be]))

(defn- test-world
  [world-id]
  {:server-session-id :test-session
   :world-id world-id})

(deftest energy-converter-capacity-contract-test
  (testing "converter energy stays within [0, capacity] and respects io direction"
    (let [state* (atom nil)
          fake-be (Object.)
          in-cap (converter-base/make-energy-capability fake-be "rf-input")
          out-cap (converter-base/make-energy-capability fake-be "rf-output")]
      (with-redefs [platform-be/get-custom-state (fn [_] @state*)
                    platform-be/set-custom-state! (fn [_ v] (reset! state* v))]
        (is (= 0.0 (converter-base/set-energy! fake-be -10.0)))
         (is (= (double (converter-config/energy-capacity))
           (converter-base/set-energy! fake-be (+ (converter-config/energy-capacity) 500.0))))
        (converter-base/set-energy! fake-be 0.0)
        (is (= 0 (.extractEnergy in-cap 200 false)))
        (is (= 150 (.receiveEnergy in-cap 150 false)))
        (is (= 0 (.receiveEnergy out-cap 200 false)))
        (is (= 150 (.extractEnergy out-cap 200 false)))))))

(deftest wireless-network-balance-and-auth-contract-test
  (testing "wireless network keeps auth/range checks and balances node energy"
    (let [world-key (test-world :contract-world)
          wd (wdata/create-world-data world-key)
          matrix-vb (vb/create-vmatrix 0 0 0)
          node-a-vb (vb/create-vnode 3 0 0)
          node-b-vb (vb/create-vnode 5 0 0)
          tiles (atom {[0 0 0] (stubs/fake-matrix {:capacity 4 :matrix-range 32.0 :bandwidth 5000.0})
                       [3 0 0] (stubs/mutable-node {:energy 900.0 :max-energy 1000.0 :bandwidth 1.0e6})
                       [5 0 0] (stubs/mutable-node {:energy 100.0 :max-energy 1000.0 :bandwidth 1.0e6})})]
      (stubs/with-tile-world tiles
        (fn []
          (is (true? (wdata/create-network-impl! wd matrix-vb "contract-net" "pw")))
          (let [net (wdata/get-network-by-ssid wd "contract-net")
                node-a (get @tiles [3 0 0])
                node-b (get @tiles [5 0 0])]
            (is (false? (network-membership/add-node! net node-a-vb "wrong")))
            (is (true? (network-membership/add-node! net node-a-vb "pw")))
            (is (true? (network-membership/add-node! net node-b-vb "pw")))
            (with-redefs [network-config/update-interval-ticks (constantly 1)
                          network-config/buffer-max (constantly 1.0e6)
                          shuffle identity]
              (network-runtime/tick-wireless-net! net))
            (let [ea (.getEnergy node-a)
                  eb (.getEnergy node-b)
                  buf (network-state/get-buffer net)]
              (is (< (Math/abs (- ea eb)) 5.0))
              (is (< (Math/abs (- (+ ea eb buf) 1000.0)) 1.0e-6)))))))))

(deftest energy-converter-simulate-and-bounds-contract-test
  (testing "simulate mode does not mutate state and extraction is bounded"
    (let [state* (atom nil)
          fake-be (Object.)
          in-cap (converter-base/make-energy-capability fake-be "rf-input")
          out-cap (converter-base/make-energy-capability fake-be "rf-output")]
      (with-redefs [platform-be/get-custom-state (fn [_] @state*)
                    platform-be/set-custom-state! (fn [_ v] (reset! state* v))]
        (converter-base/set-energy! fake-be 200.0)
        (is (= 100 (.receiveEnergy in-cap 100 true)))
        (is (= 200.0 (converter-base/get-energy fake-be)))
        (is (= 200 (.extractEnergy out-cap 500 false)))
        (is (= 0.0 (converter-base/get-energy fake-be)))))))

(deftest wireless-network-capacity-and-range-contract-test
  (testing "node admission enforces matrix capacity and range boundaries"
    (let [world-key (test-world :contract-cap-range)
          wd (wdata/create-world-data world-key)
          matrix-vb (vb/create-vmatrix 0 0 0)
          near-a (vb/create-vnode 2 0 0)
          near-b (vb/create-vnode 4 0 0)
          far-node (vb/create-vnode 100 0 0)
          tiles (atom {[0 0 0] (stubs/fake-matrix {:capacity 1 :matrix-range 16.0})
                       [2 0 0] (stubs/mutable-node {:energy 10.0 :max-energy 100.0})
                       [4 0 0] (stubs/mutable-node {:energy 20.0 :max-energy 100.0})
                       [100 0 0] (stubs/mutable-node {:energy 30.0 :max-energy 100.0})})]
      (stubs/with-tile-world tiles
        (fn []
          (is (true? (wdata/create-network-impl! wd matrix-vb "cap-net" "pw")))
          (let [net (wdata/get-network-by-ssid wd "cap-net")]
            (is (true? (network-membership/add-node! net near-a "pw")))
            (is (false? (network-membership/add-node! net near-b "pw")))
            (network-membership/remove-node! net near-a)
            (is (false? (network-membership/add-node! net far-node "pw")))
            (is (true? (network-membership/add-node! net near-b "pw")))))))))
