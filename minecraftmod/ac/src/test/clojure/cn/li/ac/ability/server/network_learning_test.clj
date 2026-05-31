(ns cn.li.ac.ability.server.network-learning-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
            [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.test.support.player-state :as test-player]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.server.network :as network]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.service.command-runtime :as command-rt]            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.ac.ability.util.uuid :as uuid]))

(use-fixtures :each test-player/clean-player-states-fixture)

(deftype StubPlayer []
  entity/IEntityOps
  (entity-distance-to-sqr [_ _ _ _] 0.0)
  (player-get-level [_] nil)
  (player-creative? [_] false)
  (player-spectator? [_] false)
  (player-get-name [_] "stub-player")
  (player-get-uuid [_] "stub-player")
  (player-get-main-hand-item-id [_] nil)
  (player-get-main-hand-item-count [_] 0)
  (player-main-hand-placeable-block? [_] false)
  (player-place-main-hand-block-at-hit! [_ _ _ _ _ _]
    {:placed? false :fallback-drop? false :pos nil :face nil})
  (player-consume-main-hand-item! [_ _] false)
  (player-drop-main-hand-item-at! [_ _ _ _ _] false)
  (player-count-item-by-id [_ _] 0)
  (player-consume-item-by-id! [_ _ _] false)
  (player-give-item-stack! [_ _] false)
  (player-spawn-entity-by-id! [_ _ _] false)
  (player-raytrace-block [_ _ _] nil)
  (player-get-container-menu [_] nil)
  (inventory-get-player [_] nil)
  (menu-get-container-id [_] 0))

(deftest handle-learn-skill-request-delegates-to-command-runtime-test
  (let [calls* (atom [])
        player (StubPlayer.)]
    (store/set-player-state!* test-player/test-session-id "p1" {:ability-data (assoc (adata/new-ability-data) :level 3)})
    (with-redefs [uuid/player-uuid (constantly "p1")
                  skill/get-skill (fn [skill-id]
                                    {:id skill-id :level 1 :developer-type :normal :prerequisites []})
                  learning-rules/check-all-conditions (fn [skill-spec ability-data player-level developer-type]
                                                        (swap! calls* conj [:conditions (:id skill-spec) player-level developer-type ability-data])
                                                        {:pass? true :failures []})
                  command-rt/run-command-in-session! (fn [session-id uuid {:keys [skill-id]}]
                                            (swap! calls* conj [:learn session-id uuid skill-id])
                                            {:state {:ability-data (adata/learn-skill (get-in (store/get-player-state* session-id uuid) [:ability-data]) skill-id)}
                                             :events [{:event/type :ability/skill-learn
                                                       :uuid uuid
                                                       :skill-id skill-id}]
                                             :effects []})]
                                          (#'network/handle-learn-skill-request {:skill-id :arc-gen} player)
      (is (= [[:conditions :arc-gen 3 :normal (get-in (store/get-player-state* test-player/test-session-id "p1") [:ability-data])]
              [:learn test-player/test-session-id "p1" :arc-gen]]
             @calls*)))))


