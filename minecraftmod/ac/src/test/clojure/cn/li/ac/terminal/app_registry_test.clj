(ns cn.li.ac.terminal.app-registry-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.terminal.app-registry :as reg]))

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
