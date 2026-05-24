(ns cn.li.ac.block.ability-interferer.handlers-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.block.ability-interferer.handlers :as handlers]
            [cn.li.ac.wireless.gui.sync.handler :as sync-handler]
            [cn.li.mcmod.platform.be :as platform-be]))

(deftest handle-set-whitelist-normalizes-and-marks-changed-test
  (let [saved (atom nil)
        changed (atom 0)]
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:whitelist ["Old"]})
                  platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                  platform-be/set-changed! (fn [_] (swap! changed inc))]
      (is (= {:success true :whitelist ["Alice" "Bob"]}
             (#'handlers/handle-set-whitelist {:pos-x 1 :pos-y 2 :pos-z 3
                                               :whitelist [" Bob " "" "Alice" "Bob"]}
                                              :player)))
      (is (= ["Alice" "Bob"] (:whitelist @saved)))
      (is (= 1 @changed)))))

(deftest handle-add-to-whitelist-marks-changed-and-normalizes-test
  (let [saved (atom nil)
        changed (atom 0)]
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:whitelist ["Bob"]})
                  platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                  platform-be/set-changed! (fn [_] (swap! changed inc))]
      (is (= {:success true :whitelist ["Alice" "Bob"]}
             (#'handlers/handle-add-to-whitelist {:pos-x 1 :pos-y 2 :pos-z 3
                                                  :player-name " Alice "}
                                                 :player)))
      (is (= ["Alice" "Bob"] (:whitelist @saved)))
      (is (= 1 @changed)))))

(deftest handle-remove-from-whitelist-marks-changed-test
  (let [saved (atom nil)
        changed (atom 0)]
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)
                  platform-be/get-custom-state (fn [_] {:whitelist ["Alice" "Bob"]})
                  platform-be/set-custom-state! (fn [_ st] (reset! saved st))
                  platform-be/set-changed! (fn [_] (swap! changed inc))]
      (is (= {:success true :whitelist ["Alice"]}
             (#'handlers/handle-remove-from-whitelist {:pos-x 1 :pos-y 2 :pos-z 3
                                                       :player-name "Bob"}
                                                      :player)))
      (is (= ["Alice"] (:whitelist @saved)))
      (is (= 1 @changed)))))

(deftest handle-add-remove-whitelist-guards-invalid-input-test
  (testing "blank add name is rejected"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)]
      (is (= {:success false}
             (#'handlers/handle-add-to-whitelist {:pos-x 1 :pos-y 2 :pos-z 3
                                                  :player-name "   "}
                                                 :player)))))
  (testing "blank remove name is rejected"
    (with-redefs [sync-handler/get-world (fn [_] :world)
                  sync-handler/get-tile-at (fn [_ _] :tile)]
      (is (= {:success false}
             (#'handlers/handle-remove-from-whitelist {:pos-x 1 :pos-y 2 :pos-z 3
                                                       :player-name ""}
                                                      :player))))))
