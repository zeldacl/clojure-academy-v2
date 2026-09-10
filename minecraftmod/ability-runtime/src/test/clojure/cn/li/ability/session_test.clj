(ns cn.li.ability.session-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ability.session :as session]))

(deftest start-and-read-session-test
  (session/reset-for-test! :ac)
  (session/start! :ac "alice" :railgun {:context {:x 1} :server-tick 5})
  (is (session/active? :ac "alice"))
  (is (= :railgun (:ability-id (session/session :ac "alice")))))

(deftest tenants-do-not-collide-on-the-same-owner-test
  (session/reset-for-test!)
  (session/start! :ac "alice" :railgun {})
  (session/start! :bc "alice" :fireball {})
  (is (= :railgun (:ability-id (session/session :ac "alice"))))
  (is (= :fireball (:ability-id (session/session :bc "alice"))))
  (is (nil? (session/session :cc "alice"))))

(deftest abilities-do-not-collide-on-the-same-owner-test
  (session/reset-for-test! :ac)
  (session/start! :ac "alice" :railgun {:server-tick 5})
  (session/start! :ac "alice" :thunder-clap {:server-tick 6})
  (is (= #{:railgun :thunder-clap}
         (set (keys (session/sessions-for-owner :ac "alice")))))
  (is (= 5 (:tick (session/session :ac "alice" :railgun))))
  (is (= 6 (:tick (session/session :ac "alice" :thunder-clap))))
  (session/remove! :ac "alice" :railgun)
  (is (not (session/active? :ac "alice" :railgun)))
  (is (session/active? :ac "alice" :thunder-clap)))

(deftest exact-ability-patches-do-not-leak-between-sessions-test
  (session/reset-for-test! :ac)
  (session/start! :ac "alice" :railgun {})
  (session/start! :ac "alice" :thunder-clap {})
  (session/apply-actions! :ac "alice" :railgun
                           [{:type :session-patch
                             :entries [{:path [:charge] :mode :assign :value 3}]}])
  (is (= 3 (get-in (session/session :ac "alice" :railgun) [:state :charge])))
  (is (nil? (get-in (session/session :ac "alice" :thunder-clap) [:state :charge]))))

(deftest remove-only-affects-its-own-tenant-test
  (session/reset-for-test!)
  (session/start! :ac "alice" :railgun {})
  (session/start! :bc "alice" :fireball {})
  (session/remove! :ac "alice")
  (is (not (session/active? :ac "alice")))
  (is (session/active? :bc "alice")))

(deftest apply-actions-patches-state-and-latches-test
  (session/reset-for-test! :ac)
  (session/start! :ac "alice" :railgun {})
  (session/apply-actions!
   :ac "alice"
   [{:type :session-patch :entries [{:path [:charge] :mode :assign :value 3}
                                    {:path [:charge] :mode :increment :value 1}]}
    {:type :session-latches :latches #{:fired}}])
  (let [entry (session/session :ac "alice")]
    (is (= 4.0 (get-in entry [:state :charge])))
    (is (= #{:fired} (:latches entry)))))

(deftest snapshot-is-scoped-to-one-tenant-and-keyed-by-owner-test
  (session/reset-for-test!)
  (session/start! :ac "alice" :railgun {})
  (session/start! :ac "bob" :thunder-clap {})
  (session/start! :bc "alice" :fireball {})
  (is (= #{"alice" "bob"} (set (keys (session/snapshot :ac)))))
  (is (= :railgun (get-in (session/snapshot :ac) ["alice" :ability-id]))))

(deftest reset-for-test-without-content-id-clears-every-tenant-test
  (session/start! :ac "alice" :railgun {})
  (session/start! :bc "alice" :fireball {})
  (session/reset-for-test!)
  (is (not (session/active? :ac "alice")))
  (is (not (session/active? :bc "alice"))))
