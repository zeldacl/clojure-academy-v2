(ns cn.li.wireless.gui.messages-dsl-test
  "Tests for wireless GUI message DSL.
  Covers: naming conventions, uniqueness constraints,
  domain lookups, and catalog-level consistency."
  (:require [cn.li.mcmod.gui.message.dsl :as msg-dsl]
            [cn.li.ac.wireless.gui.node-messages :as node-msgs]
            [cn.li.ac.wireless.gui.matrix-messages :as matrix-msgs]
            [cn.li.ac.wireless.gui.message.api :as wireless-msgs]))

;; ============================================================================
;; DSL core — message-id generation
;; ============================================================================

(defn test-message-id-format
  []
  (assert (= "wireless_node_get_status"
             (msg-dsl/message-id :node :get-status))
          "Hyphen in action should map to underscore in msg-id")
  (assert (= "wireless_matrix_gather_info"
             (msg-dsl/message-id :matrix :gather-info))
          "Compound action should produce correct token")
  (assert (= "wireless_node_connect"
             (msg-dsl/message-id :node :connect))
          "Single-word action stays unchanged"))

;; ============================================================================
;; DSL core — build-domain-spec
;; ============================================================================

(defn test-build-domain-spec-happy-path
  []
  (let [spec (msg-dsl/build-domain-spec :demo [:alpha :beta :gamma])]
    (assert (= :demo (:domain spec))
            "Domain stored in spec")
    (assert (= 3 (count (:specs spec)))
            "Spec entry per action")
    (assert (= "wireless_demo_alpha" (get-in spec [:messages :alpha]))
            "Message id for :alpha")
    (assert (= "wireless_demo_beta" (get-in spec [:messages :beta]))
            "Message id for :beta")))

(defn test-build-domain-spec-rejects-duplicates
  []
  (let [threw? (try
                 (msg-dsl/build-domain-spec :demo [:alpha :alpha :beta])
                 false
                 (catch clojure.lang.ExceptionInfo ex
                   (assert (= :demo (:domain (ex-data ex)))
                           "Error data includes the domain")
                   (assert (contains? (ex-data ex) :duplicate-actions)
                           "Error data includes :duplicate-actions")
                   true))]
    (assert threw? "Duplicate actions must throw")))

;; ============================================================================
;; DSL core — build-catalog
;; ============================================================================

(defn test-build-catalog-happy-path
  []
  (let [s1 (msg-dsl/build-domain-spec :foo [:a :b])
        s2 (msg-dsl/build-domain-spec :bar [:c :d])
        cat (msg-dsl/build-catalog [s1 s2])]
    (assert (= 4 (count (:specs cat)))
            "All specs in catalog")
    (assert (contains? (:domains cat) :foo)
            ":foo domain present")
    (assert (contains? (:domains cat) :bar)
            ":bar domain present")
    (assert (= "wireless_foo_a" (msg-dsl/msg-id cat :foo :a))
            "msg-id lookup for :foo/:a")))

(defn test-build-catalog-rejects-cross-domain-duplicate-ids
  []
  ;; If two domains produce the same final string, catalog must throw.
  ;; node + node2 sharing :x would both expand to the same id only if
  ;; domain names differ — craft a pathological case by hand.
  (let [s1 {:domain :x
             :messages {:foo "wireless_shared_collision"}
             :specs [{:domain :x :action :foo :msg-id "wireless_shared_collision"}]}
        s2 {:domain :y
             :messages {:bar "wireless_shared_collision"}
             :specs [{:domain :y :action :bar :msg-id "wireless_shared_collision"}]}
        threw? (try
                 (msg-dsl/build-catalog [s1 s2])
                 false
                 (catch clojure.lang.ExceptionInfo _
                   true))]
    (assert threw? "Cross-domain msg-id collision must throw")))

(defn test-build-catalog-rejects-invalid-naming
  []
  (let [bad-spec {:domain :z
                  :messages {:foo "UPPERCASE_ID"}
                  :specs [{:domain :z :action :foo :msg-id "UPPERCASE_ID"}]}
        threw? (try
                 (msg-dsl/build-catalog [bad-spec])
                 false
                 (catch clojure.lang.ExceptionInfo _
                   true))]
    (assert threw? "Uppercase msg-id should fail naming validation")))

(defn test-find-by-msg-id
  []
  (let [s (msg-dsl/build-domain-spec :foo [:a :b])
        cat (msg-dsl/build-catalog [s])]
    (assert (= {:domain :foo :action :a}
               (msg-dsl/find-by-msg-id cat "wireless_foo_a"))
            "Reverse lookup by msg-id")
    (assert (nil? (msg-dsl/find-by-msg-id cat "wireless_foo_nonexistent"))
            "Unknown msg-id returns nil")))

;; ============================================================================
;; node-messages — completeness
;; ============================================================================

