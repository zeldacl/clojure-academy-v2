(ns cn.li.ac.block.wireless-node-place-index-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.block.wireless-node.logic :as wnode]
            [cn.li.ac.block.wireless-node.owner :as node-owner]
            [cn.li.ac.test.support.wireless-stubs :as stubs]
            [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.core.spatial-index :as si]
            [cn.li.ac.wireless.data.world :as world]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as ppos]))

(use-fixtures
  :each
  (fn [f]
    (world-registry/call-with-world-registry-runtime
      (world-registry/create-world-registry-runtime)
      f)))

(deftest node-place-registers-discoverable-spatial-index-test
  (let [world-id {:server-session-id :test-session
                  :world-id :w-node-index}
        tiles (atom {[0 0 0] (stubs/mutable-node {})})
        handler (wnode/handle-node-place :basic)
        expected-vb (vb/create-vnode 0 0 0)]
    (stubs/with-tile-world tiles
      (fn []
        (let [pos (ppos/create-block-pos 0 0 0)]
        (with-redefs [platform-be/get-custom-state (fn [_] nil)
                      platform-be/set-custom-state! (fn [_ _] nil)
                      node-owner/player-name (fn [_] "tester")]
          (handler {:player :player :world world-id :pos pos})
          (let [wd (world/get-world-data world-id)]
            (is (some? wd) "placing a node should ensure world-data exists")
            (is (contains? (si/vblocks-in-index (world-registry/spatial-index wd)
                                                (si/nearby-chunk-keys 0 0 0 1))
                           expected-vb)))))))))

