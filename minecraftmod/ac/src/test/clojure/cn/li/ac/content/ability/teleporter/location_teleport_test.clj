(ns cn.li.ac.content.ability.teleporter.location-teleport-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.config :as ability-config]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.content.ability.teleporter.location-teleport :as loc-tp]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.ac.ability.messages :as catalog]
            [cn.li.mcmod.network.server :as net-srv]
            [cn.li.mcmod.platform.named-position-store :as position-store]
            [cn.li.mcmod.platform.teleportation :as teleportation]))

(defn- memory-position-store
  [store]
  (reify position-store/INamedPositionStore
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
              (teleportation/available?) (teleportation-at current-pos)]
      (testing "saving trims names to the upstream 16 character UI limit"
        (is (= {:success? true :name "home-base-name-t"}
               (loc-tp/save-current-location! player-id long-name)))
        (is (= {:name "home-base-name-t"
                :world-id "minecraft:overworld"
                :x 10.0 :y 64.0 :z -2.5}
               (position-store/get-location* player-id "home-base-name-t"))))
      (testing "query includes saved locations with same-dimension perform stats"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 1.0)
                      skill-effects/current-cp (fn [_] 100000.0)
                      skill-effects/get-player-state (fn [_]
                                                       {:resource-data {:activated true
                                                                        :overload-fine true
                                                                        :interferences #{}
                                                                        :cur-cp 100000.0}})]
          (let [result (loc-tp/query-location-teleport player-id)
                loc (first (:locations result))]
            (is (true? (:success? result)))
            (is (= current-pos (:current-pos result)))
            (is (= 16 (get-in result [:limits :max-saved-locations])))
            (is (= "home-base-name-t" (:name loc)))
            (is (false? (:cross-dimension? loc)))
            (is (true? (:can-perform? loc))))))
      (testing "delete removes the saved location"
        (is (= {:success? true :name "home-base-name-t"}
               (loc-tp/delete-saved-location! player-id "home-base-name-that-is-too-long")))
        (is (empty? (position-store/list-locations* player-id)))))))

      (deftest save-current-location-enforces-ac-max-location-policy-test
        (let [store (atom {"player-limit" {"home" {:name "home"
                       :world-id "minecraft:overworld"
                       :x 0.0 :y 64.0 :z 0.0}}})
         current-pos {:world-id "minecraft:overworld" :x 10.0 :y 65.0 :z 2.0}]
          (teleportation/available?) (teleportation-at current-pos)]
            (with-redefs [ability-config/max-saved-locations (fn [] 1)]
         (testing "new names are rejected when the AC configured limit is reached"
           (is (= {:success? false
              :error :location-limit-reached
              :max-locations 1}
             (loc-tp/save-current-location! "player-limit" "mine")))
           (is (nil? (position-store/get-location*
                      "player-limit" "mine"))))
         (testing "overwriting an existing saved location is still allowed"
           (is (= {:success? true :name "home"}
             (loc-tp/save-current-location! "player-limit" "home")))
           (is (= current-pos
             (select-keys (position-store/get-location*
                          "player-limit" "home")
                [:world-id :x :y :z]))))))))

