(ns cn.li.ac.content.ability.teleporter.location-teleport-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.teleporter.location-teleport :as loc-tp]
            [cn.li.mcmod.platform.saved-locations :as saved-locations]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(defn- memory-saved-locations
  [store]
  (reify saved-locations/ISavedLocations
    (save-location! [_ player-uuid location-name world-id x y z]
      (swap! store assoc-in [player-uuid location-name]
             {:name location-name
              :world-id world-id
              :x (double x)
              :y (double y)
              :z (double z)})
      true)
    (delete-location! [_ player-uuid location-name]
      (let [exists? (contains? (get @store player-uuid {}) location-name)]
        (swap! store update player-uuid dissoc location-name)
        exists?))
    (get-location [_ player-uuid location-name]
      (get-in @store [player-uuid location-name]))
    (list-locations [_ player-uuid]
      (->> (vals (get @store player-uuid {}))
           (sort-by :name)
           vec))
    (get-location-count [_ player-uuid]
      (count (get @store player-uuid {})))
    (has-location? [_ player-uuid location-name]
      (contains? (get @store player-uuid {}) location-name))))

(defn- teleportation-at
  [pos]
  (reify teleportation/ITeleportation
    (teleport-player! [_ _ _ _ _ _] true)
    (teleport-with-entities! [_ _ _ _ _ _ _] {:success true :teleported-count 1})
    (reset-fall-damage! [_ _] true)
    (get-player-position [_ _] pos)
    (get-player-dimension [_ _] (:world-id pos))))

(deftest save-query-delete-saved-location-roundtrip-test
  (let [store (atom {})
        player-id "player-a"
        current-pos {:world-id "minecraft:overworld" :x 10.0 :y 64.0 :z -2.5}
        long-name "home-base-name-that-is-too-long"]
    (binding [saved-locations/*saved-locations* (memory-saved-locations store)
              teleportation/*teleportation* (teleportation-at current-pos)]
      (testing "saving trims names to the upstream 16 character UI limit"
        (is (= {:success? true :name "home-base-name-t"}
               (loc-tp/save-current-location! player-id long-name)))
        (is (= {:name "home-base-name-t"
                :world-id "minecraft:overworld"
                :x 10.0 :y 64.0 :z -2.5}
               (saved-locations/get-location saved-locations/*saved-locations* player-id "home-base-name-t"))))
      (testing "query includes saved locations with same-dimension perform stats"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 1.0)
                      skill-effects/current-cp (fn [_] 100000.0)]
          (let [result (loc-tp/query-location-teleport player-id)
                loc (first (:locations result))]
            (is (true? (:success? result)))
            (is (= current-pos (:current-pos result)))
            (is (= "home-base-name-t" (:name loc)))
            (is (false? (:cross-dimension? loc)))
            (is (true? (:can-perform? loc))))))
      (testing "delete removes the saved location"
        (is (= {:success? true :name "home-base-name-t"}
               (loc-tp/delete-saved-location! player-id "home-base-name-that-is-too-long")))
        (is (empty? (saved-locations/list-locations saved-locations/*saved-locations* player-id)))))))

(deftest cross-dimension-location-requires-high-exp-test
  (let [store (atom {"player-b" {"nether" {:name "nether"
                                            :world-id "minecraft:the_nether"
                                            :x 0.0 :y 80.0 :z 0.0}}})
        current-pos {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}]
    (binding [saved-locations/*saved-locations* (memory-saved-locations store)
              teleportation/*teleportation* (teleportation-at current-pos)]
      (testing "low exp marks cross-dimension destination unavailable"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 0.8)
                      skill-effects/current-cp (fn [_] 100000.0)]
          (let [loc (first (:locations (loc-tp/query-location-teleport "player-b")))]
            (is (true? (:cross-dimension? loc)))
            (is (false? (:can-perform? loc)))
            (is (= :err-exp (:error loc))))))
      (testing "exp above threshold allows cross-dimension destination when CP is enough"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 0.81)
                      skill-effects/current-cp (fn [_] 100000.0)]
          (let [loc (first (:locations (loc-tp/query-location-teleport "player-b")))]
            (is (true? (:cross-dimension? loc)))
            (is (true? (:can-perform? loc)))
            (is (nil? (:error loc)))))))))
