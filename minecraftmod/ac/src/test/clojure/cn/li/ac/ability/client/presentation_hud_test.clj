(ns cn.li.ac.ability.client.presentation-hud-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.presentation-hud :as hud]))

(deftest skill-slot-projection-preserves-icon-key-and-cooldown
  (let [items (#'hud/skill-slot-items
               [{:x 10 :y 20 :skill-icon "academy:textures/skill.png"
                 :skill-name "Arc" :key-label "R"
                 :in-cooldown true :cooldown-remaining 10
                 :cooldown-total 20 :alpha 1.0}])]
    (is (some #(= :image (:kind %)) items))
    (is (some #(and (= :text (:kind %)) (= "R" (:text %))) items))
    (is (some #(and (= :text (:kind %))
                    (= "0.5s" (:text %))) items))
    (is (some #(and (= :quad (:kind %))
                    (= (unchecked-int 0x4D999999) (:rgba %))) items))))

(deftest crosshair-projection-is-a-marker
  (let [items (#'hud/crosshair-items {:x 100 :y 50 :intensity 0.5})]
    (is (= 2 (count items)))
    (is (every? #(= :quad (:kind %)) items))
    (is (every? #(pos? (:w %)) items))))
(deftest skill-slot-projection-renders-mouse-cap-and-active-glow
  (let [items (#'hud/skill-slot-items
               [{:x 10 :y 20 :skill-icon "academy:textures/skill.png"
                 :key-label :mouse-left :sin-effect? true
                 :glow-color [0x46 0xB3 0xFF 0xFF]}])]
    (is (some #(= "academy:textures/guis/key_hint/mouse_left.png" (:src %)) items))
    (is (not-any? #(and (= :text (:kind %)) (seq (:text %))) items))
    (is (= 4 (count (filter #(= :quad (:kind %)) items))))))
