(ns cn.li.ac.content.ability.fx-channel-registration-contract-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]))

(def ^:private fx-init-symbols
  '[cn.li.ac.content.ability.meltdowner.mine-ray-fx/init!
    cn.li.ac.content.ability.teleporter.location-teleport-fx/init!])

(deftest all-content-ability-fx-inits-register-via-fx-spec-test
  (is (= 2 (count fx-init-symbols)))
  (doseq [init-sym fx-init-symbols]
    (let [register-calls* (atom [])]
      (with-redefs [fx-spec/register! (fn [spec] (swap! register-calls* conj spec))]
        ((requiring-resolve init-sym)))
      (is (< 0 (count @register-calls*))))))
