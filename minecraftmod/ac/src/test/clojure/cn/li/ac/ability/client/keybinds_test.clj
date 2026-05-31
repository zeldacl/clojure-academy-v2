(ns cn.li.ac.ability.client.keybinds-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.api :as client-api]
            [cn.li.ac.ability.client.keybinds :as keybinds]            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.test.support.player-state :as ps-fix]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(defn- reset-fixture [f]
  (ps-fix/with-test-player-state-owner
    (fn []
      (keybinds/reset-client-keybind-state-for-test!)
      (keybinds/reset-keybind-registries-for-test!)
      (store/reset-store!)
      (try
        (f)
        (finally
          (keybinds/reset-client-keybind-state-for-test!)
          (keybinds/reset-keybind-registries-for-test!)
          (store/reset-store!))))))

(use-fixtures :each reset-fixture)

(defn- activated-state []
  (assoc-in (store/fresh-player-state) [:resource-data :activated] true))

(deftest key-state-isolated-by-client-owner-test
  (let [opened (atom [])]
    (with-redefs [client-bridge/open-screen! (fn [screen-key payload]
                                               (swap! opened conj [screen-key payload]))]
      (binding [keybinds/*client-session-id* :session-a
                keybinds/*get-player-uuid-fn* (constantly "player-a")]
        (keybinds/on-gui-key-event :skill-tree true))
      (binding [keybinds/*client-session-id* :session-a
                keybinds/*get-player-uuid-fn* (constantly "player-b")]
        (keybinds/on-gui-key-event :preset-editor true)))
    (is (= [[:ac/skill-tree {:player-uuid "player-a"}]
            [:ac/preset-editor {:player-uuid "player-b"}]]
           @opened))
    (is (= true (get-in (keybinds/key-state-snapshot {:client-session-id :session-a
                                                       :player-uuid "player-a"})
                        [:gui-keys :skill-tree])))
    (is (= false (get-in (keybinds/key-state-snapshot {:client-session-id :session-a
                                                        :player-uuid "player-a"})
                         [:gui-keys :preset-editor])))
    (is (= true (get-in (keybinds/key-state-snapshot {:client-session-id :session-a
                                                       :player-uuid "player-b"})
                        [:gui-keys :preset-editor])))))

(deftest clear-client-keybind-state-clears-only-owner-test
  (with-redefs [client-bridge/open-screen! (fn [_ _] nil)]
    (binding [keybinds/*client-session-id* :session-a
              keybinds/*get-player-uuid-fn* (constantly "player-a")]
      (keybinds/on-gui-key-event :skill-tree true))
    (binding [keybinds/*client-session-id* :session-a
              keybinds/*get-player-uuid-fn* (constantly "player-b")]
      (keybinds/on-gui-key-event :skill-tree true)))
  (keybinds/clear-client-keybind-state! {:client-session-id :session-a
                                          :player-uuid "player-a"})
  (is (= false (get-in (keybinds/key-state-snapshot {:client-session-id :session-a
                                                     :player-uuid "player-a"})
                       [:gui-keys :skill-tree])))
  (is (= true (get-in (keybinds/key-state-snapshot {:client-session-id :session-a
                                                    :player-uuid "player-b"})
                      [:gui-keys :skill-tree]))))

(deftest client-keybind-owner-requires-explicit-session-and-player-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Client keybind owner requires :client-session-id"
                        (keybinds/key-state-snapshot "player-a")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Client keybind owner requires :player-uuid"
                        (keybinds/key-state-snapshot {:client-session-id :session-a}))))

(deftest preset-switch-state-isolated-by-player-test
  (store/reset-store!)
  (store/set-player-state!* :session-a "player-a" (activated-state))
  (store/set-player-state!* :session-a "player-b" (activated-state))
  (let [requests (atom [])]
    (with-redefs [client-api/req-switch-preset! (fn [preset-idx callback]
                                                  (swap! requests conj preset-idx)
                                                  (when callback (callback {:success true})))]
      (binding [keybinds/*client-session-id* :session-a]
        (keybinds/switch-preset! "player-a")
        (keybinds/switch-preset! "player-a")
        (keybinds/switch-preset! "player-b"))))
  (is (= 2 (:current-preset (keybinds/get-preset-switch-state {:client-session-id :session-a
                                                               :player-uuid "player-a"}))))
  (is (= 1 (:current-preset (keybinds/get-preset-switch-state {:client-session-id :session-a
                                                               :player-uuid "player-b"})))))

(deftest activate-handler-registry-policy-test
  (let [handler-a {:id :test/handler
                   :priority 10
                   :handles-fn (fn [_] true)
                   :on-key-down-fn (fn [_] nil)}
        handler-b (assoc handler-a :priority 20)]
    (keybinds/add-activate-handler! handler-a)
    (keybinds/add-activate-handler! (assoc handler-a :on-key-down-fn (fn [_] :new-function)))
    (is (= 1 (count (:activate-handlers (keybinds/keybind-registries-snapshot)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Conflicting activate handler id"
                          (keybinds/add-activate-handler! handler-b)))
    (keybinds/freeze-keybind-registries!)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Keybind registries are frozen"
                          (keybinds/register-key-delegate! :default 0 {:skill-id :railgun})))))

(deftest default-abort-handler-uses-client-abort-hook-test
  (let [aborted (atom [])]
    (keybinds/install-default-handlers!)
    (with-redefs [ctx/get-all-contexts-for-player (fn [& _] [{:id "ctx-1" :status :alive}])
                  runtime-hooks/client-abort-all! (fn [] (swap! aborted conj :abort-hook))
                  ctx/abort-all-contexts-for-player! (fn [& _]
                                                       (throw (ex-info "legacy abort path should not be used" {})))]
      (keybinds/trigger-mode-switch! "p1"))
    (is (= [:abort-hook] @aborted))))

(deftest keybind-registry-runtime-isolation-test
  (let [runtime-a (keybinds/create-keybind-registry-runtime)
        runtime-b (keybinds/create-keybind-registry-runtime)
        handler-a {:id :iso/a
                   :priority 10
                   :handles-fn (fn [_] true)
                   :on-key-down-fn (fn [_] nil)}
        handler-b {:id :iso/b
                   :priority 10
                   :handles-fn (fn [_] true)
                   :on-key-down-fn (fn [_] nil)}]
    (keybinds/call-with-keybind-registry-runtime
      runtime-a
      (fn []
        (keybinds/add-activate-handler! handler-a)
        (keybinds/register-key-delegate! :default 0 {:skill-id :railgun})
        (is (= 1 (count (:activate-handlers (keybinds/keybind-registries-snapshot)))))
        (is (= :railgun
               (:skill-id (keybinds/get-delegate-for-key 0))))))
    (keybinds/call-with-keybind-registry-runtime
      runtime-b
      (fn []
        (is (empty? (:activate-handlers (keybinds/keybind-registries-snapshot))))
        (is (nil? (keybinds/get-delegate-for-key 0)))
        (keybinds/add-activate-handler! handler-b)
        (keybinds/register-key-delegate! :default 0 {:skill-id :meltdowner})
        (is (= :meltdowner
               (:skill-id (keybinds/get-delegate-for-key 0))))))
    (keybinds/call-with-keybind-registry-runtime
      runtime-a
      (fn []
        (is (= 1 (count (:activate-handlers (keybinds/keybind-registries-snapshot)))))
        (is (= :railgun
               (:skill-id (keybinds/get-delegate-for-key 0))))))))


