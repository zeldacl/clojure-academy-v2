(ns cn.li.ac.content.ability.fx-channel-registration-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]))

(def ^:private fx-init-symbols
  '[cn.li.ac.content.ability.meltdowner.mine-ray-fx/init!
    cn.li.ac.content.ability.teleporter.flesh-ripping-fx/init!
    cn.li.ac.content.ability.teleporter.location-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.shift-teleport-fx/init!
    cn.li.ac.content.ability.teleporter.teleporter-crit-fx/init!
    cn.li.ac.content.ability.teleporter.threatening-teleport-fx/init!
    cn.li.ac.content.ability.vecmanip.blood-retrograde-fx/init!
    cn.li.ac.content.ability.vecmanip.directed-blastwave-fx/init!
    cn.li.ac.content.ability.vecmanip.directed-shock-fx/init!
    cn.li.ac.content.ability.vecmanip.groundshock-fx/init!
    cn.li.ac.content.ability.vecmanip.vec-accel-fx/init!
    cn.li.ac.content.ability.vecmanip.vec-deviation-fx/init!])

(deftest all-content-ability-fx-inits-register-via-fx-spec-test
  (is (= 13 (count fx-init-symbols)))
  (doseq [init-sym fx-init-symbols]
    (let [register-calls* (atom [])]
      (with-redefs [fx-spec/register! (fn [spec] (swap! register-calls* conj spec))]
        ((requiring-resolve init-sym)))
      (is (< 0 (count @register-calls*))))))
