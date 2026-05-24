(ns cn.li.mcmod.client.platform-bridge-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- reset-bridge-state! [f]
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-down-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-tick-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-up-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-down-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-tick-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-up-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-skill-tree-screen-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-preset-editor-screen-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-location-teleport-screen-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-terminal-screen-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-simple-gui-fn* (constantly nil))
  (alter-var-root #'cn.li.mcmod.client.platform-bridge/*play-intensify-local-effect-fn* (constantly nil))
  (try
    (f)
    (finally
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-down-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-tick-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*slot-key-up-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-down-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-tick-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*movement-key-up-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-skill-tree-screen-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-preset-editor-screen-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-location-teleport-screen-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-terminal-screen-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*open-simple-gui-fn* (constantly nil))
      (alter-var-root #'cn.li.mcmod.client.platform-bridge/*play-intensify-local-effect-fn* (constantly nil)))))

(use-fixtures :each reset-bridge-state!)

(deftest install-client-bridge-wires-intensify-local-effect-test
  (let [calls* (atom 0)]
    (client-bridge/install-client-bridge!
     {:play-intensify-local-effect (fn []
                                     (swap! calls* inc)
                                     :ok)})
    (is (= :ok (client-bridge/play-intensify-local-effect!)))
    (is (= 1 @calls*))))
