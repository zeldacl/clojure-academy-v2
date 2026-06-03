(ns cn.li.ac.content.ability.electromaster.body-intensify-fx-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.content.ability.electromaster.body-intensify-fx :as body-intensify-fx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(deftest init-registers-body-intensify-fx-channel-test
  (let [registered* (atom nil)]
    (with-redefs [fx-registry/register-fx-channel! (fn [channel handler]
                                                     (reset! registered* {:channel channel
                                                                          :handler handler})
                                                     nil)]
      (body-intensify-fx/init!)
      (is (= :body-intensify/fx-end (:channel @registered*)))
      (is (fn? (:handler @registered*))))))

(deftest fx-handler-plays-local-effect-when-performed-test
  (let [handlers* (atom {})
        sounds* (atom [])
        local-fx* (atom [])]
    (with-redefs [fx-registry/register-fx-channel! (fn [topic handler]
                                                     (swap! handlers* assoc topic handler)
                                                     nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)
                  client-bridge/run-client-effect! (fn [effect-key payload]
                                                     (swap! local-fx* conj [effect-key payload])
                                                     nil)]
      (body-intensify-fx/init!)
      ((get @handlers* :body-intensify/fx-end) "ctx-1" :body-intensify/fx-end {:performed? true})
      ((get @handlers* :body-intensify/fx-end) "ctx-2" :body-intensify/fx-end {:performed? false})
      (is (= 1 (count @sounds*)))
      (is (= [[:mcmod/spawn-local-scripted-effect {:effect-id "intensify_effect"
                       :ctx-id "ctx-1"
                       :channel :body-intensify/fx-end}]]
             @local-fx*)))))
