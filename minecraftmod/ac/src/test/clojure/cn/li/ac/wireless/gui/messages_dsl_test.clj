(ns cn.li.ac.wireless.gui.messages-dsl-test
  "Tests for wireless GUI message DSL and catalog integration."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.mcmod.gui.message.dsl :as msg-dsl]
            [cn.li.ac.gui.manifest :as gui-manifest]
            [cn.li.ac.wireless.gui.message.registry :as msg-registry]
            [cn.li.ac.wireless.gui.message.api :as wireless-msgs]
            [cn.li.ac.wireless.shared.message-registry :as shared-registry]))

(def expected-node-actions
  #{:get-status :change-name :change-password :list-networks :connect :disconnect})

(def expected-matrix-actions
  #{:gather-info :init :change-ssid :change-password :sync-state})

(def ^:private wireless-prefix "wireless")

(deftest message-id-format-test
  (is (= "wireless_node_get_status" (msg-dsl/message-id wireless-prefix :node :get-status)))
  (is (= "wireless_matrix_gather_info" (msg-dsl/message-id wireless-prefix :matrix :gather-info)))
  (is (= "wireless_wind_gen_get_status_main" (msg-dsl/message-id wireless-prefix :wind-gen :get-status-main))))

(deftest domain-spec-validation-test
  (testing "build-domain-spec constructs expected map"
    (let [spec (msg-dsl/build-domain-spec wireless-prefix :demo [:alpha :beta])]
      (is (= :demo (:domain spec)))
      (is (= "wireless_demo_alpha" (get-in spec [:messages :alpha])))
      (is (= 2 (count (:specs spec))))))
  (testing "duplicate actions are rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Duplicate actions"
          (msg-dsl/build-domain-spec wireless-prefix :dup [:x :x])))))

(deftest catalog-validation-test
  (testing "cross-domain duplicate message ids are rejected"
    (let [s1 {:domain :x
              :messages {:a "wireless_collision_id"}
              :specs [{:domain :x :action :a :msg-id "wireless_collision_id"}]}
          s2 {:domain :y
              :messages {:b "wireless_collision_id"}
              :specs [{:domain :y :action :b :msg-id "wireless_collision_id"}]}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Duplicate message ids"
            (msg-dsl/build-catalog [s1 s2])))))
  (testing "naming format is enforced"
    (let [bad {:domain :z
               :messages {:a "UPPERCASE_ID"}
               :specs [{:domain :z :action :a :msg-id "UPPERCASE_ID"}]}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid message ids"
            (msg-dsl/build-catalog [bad]))))))

(deftest wireless-message-integration-test
  (shared-registry/register-all!)
  (let [catalog (wireless-msgs/catalog)
        node-spec (msg-registry/get-domain-spec :node)
        matrix-spec (msg-registry/get-domain-spec :matrix)]
    (testing "required domains are registered"
      (is (some? node-spec))
      (is (some? matrix-spec))
      (is (contains? (:domains catalog) :node))
      (is (contains? (:domains catalog) :matrix))
      (is (empty? (gui-manifest/missing-message-domains [:node :matrix :generator :wind-gen
                                                         :phase-gen :imag-fusor :metal-former
                                                         :developer :ability-interferer]))))

    (testing "registered actions match expected sets"
      (is (= expected-node-actions (set (keys (:messages node-spec)))))
      (is (= expected-matrix-actions (set (keys (:messages matrix-spec)))))
      (is (= (set (gui-manifest/message-actions :metal-former))
             (set (keys (:messages (msg-registry/get-domain-spec :metal-former)))))))

    (testing "catalog lookups are bidirectional"
      (is (= "wireless_node_get_status" (wireless-msgs/msg :node :get-status)))
      (is (= "wireless_matrix_init" (wireless-msgs/msg :matrix :init)))
      (is (= {:domain :node :action :get-status}
             (wireless-msgs/find-by-msg-id "wireless_node_get_status")))
    )
  )
)

(deftest wireless-message-registry-duplicate-and-freeze-policy-test
  (let [snapshot (msg-registry/registry-snapshot)]
    (try
      (msg-registry/reset-registry-for-test!)
      (let [spec (msg-registry/register-block-messages! :demo [:alpha])]
        (is (= spec (msg-registry/register-block-messages! :demo [:alpha])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Conflicting wireless GUI message domain"
                              (msg-registry/register-block-messages! :demo [:beta])))
        (msg-registry/freeze-registry!)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Wireless GUI message registry is frozen"
                              (msg-registry/register-block-messages! :new-domain [:x]))))
      (finally
        (msg-registry/reset-registry-for-test! snapshot)))))
