(ns cn.li.ac.ability.learning-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.model.ability :as adata]
            [cn.li.ac.ability.registry.event :as evt]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.server.service.learning-runtime :as learning-rt]))

(deftest learn-skill-bang-updates-player-state-and-fires-event-test
  (let [state*  (atom {:ability-data (adata/new-ability-data)})
        events* (atom [])]
    (with-redefs [evt/make-skill-learn-event (fn [uuid skill-id]
                                               {:event/type :ability/skill-learn
                                                :uuid uuid
                                                :skill-id skill-id})
                  ps/get-player-state (fn [_] @state*)
                  ps/update-ability-data! (fn [_ f]
                                            (swap! state* update :ability-data f))
                  evt/fire-ability-event! (fn [event]
                                            (swap! events* conj event))]
      (let [{:keys [data event]} (learning-rt/learn-skill! "p1" :s1)]
        (is (contains? (:learned-skills (:ability-data @state*)) :s1))
        (is (= (:ability-data @state*) data))
        (is (= {:event/type :ability/skill-learn :uuid "p1" :skill-id :s1} event))
        (is (= [event] @events*))))))
