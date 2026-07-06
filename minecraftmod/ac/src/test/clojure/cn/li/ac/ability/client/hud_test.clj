(ns cn.li.ac.ability.client.hud-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.ac.ability.client.hud :as hud]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
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
    (runtime-hooks/with-client-ctx {:session-id session-id}
      (let [render-data (hud/build-hud-render-data {:activated false
                                                    :cp {:cur 0.0 :max 1.0}
                                                    :overload {:cur 0.0 :max 1.0 :fine true}
                                                    :active-slots []}
                                                   320 180 {}
                                                   :combat-notice-component component
                                                   :now-ms (System/currentTimeMillis))]
        (is (some? render-data))
        (is (= "Critical Hit x1.6" (get-in render-data [:combat-notice :text])))))))

(deftest cp-bar-render-data-uses-texture-without-solid-bar-color-test
  (let [data (hud/build-cp-bar-render-data
              {:cp {:cur 100.0 :max 100.0}})]
    (is (= "my_mod:textures/guis/cpbar/cp.png" (:fg-texture data)))
    (is (nil? (:bar-color data)))))

(deftest build-skill-slot-render-data-resolves-skill-id-spec-and-icon-test
  (with-redefs [read-model/get-player-contexts-for-player (fn [& _] [])
                skill-query/get-skill-by-controllable (fn [_ _] :railgun)
                skill-registry/get-skill (fn [_] {:name "Railgun"})
                skill-query/get-skill-icon-path (fn [_] "textures/skills/railgun.png")
                cd-data/in-cooldown? (fn [_ _ _] false)
                cd-data/get-remaining (fn [_ _ _] 0)
                dstate/delegate-state-for-slot (fn [_ _ _] {:state :idle :alpha 1.0 :glow-color nil :sin-effect? false})]
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