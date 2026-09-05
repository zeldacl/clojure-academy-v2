(ns cn.li.ac.gui.info-area-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.info-area :as info-area]))

(deftest hist-from-container-matches-generators-and-node
  (testing "energy bar grows from shared container atoms"
    (let [low (info-area/hist-from-container
                {:energy (atom 1500.0) :max-energy (atom 15000.0)})
          high (info-area/hist-from-container
                 {:energy (atom 12000.0) :max-energy (atom 15000.0)})]
      (is (< (double (get-in low [:hist-bars 0 :h]))
             (double (get-in high [:hist-bars 0 :h]))))))
  (testing "presentation-energy-max-fn wins over a wrong max-energy atom"
    (let [snap (info-area/hist-from-container
                 {:energy (atom 7500.0)
                  :max-energy (atom 7500.0) ;; would pin ratio at 100% if trusted
                  :presentation-energy-max-fn (fn [_] 15000.0)})]
      (is (= 0.5 (:ratio (get-in snap [:histograms 0]))))))
  (testing "zero max-energy uses presentation-energy-max-fn (wireless-node)"
    (let [snap (info-area/hist-from-container
                 {:energy (atom 7500.0)
                  :max-energy (atom 0)
                  :presentation-energy-max-fn (fn [_] 15000.0)})]
      (is (= 0.5 (:ratio (get-in snap [:histograms 0]))))
      (is (< 20.0 (double (get-in snap [:hist-bars 0 :h]))))))
  (testing "matrix without :energy leaves hist to hist-from-network"
    (is (nil? (info-area/hist-from-container {:capacity (atom 1)}))))
  (testing "matrix network capacity hist via shared-info-hist"
    (let [snap (info-area/shared-info-hist
                 {:presentation-network (atom {:load 4 :max-capacity 8})})]
      (is (= 0.5 (:ratio (get-in snap [:histograms 0]))))
      (is (number? (get-in snap [:hist-bars 0 :h])))))
  (testing "info-area/snapshot must not residual-build hist bars"
    (let [snap (info-area/snapshot
                 {:initialized true :energy 5000 :max-energy 10000
                  :load 1 :max-capacity 2 :range 8 :owner "A"
                  :ssid "n" :password "p"}
                 {:owner? true})]
      (is (nil? (:histograms snap)))
      (is (nil? (:hist-bars snap)))
      (is (vector? (:fields snap)))))
  (testing "fill-ratio contract"
    (is (= 0.0 (info-area/fill-ratio 3 0)))
    (is (= 0.5 (info-area/fill-ratio 5 10)))
    (is (= 0.5 (:ratio (info-area/energy-hist 5000 10000))))
    (is (= 0.0 (:ratio (info-area/capacity-hist 3 0)))))
  (testing "bar height tracks ratio continuously (no 3% floor)"
    (let [histograms (info-area/project-histograms
                       [(info-area/energy-hist 150.0 15000.0)])
          h (double (get-in histograms [0 :h]))
          expected (* 48.0 (/ 150.0 15000.0))]
      (is (< h 2.0) "must be below the old 0.03*48≈1.44 floor band visibly")
      (is (< (Math/abs (- h expected)) 0.01)))))

(deftest apply-drafts-to-fields-overlays-draft-keys
  (testing "snapshot field values lose to live draft keys (backspace/typing)"
    (let [state {:node-name "ab"
                 :network-password "xy"
                 :info-area
                 {:fields
                  [(info-area/field-entry
                     {:id :owner :label "Owner" :value "Alice"})
                   (info-area/field-entry
                     {:id :node-name :label "SSID" :value "OLD"
                      :editable? true :draft-key :node-name})
                   (info-area/field-entry
                     {:id :password :label "Password" :value "OLD"
                      :editable? true :draft-key :network-password})]}}
          next (info-area/apply-drafts-to-fields state)]
      (is (= "Alice" (get-in next [:info-area :fields 0 :value])))
      (is (= "ab" (get-in next [:info-area :fields 1 :value])))
      (is (= "xy" (get-in next [:info-area :fields 2 :value])))))
  (testing "missing draft keys leave field values untouched"
    (let [state {:info-area
                 {:fields
                  [(info-area/field-entry
                     {:id :node-name :label "SSID" :value "keep"
                      :editable? true :draft-key :node-name})]}}]
      (is (= "keep"
             (get-in (info-area/apply-drafts-to-fields state)
                     [:info-area :fields 0 :value]))))))

(deftest draft-aliases-unify-ssid-and-node-name
  (testing "form :ssid projects onto canonical :node-name"
    (is (= {:node-name "aaa"}
           (info-area/project-form-drafts {:ssid "aaa"})))
    (is (= "aaa" (get (info-area/expand-drafts {:node-name "aaa"}) :ssid)))
    (is (= "aaa" (get (info-area/expand-drafts {:node-name "aaa"}) :node-name))))
  (testing "empty ssid does not shadow a good node-name"
    (is (= "aaa" (info-area/draft-value {:ssid "" :node-name "aaa"} :node-name)))
    (is (= "aaa" (info-area/draft-value {:ssid "aaa" :node-name ""} :node-name))))
  (testing "empty :network-password does not shadow a good :password"
    (is (= "pw" (info-area/draft-value {:network-password "" :password "pw"}
                                       :network-password)))
    (is (= {:network-password "pw"}
           (info-area/project-form-drafts {:network-password "" :password "pw"})))
    (is (= "pw"
           (:password (info-area/sync-view-into-form {}
                        {:network-password "" :password "pw"})))))
  (testing "apply-drafts resolves :ssid alias onto :node-name draft-key row"
    (let [state {:ssid "typed"
                 :info-area
                 {:fields
                  [(info-area/field-entry
                     {:id :node-name :label "SSID" :value "OLD"
                      :editable? true :draft-key :node-name})]}}]
      (is (= "typed"
             (get-in (info-area/apply-drafts-to-fields state)
                     [:info-area :fields 0 :value])))))
  (testing "empty wireless-style :network-password must not blank a typed password field"
    ;; Regression: snapshot-for used to inject :network-password "" for the
    ;; wireless connect box; apply-drafts then forced the info-area password
    ;; row to "" even after the user typed (matrix INIT).
    (let [state {:network-password ""
                 :password "aaa"
                 :info-area
                 {:fields
                  [(info-area/field-entry
                     {:id :password :label "Password" :value "OLD"
                      :editable? true :draft-key :network-password})]}}]
      (is (= "aaa"
             (get-in (info-area/apply-drafts-to-fields state)
                     [:info-area :fields 0 :value])))))
  (testing "payload-drafts expands field aliases for every page"
    (is (= "pw" (:password (info-area/payload-drafts :network-password "pw"))))
    (is (= "pw" (:network-password (info-area/payload-drafts :password "pw"))))
    (is (= "n" (:ssid (info-area/payload-drafts :node-name "n"))))
    (is (= "n" (:node-name (info-area/payload-drafts :ssid "n"))))))
