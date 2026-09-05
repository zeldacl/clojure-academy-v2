(ns cn.li.ac.gui.presentation-application-test
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.gui.presentation-application :as app]))

;; Exercise private helpers via the public mount! dispatch path is heavy;
;; re-bind by resolving the private vars for focused contract checks.
(def ^:private live-text-edit? #'app/live-text-edit?)
(def ^:private preserve-text-drafts #'app/preserve-text-drafts)

(deftest live-text-edit-detects-backspace-and-change
  (is (live-text-edit? :input/backspace {}))
  (is (live-text-edit? :application/input {:backspace true}))
  (is (live-text-edit? :customize/x-change {:value "1"}))
  (is (not (live-text-edit? :application/activate {:value "x"})))
  (is (not (live-text-edit? :location/add {:value "spawn"}))))

(deftest preserve-text-drafts-keeps-input-when-handler-omits-it
  (testing "freq-style partial snapshot still keeps the edited :input"
    (let [current {:input "ab" :lines [] :status "authorize"}
          result {:lines [{:label "x"}] :status "authorize"}
          next (preserve-text-drafts result current :application/input
                                     {:value "a" :path [:state :input] :backspace true})]
      (is (= "ab" (:input next)))
      (is (= "authorize" (:status next)))))
  (testing "submit/activate does not force-preserve stale drafts over clears"
    (let [current {:input "keep"}
          result {:input "" :status "ok"}
          next (preserve-text-drafts result current :application/activate
                                     {:value "keep" :path [:state :input]})]
      (is (= "" (:input next))))))