(def expected-node-actions
  #{:get-status :change-name :change-password :list-networks :connect :disconnect})

(defn test-node-messages-complete
  []
  (let [declared (set (keys (get-in node-msgs/node-domain-spec [:messages])))]
    (assert (= expected-node-actions declared)
            (str "node-messages must declare exactly " expected-node-actions
                 ", got " declared))))

(defn test-node-messages-ids-use-node-prefix
  []
  (doseq [id (vals (get-in node-msgs/node-domain-spec [:messages]))]
    (assert (.startsWith id "wireless_node_")
            (str id " must start with wireless_node_"))))

(defn test-node-msg-helper-resolves
  []
  (assert (= "wireless_node_get_status" (node-msgs/msg :get-status)))
  (assert (= "wireless_node_connect"    (node-msgs/msg :connect)))
  (assert (= "wireless_node_disconnect" (node-msgs/msg :disconnect))))

(defn test-node-msg-helper-throws-on-unknown
  []
  (let [threw? (try (node-msgs/msg :nonexistent) false
                    (catch clojure.lang.ExceptionInfo _ true))]
    (assert threw? "node-msgs/msg must throw for unknown action")))

;; ============================================================================
;; matrix-messages — completeness
;; ============================================================================

(def expected-matrix-actions
  #{:gather-info :init :change-ssid :change-password})

(defn test-matrix-messages-complete
  []
  (let [declared (set (keys (get-in matrix-msgs/matrix-domain-spec [:messages])))]
    (assert (= expected-matrix-actions declared)
            (str "matrix-messages must declare exactly " expected-matrix-actions
                 ", got " declared))))

(defn test-matrix-messages-ids-use-matrix-prefix
  []
  (doseq [id (vals (get-in matrix-msgs/matrix-domain-spec [:messages]))]
    (assert (.startsWith id "wireless_matrix_")
            (str id " must start with wireless_matrix_"))))

(defn test-matrix-msg-helper-resolves
  []
  (assert (= "wireless_matrix_gather_info"    (matrix-msgs/msg :gather-info)))
  (assert (= "wireless_matrix_init"           (matrix-msgs/msg :init)))
  (assert (= "wireless_matrix_change_ssid"    (matrix-msgs/msg :change-ssid)))
  (assert (= "wireless_matrix_change_password" (matrix-msgs/msg :change-password))))

;; ============================================================================
;; wireless-messages catalog — integration
;; ============================================================================

(defn test-catalog-contains-all-messages
  []
  (let [all-ids (set (map :msg-id (:specs wireless-msgs/catalog)))
        expected-count (+ (count expected-node-actions)
                          (count expected-matrix-actions))]
    (assert (= expected-count (count all-ids))
            (str "Catalog must contain " expected-count " distinct message ids, got " (count all-ids)))))

(defn test-catalog-domains-present
  []
  (assert (contains? (:domains wireless-msgs/catalog) :node)   ":node domain in catalog")
  (assert (contains? (:domains wireless-msgs/catalog) :matrix) ":matrix domain in catalog"))

(defn test-catalog-cross-lookup
  []
  ;; Given a msg-id string, can we always reverse-lookup its origin?
  (doseq [{:keys [domain action msg-id]} (:specs wireless-msgs/catalog)]
    (let [found (wireless-msgs/find-by-msg-id msg-id)]
      (assert (= domain (:domain found))
              (str msg-id " reverse lookup domain mismatch"))
      (assert (= action (:action found))
              (str msg-id " reverse lookup action mismatch")))))

(defn test-wireless-msg-dispatch
  []
  (assert (= "wireless_node_get_status"
             (wireless-msgs/msg :node :get-status)))
  (assert (= "wireless_matrix_init"
             (wireless-msgs/msg :matrix :init))))

;; ============================================================================
;; Runner
;; ============================================================================

(defn run-all-tests
  []
  (println "=== wireless messages DSL tests ===")
  (doseq [[test-name test-fn]
          [["message-id format"                     test-message-id-format]
           ["build-domain-spec happy path"          test-build-domain-spec-happy-path]
           ["build-domain-spec rejects duplicates"  test-build-domain-spec-rejects-duplicates]
           ["build-catalog happy path"              test-build-catalog-happy-path]
           ["build-catalog rejects collision"       test-build-catalog-rejects-cross-domain-duplicate-ids]
           ["build-catalog rejects invalid naming"  test-build-catalog-rejects-invalid-naming]
           ["find-by-msg-id"                        test-find-by-msg-id]
           ["node-messages complete"                test-node-messages-complete]
           ["node-messages ids prefix"              test-node-messages-ids-use-node-prefix]
           ["node-msgs/msg resolves"                test-node-msg-helper-resolves]
           ["node-msgs/msg throws on unknown"       test-node-msg-helper-throws-on-unknown]
           ["matrix-messages complete"              test-matrix-messages-complete]
           ["matrix-messages ids prefix"            test-matrix-messages-ids-use-matrix-prefix]
           ["matrix-msgs/msg resolves"              test-matrix-msg-helper-resolves]
           ["catalog contains all messages"         test-catalog-contains-all-messages]
           ["catalog domains present"               test-catalog-domains-present]
           ["catalog cross-lookup"                  test-catalog-cross-lookup]
           ["wireless-msgs dispatch"                test-wireless-msg-dispatch]]]
    (try
      (test-fn)
      (println (str "  ✓ " test-name))
      (catch AssertionError e
        (println (str "  ✗ " test-name ": " (.getMessage e)))
        (throw e))))
  (println "=== All tests passed ==="))

(comment
  (run-all-tests)
  :rcf)
