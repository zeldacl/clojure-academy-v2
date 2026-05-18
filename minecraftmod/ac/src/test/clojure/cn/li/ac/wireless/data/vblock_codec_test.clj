(ns cn.li.ac.wireless.data.vblock-codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.foundation.vblock :as foundation-vb]
            [cn.li.ac.test.support.nbt :as test-nbt]
            [cn.li.ac.wireless.core.vblock :as runtime-vb]
            [cn.li.ac.wireless.data.vblock-codec :as codec]
            [cn.li.mcmod.platform.nbt :as nbt]))

(defn- atom-list
  []
  (let [items (atom [])]
    (reify nbt/INBTList
      (nbt-append! [this el] (swap! items conj el) this)
      (nbt-list-size [_] (count @items))
      (nbt-list-get [_ i] (get @items i))
      (nbt-list-get-compound [_ i]
        (let [v (get @items i)]
          (when (satisfies? nbt/INBTCompound v) v))))))

(deftest vblock-compound-roundtrip-test
  (with-redefs [nbt/create-nbt-compound test-nbt/atom-compound]
    (testing "data codec round-trips pure foundation vblocks"
      (let [source (foundation-vb/vblock 10 20 30 :receiver true)
            decoded (codec/vblock-from-nbt (codec/vblock-to-nbt source))]
        (is (= (:x source) (:x decoded)))
        (is (= (:y source) (:y decoded)))
        (is (= (:z source) (:z decoded)))
        (is (= (:block-type source) (:block-type decoded)))
        (is (= (:ignore-chunk source) (:ignore-chunk decoded)))))
    (testing "runtime facade preserves existing VBlock shape"
      (let [source (runtime-vb/create-vnode 1 2 3)
            decoded (runtime-vb/vblock-from-nbt (runtime-vb/vblock-to-nbt source))]
        (is (= source decoded))))))

(deftest vblock-list-roundtrip-test
  (with-redefs [nbt/create-nbt-compound test-nbt/atom-compound
                nbt/create-nbt-list atom-list]
    (let [source [(runtime-vb/create-vnode 1 2 3)
                  (runtime-vb/create-vmatrix 4 5 6)]
          decoded (codec/nbt-list->vblocks
                    (codec/vblocks-to-nbt-list source)
                    :node
                    false
                    runtime-vb/from-foundation)]
      (is (= source decoded)))))
