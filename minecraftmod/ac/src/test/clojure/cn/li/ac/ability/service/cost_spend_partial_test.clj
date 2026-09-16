(ns cn.li.ac.ability.service.cost-spend-partial-test
  "cost/spend's :partial? takes what is available instead of refusing.

   The param was declared on :cost/spend, destructured by the handler, and
   then never used, so a partial spend behaved exactly like a full one. Its
   single caller is a deflect cost whose pre-V4 implementation was
   (min current-cp base-cost) with the note 'spends the remaining CP but
   never blocks deflection' -- so on this branch the skill spent NOTHING
   once the player dropped below the full cost, while still deflecting.

   Asserting the amount is the point: both the broken and the fixed handler
   return a boolean and neither throws, so only the CP actually consumed
   distinguishes them."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.service.combat-runtime :as combat-runtime]
            [cn.li.ac.ability.service.command-runtime :as command-runtime]
            [cn.li.ac.ability.service.runtime-store :as runtime-store]
            [cn.li.mcmod.runtime.capabilities :as capabilities]))

(defn- spend-request
  "Install the AC capabilities, then invoke :cost/spend directly with a
   player who holds `cur-cp`, returning the :consume-resource command it
   issued (or nil when it issued none)."
  [cur-cp request]
  (combat-runtime/install-ac-host-capabilities!)
  (let [issued (atom nil)]
    (with-redefs [runtime-store/get-player-state
                  (fn [_session _uuid]
                    {:resource-data {:cur-cp cur-cp :max-cp 100.0
                                     :cur-overload 0.0 :max-overload 100.0
                                     :activated true :overload-fine true}})
                  command-runtime/run-command-in-session!
                  (fn [_session _uuid command] (reset! issued command) nil)]
      (let [handler (get (:queries (capabilities/snapshot)) :cost/spend)]
        (is (some? handler) ":cost/spend is not registered")
        (handler (merge {:owner "p"} request) {:frame {}})
        @issued))))

(deftest partial-spend-takes-what-is-available-test
  (testing "with enough CP a partial spend is an ordinary one"
    (is (= 10.0 (:cp (spend-request 40.0 {:budget {:resources {:cp 10.0}}
                                          :partial? true})))))

  (testing "with too little CP it spends the remainder rather than nothing"
    ;; The defect: this used to issue no command at all, so a player below
    ;; the cost deflected for free.
    (is (= 3.0 (:cp (spend-request 3.0 {:budget {:resources {:cp 10.0}}
                                        :partial? true})))))

  (testing "at zero CP there is nothing to take and no command is issued"
    (is (nil? (spend-request 0.0 {:budget {:resources {:cp 10.0}}
                                  :partial? true}))))

  (testing "without :partial? the spend stays all-or-nothing"
    (is (nil? (spend-request 3.0 {:budget {:resources {:cp 10.0}}})))
    (is (= 10.0 (:cp (spend-request 40.0 {:budget {:resources {:cp 10.0}}})))))

  (testing ":scale still applies before the clamp"
    (is (= 5.0 (:cp (spend-request 40.0 {:budget {:resources {:cp 10.0}}
                                         :scale 0.5 :partial? true}))))))
