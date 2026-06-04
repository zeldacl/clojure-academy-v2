(ns cn.li.fabric1201.gui.block-sync-broadcast-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.mcmod.gui.sync-api :as sync-api]))

(deftest fabric-gui-broadcast-defmethod-registered-test
  (require 'cn.li.fabric1201.gui.block-sync-broadcast)
  (is (contains? (methods sync-api/broadcast-gui-state!*) :fabric-1.20.1)))
