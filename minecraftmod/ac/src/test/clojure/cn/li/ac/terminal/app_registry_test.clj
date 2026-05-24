(ns cn.li.ac.terminal.app-registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.app-registry :as reg]
            [cn.li.ac.terminal.app-manifest :as manifest]
            [cn.li.ac.terminal.init :as terminal-init]
            [cn.li.ac.terminal.apps.skill-tree :as skill-tree]
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
                    (when (= sym 'cn.li.ac.terminal.apps.skill-tree/init-skill-tree-app!)
                      skill-tree/init-skill-tree-app!))
                  client-bridge/open-skill-tree-screen!
                  (fn [player-uuid learn-context]
                    (swap! launches conj {:player-uuid player-uuid
                                          :learn-context learn-context}))]
      (terminal-init/register-apps!)
      (is (some? (reg/get-app :skill-tree)))
      (is (true? (reg/launch-app :skill-tree :player-1)))
      (is (= [{:player-uuid :player-1
               :learn-context nil}]
             @launches)))))
