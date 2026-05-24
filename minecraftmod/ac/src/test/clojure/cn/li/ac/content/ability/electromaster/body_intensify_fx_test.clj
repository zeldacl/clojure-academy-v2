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
  (let [handler* (atom nil)
        sounds* (atom [])
        local-fx* (atom 0)]
    (with-redefs [fx-registry/register-fx-channel! (fn [_ handler]
                                                     (reset! handler* handler)
                                                     nil)
                  client-sounds/queue-sound-effect! (fn [payload]
                                                       (swap! sounds* conj payload)
                                                       nil)
                  client-bridge/play-intensify-local-effect! (fn []
                                                                (swap! local-fx* inc)
                                                                nil)]
      (body-intensify-fx/init!)
      (@handler* "ctx-1" :body-intensify/fx-end {:performed? true})
      (@handler* "ctx-2" :body-intensify/fx-end {:performed? false})
      (is (= 1 (count @sounds*)))
      (is (= 1 @local-fx*)))))
