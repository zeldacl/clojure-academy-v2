(ns cn.li.ac.ability.messages-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.messages :as ability-messages]
            [cn.li.mcmod.hooks.messages :as message-registry]))

(defn- clean-messages-fixture
  [f]
  (message-registry/clear-messages!)
  (f)
  (message-registry/clear-messages!))

(use-fixtures :each clean-messages-fixture)

(deftest wire-ids-remain-stable-test
  (is (= "ability:sync/runtime-v2" ability-messages/MSG-SYNC-V2))
  (is (= "ability:session/state" ability-messages/MSG-SESSION-STATE))
  ;; The six ability:ctx/* ids went with the legacy Context transport that
  ;; 5be38fe57 deleted -- neither side had registered a handler for any of
  ;; them since. Asserting their absence keeps them from drifting back in.
  (is (empty? (filter #(.startsWith ^String % "ability:ctx/")
                      ability-messages/all-messages)))
  (is (= "ability:req/location-teleport/query" ability-messages/MSG-REQ-SAVED-POS-QUERY))
  (is (= (set (vals ability-messages/message-ids)) ability-messages/all-messages))
  (is (ability-messages/valid-msg-id? ability-messages/MSG-REQ-LEVEL-UP))
  (is (not (ability-messages/valid-msg-id? "ability:req/unknown"))))

(deftest install-registers-ac-messages-test
  (ability-messages/install!)
  (is (= ability-messages/MSG-SESSION-STATE
         (message-registry/msg-id :session-state)))
  (is (= ability-messages/MSG-SYNC-V2
         (message-registry/msg-id :sync-v2)))
  (is (= ability-messages/MSG-REQ-SAVED-POS-PERFORM
         (message-registry/msg-id :req-saved-pos-perform))))

