(ns cn.li.combat.capability-coverage-test
  "Regression guard for a class of bug this project has hit more than once:
   a compiled ability's :query/:mutate component asks the host table for a
   capability keyword (vm.clj's query-capability-by-component /
   action-capability-by-component) that no handler was ever registered for
   -- combat-core's own platform.clj, or one of the handful of AC-domain
   capabilities AC's install-ac-host-capabilities! owns (resource/progression
   concerns, not world effects). The failure is silent at dispatch time (an
   unpopulated :result slot, or an action that quietly never runs), so
   nothing else in the test suite would catch a newly-added component
   forgetting to register its capability anywhere."
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [cn.li.combat.vm :as vm]
            [cn.li.combat.platform :as platform]))

;; combat-core intentionally does not own these -- they are AC's own
;; resource/progression domain (see ac/.../combat_runtime.clj's
;; install-ac-host-capabilities!), not world-facing effects. Keep this list
;; in sync with that function; a capability moved off of it (or onto it)
;; should show up here as a failing test until this list is updated too.
(def ^:private ac-owned-query-capabilities #{:energy/target})
(def ^:private ac-owned-action-capabilities
  #{:entity/mark :energy/charge :resource/enforce-floor :resource/add})

(deftest every-required-query-capability-has-a-registered-handler-test
  (let [required (:query (vm/required-host-capabilities))
        registered (set (keys (platform/query-handlers)))
        covered (set/union registered ac-owned-query-capabilities)
        missing (set/difference required covered)]
    (is (empty? missing)
        (str "components require query capabilities with no registered "
             "handler anywhere (combat-core or AC): " missing))))

(deftest every-required-action-capability-has-a-registered-handler-test
  (let [required (:action (vm/required-host-capabilities))
        registered (set (keys (platform/action-handlers)))
        covered (set/union registered ac-owned-action-capabilities)
        missing (set/difference required covered)]
    (is (empty? missing)
        (str "components require action capabilities with no registered "
             "handler anywhere (combat-core or AC): " missing))))

;; The inverse direction matters too: an AC-owned entry that combat-core's
;; own platform.clj ALSO registers a handler for would be silently shadowed
;; by whichever install! runs first (both use the same idempotent
;; when-not-contains? guard) -- catch the two lists ever drifting into
;; overlap.
(deftest ac-owned-capabilities-do-not-overlap-combat-core-handlers-test
  (is (empty? (set/intersection ac-owned-query-capabilities
                                (set (keys (platform/query-handlers))))))
  (is (empty? (set/intersection ac-owned-action-capabilities
                                (set (keys (platform/action-handlers)))))))
