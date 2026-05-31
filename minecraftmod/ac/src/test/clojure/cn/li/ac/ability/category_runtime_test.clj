(ns cn.li.ac.ability.category-runtime-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.model.preset :as preset]
            [cn.li.ac.ability.registry.event :as evt]            [cn.li.ac.ability.service.state-actions :as state-actions]))

(use-fixtures :each test-player/clean-player-states-fixture)

(deftest change-category-clears-presets-and-fires-event-test
  (let [uuid "category-runtime-player"
        events* (atom [])]
    (store/set-player-state!*
      test-player/test-session-id
      uuid
      {:ability-data (assoc (adata/new-ability-data) :category-id :electromaster)
       :preset-data (-> (preset/new-preset-data)
                        (preset/set-active-preset 2)
                        (preset/set-slot 0 0 [:electromaster :arc-gen]))})
    (with-redefs [evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))]
      (let [{:keys [old-category new-category]} (state-actions/change-category! uuid :meltdowner)]
        (is (= :electromaster old-category))
        (is (= :meltdowner new-category))
        (is (= :meltdowner (get-in (store/get-player-state* test-player/test-session-id uuid) [:ability-data :category-id])))
        (is (= {} (get-in (store/get-player-state* test-player/test-session-id uuid) [:preset-data :slots])))
        (is (= 2 (get-in (store/get-player-state* test-player/test-session-id uuid) [:preset-data :active-preset])))
        (is (= [{:event/type evt/EVT-CATEGORY-CHANGE
                 :event/side :both
                 :uuid uuid
                 :old-cat :electromaster
                 :new-cat :meltdowner}]
               @events*))))))


