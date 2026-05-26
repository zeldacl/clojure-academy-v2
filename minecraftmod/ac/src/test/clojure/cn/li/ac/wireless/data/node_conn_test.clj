(ns cn.li.ac.wireless.data.node-conn-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.data.world :as wdata]))

(defn- test-world
  [world-id]
  {:server-session-id :test-session
   :world-id world-id})

(deftest create-node-conn-defaults-test
  (let [wd {:world :w :node-lookup (atom {})}
        node {:x 1 :y 2 :z 3}
        conn (node-conn/create-node-conn wd node)]
    (is (= node (:node conn)))
    (is (= 0 (node-conn/get-load conn)))
    (is (false? (node-conn/is-disposed? conn)))))

(deftest add-device-capacity-and-range-gates-test
  (let [wd (wdata/create-world-data (test-world :wc-gates))
        node-t (stubs/mutable-node {:capacity 1 :range 10.0})
        g1 (stubs/generator-stub {})
        g2 (stubs/generator-stub {})
        far (stubs/generator-stub {})
        tiles (atom {[0 0 0] node-t [1 0 0] g1 [2 0 0] g2 [100 0 0] far})
        node-vb (vb/create-vnode 0 0 0)
        conn (node-conn/create-node-conn wd node-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-generator! conn (vb/->VBlock 1 0 0 :generator true))))
        (is (false? (node-conn/add-generator! conn (vb/->VBlock 2 0 0 :generator true))))
        (is (false? (node-conn/add-generator! conn (vb/->VBlock 100 0 0 :generator true))))))))

(deftest tick-pulls-from-generator-test
  (let [wd (wdata/create-world-data (test-world :wc-gen))
        node-t (stubs/mutable-node {:energy 0.0 :max-energy 1000.0 :bandwidth 500.0})
        gen (stubs/generator-stub {:provided-fn identity})
        tiles (atom {[0 0 0] node-t [8 0 0] gen})
        node-vb (vb/create-vnode 0 0 0)
        gen-vb (vb/->VBlock 8 0 0 :generator true)
        conn (node-conn/create-node-conn wd node-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-generator! conn gen-vb)))
        (node-conn/tick-node-conn! conn)
        (is (pos? (.getEnergy node-t)))))))

(deftest tick-pushes-to-receiver-test
  (let [wd (wdata/create-world-data (test-world :wc-rec))
        node-t (stubs/mutable-node {:energy 800.0 :max-energy 1000.0 :bandwidth 500.0})
        rec (stubs/receiver-stub {:required 100.0 :leftover-fn (constantly 0.0)})
        tiles (atom {[0 0 0] node-t [4 0 0] rec})
        node-vb (vb/create-vnode 0 0 0)
        rec-vb (vb/->VBlock 4 0 0 :receiver true)
        conn (node-conn/create-node-conn wd node-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-receiver! conn rec-vb)))
        (node-conn/tick-node-conn! conn)
        (is (< (.getEnergy node-t) 800.0))))))

(deftest generator-overflow-uses-required-cap-test
  (let [wd (wdata/create-world-data (test-world :wc-ov))
        node-t (stubs/mutable-node {:energy 0.0 :max-energy 1000.0 :bandwidth 500.0})
        gen (stubs/generator-stub {:provided-fn (fn [req] (* 2.0 req))})
        tiles (atom {[0 0 0] node-t [2 0 0] gen})
        node-vb (vb/create-vnode 0 0 0)
        gen-vb (vb/->VBlock 2 0 0 :generator true)
        conn (node-conn/create-node-conn wd node-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-generator! conn gen-vb)))
        (node-conn/tick-node-conn! conn)
        (is (<= (.getEnergy node-t) 500.0))))))

(deftest remove-and-transfer-device-cleans-lookup-immediately-test
  (let [wd (wdata/create-world-data (test-world :wc-remove))
        node-a (stubs/mutable-node {:capacity 4 :range 20.0})
        node-b (stubs/mutable-node {:capacity 4 :range 20.0})
        gen (stubs/generator-stub {})
        rec (stubs/receiver-stub {})
        tiles (atom {[0 0 0] node-a [10 0 0] node-b [2 0 0] gen [3 0 0] rec})
        node-a-vb (vb/create-vnode 0 0 0)
        node-b-vb (vb/create-vnode 10 0 0)
        gen-vb (vb/->VBlock 2 0 0 :generator true)
        rec-vb (vb/->VBlock 3 0 0 :receiver true)
        conn-a (node-conn/create-node-conn wd node-a-vb)
        conn-b (node-conn/create-node-conn wd node-b-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-generator! conn-a gen-vb)))
        (is (true? (node-conn/add-receiver! conn-a rec-vb)))
        (is (identical? conn-a (get (world-registry/node-lookup wd) gen-vb)))
        (is (true? (node-conn/remove-receiver! conn-a rec-vb)))
        (is (empty? (node-conn/get-receivers conn-a)))
        (is (nil? (get (world-registry/node-lookup wd) rec-vb)))
        (is (true? (node-conn/add-generator! conn-b gen-vb)))
        (is (empty? (node-conn/get-generators conn-a)))
        (is (= [gen-vb] (node-conn/get-generators conn-b)))
        (is (identical? conn-b (get (world-registry/node-lookup wd) gen-vb)))))))

(deftest node-conn-nbt-round-trip-test
  (let [wd (wdata/create-world-data (test-world :wc-nbt))
        node-t (stubs/mutable-node {})
        gen (stubs/generator-stub {})
        tiles (atom {[5 0 0] node-t [9 0 0] gen})
        node-vb (vb/create-vnode 5 0 0)
        gen-vb (vb/->VBlock 9 0 0 :generator true)
        conn (node-conn/create-node-conn wd node-vb)]
    (stubs/with-tile-world tiles
      (fn []
        (is (true? (node-conn/add-generator! conn gen-vb)))
        (let [nbt (node-conn/node-connection-to-nbt conn)
              conn2 (node-conn/node-connection-from-nbt wd nbt)]
          (is (= 1 (count (node-conn/get-generators conn2))))
          (is (= 0 (count (node-conn/get-receivers conn2)))))))))
