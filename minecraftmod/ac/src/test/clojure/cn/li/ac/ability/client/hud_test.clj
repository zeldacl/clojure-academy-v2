(ns cn.li.ac.ability.client.hud-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.test.support.hud-render-data :as hud-rd]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(deftest build-hud-render-data-includes-combat-notice-when-inactive-test
  (let [component (combat-notice/create-combat-notice-component)
        session-id :test-session]
    (combat-notice/show-notice!
     component
     session-id
     :teleporter-crit
     {:text "Critical Hit x1.6"
      :duration-ms 2000
      :color [255 226 120]})
    (runtime-hooks/with-client-ctx-fn {:session-id session-id} (fn [] (let [render-data (hud-rd/build-hud-render-data {:activated false
                                                    :cp {:cur 0.0 :max 1.0}
                                                    :overload {:cur 0.0 :max 1.0 :fine true}
                                                    :active-slots []}
                                                   320 180 {}
                                                   :combat-notice-component component
                                                   :now-ms (System/currentTimeMillis))]
        (is (some? render-data))
        (is (= "Critical Hit x1.6" (get-in render-data [:combat-notice :text]))))))))

(deftest cp-bar-render-data-uses-texture-without-solid-bar-color-test
  (let [data (hud/build-cp-bar-render-data
              {:cp {:cur 100.0 :max 100.0}})]
    (is (= "my_mod:textures/guis/cpbar/cp.png" (:fg-texture data)))
    (is (nil? (:bar-color data)))))

(deftest cp-bar-consumption-hint-reports-original-and-predicted-levels-test
  (let [data (hud/build-cp-bar-render-data
              {:cp {:cur 80.0 :max 100.0}
               :consumption-hint 25.0})]
    (is (= 0.8 (:percent data)))
    (is (= 0.55 (:hint-percent data)))))

(deftest numbers-readout-release-fades-for-original-300ms-test
  (let [model {:cp {:cur 80.0 :max 100.0}
               :overload {:cur 20.0 :max 100.0 :overloaded false}}
        halfway (hud/build-numbers-texts-data model false 1000 1150)]
    (is (= 76 (get-in halfway [0 :color :a])))
    (is (nil? (hud/build-numbers-texts-data model false 1000 1300)))))

(deftest overload-render-data-separates-cap-flash-from-recovery-tail-test
  (let [cap (hud/build-overload-bar-render-data
             {:overload {:cur 100.0 :max 100.0 :overloaded true :recovering true}}
             1000)
        tail (hud/build-overload-bar-render-data
              {:overload {:cur 60.0 :max 100.0 :overloaded false :recovering true}}
              1000)]
    (is (true? (:overloaded cap)))
    (is (true? (:recovering cap)))
    (is (= "my_mod:textures/guis/cpbar/back_overload.png" (:bg-texture cap)))
    (is (false? (:overloaded tail)))
    (is (true? (:recovering tail)))
    (is (= "my_mod:textures/guis/cpbar/back_normal.png" (:bg-texture tail)))))

(deftest build-skill-slot-render-data-resolves-skill-id-spec-and-icon-test
  (with-redefs [read-model/get-player-contexts-for-player (fn [& _] [])
                skill-query/get-skill-by-controllable (fn [_ _] :railgun)
                skill-registry/get-skill (fn [_] {:name "Railgun"})
                skill-query/get-skill-icon-path (fn [_] "textures/skills/railgun.png")
                cd-data/in-cooldown? (fn [_ _ _] false)
                cd-data/get-remaining (fn [_ _ _] 0)
                dstate/delegate-state-for-slot (fn [_ _ _ _] {:state :idle :alpha 1.0 :glow-color nil :sin-effect? false})]
    (let [slots (hud-rd/build-skill-slot-render-data
                 {:active-slots [[:electromaster :railgun]]}
                 320
                 180
                 {}
                 "p1")
          first-slot (first slots)]
      (is (= 1 (count slots)))
      (is (= "Railgun" (:skill-name first-slot)))
      (is (= "textures/skills/railgun.png" (:skill-icon first-slot))))))
