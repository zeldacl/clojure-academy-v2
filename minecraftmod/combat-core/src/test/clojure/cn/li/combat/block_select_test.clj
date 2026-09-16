(ns cn.li.combat.block-select-test
  "platform/block-select! selects blocks for the two shapes content asks for.

   It understood only :line. The surface lib's break-area and random-break
   pass {:type :sphere :center ... :radius ...}, so they selected nothing
   and the two skills that call them destroyed no terrain at all -- a
   silent failure, because an empty list is a perfectly good list and the
   `each` loop over it simply never ran.

   :max-hardness was dropped the same way: break_area.edn's own comment
   claimed the cap was 'pre-filtered host-side' and nothing filtered it. It
   was spelled :projection, which on :target/entities means a FIELD
   WHITELIST -- one name, two meanings, neither implemented here."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.platform.block-manipulation :as blocks]
            [cn.li.combat.platform :as platform]))

(defn- with-world
  "Run f against a stub world where every integer position holds a block of
   `hardness`, so selection geometry is the only variable."
  [hardness f]
  (with-redefs [blocks/available? (constantly true)
                blocks/get-block (fn [_w x y z] (str "block:" x "," y "," z))
                blocks/get-block-hardness (fn [_w _x _y _z] hardness)
                blocks/can-break-block? (constantly true)
                blocks/requires-high-tier-tool? (constantly false)]
    (f)))

(defn- select [request]
  (platform/block-select! (merge {:owner "p" :world-id "w"} request) nil))

(deftest sphere-shape-selects-blocks-test
  (with-world
    1.0
    (fn []
      (testing "a sphere selects the blocks inside its radius"
        (let [blocks (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 1.0}})]
          ;; radius 1 around the origin: the centre plus its six face
          ;; neighbours are the integer points within distance 1.
          (is (= 7 (count blocks)))
          (is (every? #(= 1.0 (:hardness %)) blocks))
          (is (contains? (set (map :position blocks)) [0.0 0.0 0.0]))
          (is (contains? (set (map :position blocks)) [1.0 0.0 0.0]))
          ;; A corner is sqrt(3) away and must not be included, or the
          ;; "sphere" is really a cube.
          (is (not (contains? (set (map :position blocks)) [1.0 1.0 1.0])))))

      (testing "a bigger radius selects more, and :limit still bounds it"
        (is (< 7 (count (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 2.0}}))))
        (is (= 3 (count (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 2.0}
                                 :limit 3})))))

      (testing "the radius bound is enforced rather than scanning a huge cube"
        (is (= [] (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 64.0}})))))))

(deftest max-hardness-caps-the-selection-test
  (testing "blocks at or under the cap are kept"
    (with-world 2.0
      (fn []
        (is (seq (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 1.0}
                          :max-hardness 2.0}))))))
  (testing "blocks over the cap are dropped -- the whole point of the param"
    (with-world 50.0
      (fn []
        (is (= [] (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 1.0}
                           :max-hardness 2.0}))))))
  (testing "no cap means no filtering, so omitting it cannot silently empty a selection"
    (with-world 50.0
      (fn []
        (is (seq (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 1.0}})))))))

(deftest unbreakable-blocks-are-not-selected-test
  ;; Upstream marks unbreakable blocks with a negative hardness. Selecting
  ;; them would put bedrock in a break list.
  (with-world -1.0
    (fn []
      (is (= [] (select {:shape {:type :sphere :center [0.0 0.0 0.0] :radius 1.0}}))))))

(deftest line-shape-still-works-test
  ;; The arm that already existed, kept honest while the sphere arm was
  ;; added beside it.
  (with-redefs [blocks/available? (constantly true)
                blocks/find-blocks-in-line
                (fn [_w _sx _sy _sz _dx _dy _dz _len]
                  [{:x 1 :y 2 :z 3 :hardness 4.0 :block-id "minecraft:stone"}])
                blocks/can-break-block? (constantly true)
                blocks/requires-high-tier-tool? (constantly false)]
    (let [blocks (select {:shape {:type :line :start [0.0 0.0 0.0]
                                  :direction [1.0 0.0 0.0] :length 8.0}})]
      (is (= 1 (count blocks)))
      (is (= [1.0 2.0 3.0] (:position (first blocks))))
      (is (= "minecraft:stone" (:block-id (first blocks)))))
    (testing "and the cap applies to it too"
      (is (= [] (select {:shape {:type :line :start [0.0 0.0 0.0]
                                 :direction [1.0 0.0 0.0] :length 8.0}
                         :max-hardness 1.0}))))))