(deftest cross-dimension-location-requires-high-exp-test
  (let [store (atom {"player-b" {"nether" {:name "nether"
                                            :world-id "minecraft:the_nether"
                                            :x 0.0 :y 80.0 :z 0.0}}})
        current-pos {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}]
              (teleportation/available?) (teleportation-at current-pos)]
      (testing "low exp marks cross-dimension destination unavailable"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 0.8)
                      skill-effects/current-cp (fn [_] 100000.0)
                      skill-effects/get-player-state (fn [_]
                                                       {:resource-data {:activated true
                                                                        :overload-fine true
                                                                        :interferences #{}
                                                                        :cur-cp 100000.0}})]
          (let [loc (first (:locations (loc-tp/query-location-teleport "player-b")))]
            (is (true? (:cross-dimension? loc)))
            (is (false? (:can-perform? loc)))
            (is (= :err-exp (:error loc))))))
      (testing "exp above threshold allows cross-dimension destination when CP is enough"
        (with-redefs [skill-effects/skill-exp (fn [_ _] 0.81)
                      skill-effects/current-cp (fn [_] 100000.0)
                      skill-effects/get-player-state (fn [_]
                                                       {:resource-data {:activated true
                                                                        :overload-fine true
                                                                        :interferences #{}
                                                                        :cur-cp 100000.0}})]
          (let [loc (first (:locations (loc-tp/query-location-teleport "player-b")))]
            (is (true? (:cross-dimension? loc)))
            (is (true? (:can-perform? loc)))
            (is (nil? (:error loc)))))))))

          (deftest query-uses-resource-model-usability-not-cp-only-test
            (let [store (atom {"player-c" {"home" {:name "home"
                        :world-id "minecraft:overworld"
                        :x 3.0 :y 65.0 :z 7.0}}})
             current-pos {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0}]
              (teleportation/available?) (teleportation-at current-pos)]
                (with-redefs [skill-effects/skill-exp (fn [_ _] 1.0)
                    skill-effects/get-player-state (fn [_]
                            {:resource-data {:activated false
                              :overload-fine true
                              :interferences #{}
                              :cur-cp 100000.0}})]
             (let [result (loc-tp/query-location-teleport "player-c")
              loc (first (:locations result))]
               (is (true? (:success? result)))
               (is (= 16 (get-in result [:limits :max-location-name-length])))
               (is (= 0.8 (get-in result [:limits :cross-dimension-exp-threshold])))
               (is (false? (:can-perform? loc)))
               (is (= :err-cp (:error loc))))))))

          (deftest init-registers-action-snapshot-response-contract-test
            (let [handlers* (atom {})
             empty-snapshot {:success? true
                   :exp 0.0
                   :limits {:cross-dimension-exp-threshold 0.8
                       :max-location-name-length 16}
                   :current-pos nil
                   :locations []}]
              (with-redefs [net-srv/register-handler (fn [msg-id f]
                         (swap! handlers* assoc msg-id f)
                         nil)
                  uuid/player-uuid (fn [player] player)
                  loc-tp/query-location-teleport (fn [_] empty-snapshot)
                  loc-tp/save-current-location! (fn [_ _] {:success? false :error :save-failed})
                  loc-tp/delete-saved-location! (fn [_ _] {:success? true :name "ok"})
                  loc-tp/perform-location-teleport! (fn [_ _] {:success? false :error :err-cp :cp-cost 99.0})]
                (loc-tp/init!)
                (let [query-fn (get @handlers* catalog/MSG-REQ-SAVED-POS-QUERY)
                 add-fn (get @handlers* catalog/MSG-REQ-SAVED-POS-ADD)
                 remove-fn (get @handlers* catalog/MSG-REQ-SAVED-POS-REMOVE)
                 perform-fn (get @handlers* catalog/MSG-REQ-SAVED-POS-PERFORM)
                 query-resp (query-fn {} "player-z")
                 add-resp (add-fn {:name "n"} "player-z")
                 remove-resp (remove-fn {:name "n"} "player-z")
                 perform-resp (perform-fn {:name "n"} "player-z")]
             (testing "query response contains action + snapshot"
               (is (= {:success? true :error nil :op :query}
                 (:action query-resp)))
               (is (= empty-snapshot (:snapshot query-resp))))
             (testing "mutating actions do not get success overwritten by snapshot"
               (is (= {:success? false :error :save-failed :op :add}
                 (:action add-resp)))
               (is (= {:success? true :name "ok" :op :remove}
                 (:action remove-resp)))
               (is (= {:success? false :error :err-cp :cp-cost 99.0 :op :perform}
                 (:action perform-resp))))))))
