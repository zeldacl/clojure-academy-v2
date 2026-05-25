(ns cn.li.ac.terminal.app-registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.terminal.app-manifest :as manifest]
            [cn.li.ac.terminal.init :as terminal-init]
            [cn.li.ac.terminal.apps.skill-tree :as skill-tree]
            [cn.li.ac.ability.util.uuid :as uuid]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- clean-registry [f]
  (reg/clear-registry!)
  (f)
  (reg/clear-registry!))

(use-fixtures :each clean-registry)

(deftest register-list-launch-test
  (reg/register-app! {:id :about
                      :name "About"
                      :icon "icon.png"
                      :gui-fn 'clojure.core/identity
                      :category :misc})
  (is (= 1 (reg/app-count)))
  (is (= #{:about} (set (reg/list-app-ids))))
  (is (true? (reg/launch-app :about :player))))

(deftest skill-tree-app-registers-through-init-test
  (let [launches (atom [])]
    (with-redefs [manifest/list-app-init-symbols
                  (fn [] '[cn.li.ac.terminal.apps.skill-tree/init-skill-tree-app!])
                  requiring-resolve
                  (fn [sym]
                    (case sym
                      cn.li.ac.terminal.apps.skill-tree/init-skill-tree-app! skill-tree/init-skill-tree-app!
                      cn.li.ac.terminal.apps.skill-tree/open-skill-tree-gui skill-tree/open-skill-tree-gui
                      nil))
                  uuid/player-uuid (fn [_] "player-uuid-1")
                  entity/player-get-name (fn [_] "Player One")
                  client-bridge/open-screen!
                  (fn [screen-id payload]
                    (swap! launches conj {:screen-id screen-id
                                          :payload payload}))]
      (terminal-init/register-apps!)
      (is (some? (reg/get-app :skill-tree)))
      (is (true? (reg/launch-app :skill-tree :player-1)))
      (is (= [{:screen-id :ac/skill-tree
           :payload {:player-uuid "player-uuid-1"
                         :learn-context nil}}]
             @launches)))))
