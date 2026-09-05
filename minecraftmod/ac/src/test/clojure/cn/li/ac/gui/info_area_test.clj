(ns cn.li.ac.gui.info-area-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.info-area :as info-area]))

(deftest hist-bars-scale-with-energy-ratio
  (testing "bar height grows with energy/max-energy (live transfer)"
    (let [low (info-area/snapshot {:initialized true :energy 1500.0 :max-energy 15000.0
                                   :capacity 0 :max-capacity 8} {:owner? true})
          high (info-area/snapshot {:initialized true :energy 12000.0 :max-energy 15000.0
                                    :capacity 0 :max-capacity 8} {:owner? true})
          h0 (get-in low [:hist-bars 0 :h])
          h1 (get-in high [:hist-bars 0 :h])]
      (is (number? h0))
      (is (< (double h0) (double h1)))))
  (testing "zero max-capacity does not pin capacity bar at 100%"
    (let [snap (info-area/snapshot {:initialized true :energy 0.0 :max-energy 15000.0
                                    :capacity 3 :max-capacity 0} {:owner? true})
          ;; Energy bar is index 0; capacity is index 1.
          cap-h (get-in snap [:hist-bars 1 :h])
          min-h (* 48.0 0.03)] ;; HIST-BAR-FULL-H * clamp floor
      (is (<= (double cap-h) (+ min-h 0.01)))))
  (testing "shared hist helpers match fill-ratio contract"
    (is (= 0.0 (info-area/fill-ratio 3 0)))
    (is (= 0.5 (info-area/fill-ratio 5 10)))
    (is (= 0.5 (:ratio (info-area/energy-hist 5000 10000))))
    (is (= 0.0 (:ratio (info-area/capacity-hist 3 0))))))

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
