(ns cn.li.ac.content.ability.electromaster.body-intensify-fx-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.content.ability.electromaster.body-intensify-fx :as body-intensify-fx]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(deftest fx-handler-plays-local-effect-when-performed-test
  (let [handler* (atom nil)
        sounds* (atom [])
        local-fx* (atom [])]
    (with-redefs [fx-registry/register-fx-channel! (fn [_ handler]
                                                     (reset! handler* handler)
                                                     nil)
                  client-sounds/queue-current-sound-effect! (fn [payload]
                                                               (swap! sounds* conj payload)
                                                               nil)
                  client-bridge/run-client-effect! (fn [effect-key payload]
                                                     (swap! local-fx* conj [effect-key payload])
                                                     nil)]
      (body-intensify-fx/init!)
      (@handler* "ctx-1" :body-intensify/fx-end {:performed? true})
      (@handler* "ctx-2" :body-intensify/fx-end {:performed? false})
      (is (= 1 (count @sounds*)))
      (is (= [[:mcmod/spawn-local-scripted-effect {:effect-id "intensify_effect"
                       :ctx-id "ctx-1"
                       :channel :body-intensify/fx-end}]]
             @local-fx*)))))
