(ns cn.li.forge1201.gui.block-sync-broadcast-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.gui.platform-adapter.sync-api :as sync-api]))

(deftest forge-gui-broadcast-defmethod-registered-test
  (require 'cn.li.forge1201.gui.block-sync-broadcast)
  (is (contains? (methods sync-api/broadcast-gui-state!*) :forge-1.20.1)))
