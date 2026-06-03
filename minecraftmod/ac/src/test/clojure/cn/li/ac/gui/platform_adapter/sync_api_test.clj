(ns cn.li.ac.gui.platform-adapter.sync-api-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.platform-adapter.sync-api :as sync-api]
            [cn.li.mcmod.platform.dispatch :as platform-dispatch]))

(deftest default-broadcast-dispatch-throws-test
  (testing "missing platform version uses :default and throws"
    (let [err (try
                (sync-api/broadcast-gui-state!* nil nil {})
                (catch clojure.lang.ExceptionInfo e
                  e))]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (re-find #"No GUI block-state broadcast" (ex-message err))))))

(deftest assert-gui-broadcast-dispatch-fails-when-unregistered-test
  (let [err (try
              (sync-api/assert-gui-broadcast-dispatch! :guard-test-platform)
              (catch clojure.lang.ExceptionInfo e
                e))]
    (is (instance? clojure.lang.ExceptionInfo err))
    (is (re-find #"defmethod not registered" (ex-message err)))))

(deftest registered-defmethod-dispatches-test
  (defmethod sync-api/broadcast-gui-state!* :guard-test-platform
    [_world _pos sync-data]
    [::broadcasted sync-data])
  (try
    (is (= [::broadcasted {:energy 1}]
           (platform-dispatch/call-with-platform-version
             :guard-test-platform
             #(sync-api/broadcast-gui-state!* nil nil {:energy 1}))))
    (finally
      (remove-method sync-api/broadcast-gui-state!* :guard-test-platform))))
