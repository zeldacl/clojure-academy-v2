(ns cn.li.ac.ability.client.hud-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.service.registry :as skill]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.ac.ability.service.dispatcher :as ctx]))

(deftest cp-bar-render-data-uses-texture-without-solid-bar-color-test
  (let [data (hud/build-cp-bar-render-data
              {:cp {:cur 100.0 :max 100.0}})]
    (is (= "my_mod:textures/guis/cpbar/cp.png" (:fg-texture data)))
    (is (nil? (:bar-color data)))))

(deftest build-skill-slot-render-data-resolves-skill-id-spec-and-icon-test
  (with-redefs [ctx/get-all-contexts-for-player (fn [_] {})
                skill/get-skill-by-controllable (fn [_ _] :railgun)
                skill/get-skill (fn [_] {:name "Railgun"})
                skill/get-skill-icon-path (fn [_] "textures/skills/railgun.png")
                cd-data/in-cooldown? (fn [_ _ _] false)
                cd-data/get-remaining (fn [_ _ _] 0)
                dstate/delegate-state-for-slot (fn [_ _] {:state :idle :alpha 1.0 :glow-color nil :sin-effect? false})]
    (let [slots (hud/build-skill-slot-render-data
                 {:active-slots [[:electromaster :railgun]]}
                 320
                 180
                 {}
                 "p1")
          first-slot (first slots)]
      (is (= 1 (count slots)))
      (is (= "Railgun" (:skill-name first-slot)))
      (is (= "textures/skills/railgun.png" (:skill-icon first-slot))))))
