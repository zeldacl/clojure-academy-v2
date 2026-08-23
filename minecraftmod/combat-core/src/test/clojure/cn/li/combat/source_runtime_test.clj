(ns cn.li.combat.source-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [cn.li.combat.source-runtime :as source-runtime]))

(def ^:private fake-env
  {:caster-facade {:caster/eye {:x 1.0 :y 65.6 :z 2.0}
                   :caster/body {:x 1.0 :y 64.0 :z 2.0}
                   :caster/eye-y 65.6
                   :caster/aim {:x 0.0 :y 0.0 :z 1.0}
                   :caster/id "player-1"
                   :world/id "overworld"
                   :caster/creative? false
                   :movement/forward {:x 0.0 :y 0.0 :z 1.0}
                   :movement/back {:x 0.0 :y 0.0 :z -1.0}
                   :movement/left {:x -1.0 :y 0.0 :z 0.0}
                   :movement/right {:x 1.0 :y 0.0 :z 0.0}
                   :charge/ticks 12
                   :targeting/normal-metal-blocks [:iron :gold]
                   :targeting/weak-metal-blocks [:copper]
                   :targeting/metal-entities [:iron-golem]
                   :progression/mastery 0.75
                   :progression/level 3}
   :tunables {:beam-damage 8.5}
   :costs {:release {:resources {:cp 1.0}}}
   :progression {:hit {:per-mark 0.1}}
   :cooldown {:main {:ticks 40}}
   :invariants {:max-range 32.0}})

(defn- fresh-ctx []
  {:locals {} :seed 0 :env fake-env})

(deftest source?-classifies-only-the-six-source-ids-test
  (is (every? source-runtime/source?
              [:ability/caster :ability/tunable :ability/budget
               :ability/progression :ability/cooldown :ability/invariant]))
  (is (not (source-runtime/source? :combat/damage)))
  (is (not (source-runtime/source? :flow/sequence))))

(deftest ability-caster-binds-every-requested-output-test
  (let [ctx (source-runtime/run
             {:component :ability/caster
              :bind {:eye :eye :body :body :aim :aim :id :owner-id :world-id :wid
                     :creative? :creative :forward :fwd :back :bwd :left :lft :right :rgt
                     :eye-y :ey :charge-ticks :charge
                     :normal-metal-blocks :nmb :weak-metal-blocks :wmb :metal-entities :me
                     :mastery :mastery :level :level}}
             (fresh-ctx))]
    (is (= {:x 1.0 :y 65.6 :z 2.0} (get-in ctx [:locals :eye])))
    (is (= {:x 1.0 :y 64.0 :z 2.0} (get-in ctx [:locals :body])))
    (is (= {:x 0.0 :y 0.0 :z 1.0} (get-in ctx [:locals :aim])))
    (is (= "player-1" (get-in ctx [:locals :owner-id])))
    (is (= "overworld" (get-in ctx [:locals :wid])))
    (is (false? (get-in ctx [:locals :creative])))
    (is (= {:x 0.0 :y 0.0 :z 1.0} (get-in ctx [:locals :fwd])))
    (is (= {:x 0.0 :y 0.0 :z -1.0} (get-in ctx [:locals :bwd])))
    (is (= {:x -1.0 :y 0.0 :z 0.0} (get-in ctx [:locals :lft])))
    (is (= {:x 1.0 :y 0.0 :z 0.0} (get-in ctx [:locals :rgt])))
    (is (= 65.6 (get-in ctx [:locals :ey])))
    (is (= 12 (get-in ctx [:locals :charge])))
    (is (= [:iron :gold] (get-in ctx [:locals :nmb])))
    (is (= [:copper] (get-in ctx [:locals :wmb])))
    (is (= [:iron-golem] (get-in ctx [:locals :me])))
    (is (= 0.75 (get-in ctx [:locals :mastery])))
    (is (= 3 (get-in ctx [:locals :level])))))

(deftest ability-caster-with-no-bind-is-a-no-op-test
  (is (= (fresh-ctx) (source-runtime/run {:component :ability/caster} (fresh-ctx)))))

(deftest ability-tunable-resolves-a-declared-name-test
  (let [ctx (source-runtime/run
             {:component :ability/tunable :name :beam-damage :bind {:value :dmg}}
             (fresh-ctx))]
    (is (= 8.5 (get-in ctx [:locals :dmg])))))

(deftest ability-tunable-throws-on-an-undeclared-name-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not declared"
                        (source-runtime/run
                         {:component :ability/tunable :name :nonexistent :bind {:value :dmg}}
                         (fresh-ctx)))))

(deftest ability-budget-progression-cooldown-invariant-resolve-test
  (is (= {:resources {:cp 1.0}}
         (get-in (source-runtime/run {:component :ability/budget :name :release :bind {:budget :b}} (fresh-ctx))
                 [:locals :b])))
  (is (= {:per-mark 0.1}
         (get-in (source-runtime/run {:component :ability/progression :name :hit :bind {:progression :p}} (fresh-ctx))
                 [:locals :p])))
  (is (= {:ticks 40}
         (get-in (source-runtime/run {:component :ability/cooldown :name :main :bind {:cooldown :c}} (fresh-ctx))
                 [:locals :c])))
  (is (= 32.0
         (get-in (source-runtime/run {:component :ability/invariant :name :max-range :bind {:value :v}} (fresh-ctx))
                 [:locals :v]))))

(deftest name-is-resolved-via-value-resolve-value-not-just-a-literal-test
  ;; :name can itself be a {:ref [:local ...]}/{:expr ...} form like any
  ;; other field -- proves run doesn't special-case literal keywords only.
  (let [ctx (assoc (fresh-ctx) :locals {:which :beam-damage})]
    (is (= 8.5 (get-in (source-runtime/run
                        {:component :ability/tunable :name {:ref [:local :which]} :bind {:value :dmg}}
                        ctx)
                       [:locals :dmg])))))
