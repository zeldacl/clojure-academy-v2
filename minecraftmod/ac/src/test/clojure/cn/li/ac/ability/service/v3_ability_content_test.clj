(ns cn.li.ac.ability.service.v3-ability-content-test
  "Proves the full v3 pipeline works with REAL shipped content, not just
   this session's own test fixtures: :electromaster/brain-course
   (ac/combat/abilities/brain_course.edn) is the first real ability
   marked :engine :v3, loaded through the real manifest and
   combat-catalog/initialize! exactly like every v2 ability, and its
   :program runs through cn.li.combat.skill-runtime/execute!'s v3 branch
   to a real :accepted result -- proving cn.li.combat.recipe/compile-ability's
   engine branch, cn.li.combat.skill-runtime/execute!'s engine branch, and
   the whole AC bootstrap path all compose correctly end to end.

   brain_course.edn's :program is deliberately trivial (every phase is
   just :flow/finish :outcome :passive, unchanged from what a v2 program
   for this ability already looked like -- see NODE_LANGUAGE.md's
   :flow/phases/:flow/finish, node-core builtins shared by both engines)
   -- its real gameplay effect is :passive-effects, a separate mechanism
   cn.li.ac.ability.registry.category/combat-passives already applies
   independently of :program execution. This is intentionally the
   lowest-risk possible first real conversion: proving the pipeline
   itself, not exercising the v3 primitive/composite library (already
   covered thoroughly by cn.li.combat.skill-runtime-v3-engine-test and
   friends with synthetic content)."
  (:require [clojure.test :refer [deftest is]]
            [cn.li.ac.ability.service.combat-catalog :as catalog]
            [cn.li.combat.skill-runtime :as skill-runtime]))

(deftest brain-course-compiles-with-engine-v3-and-is-available-test
  (let [state (catalog/initialize!)
        ability (get-in state [:combat :abilities :electromaster/brain-course])]
    (is (nil? (get-in state [:combat :errors :electromaster/brain-course])))
    (is (= :v3 (:engine ability)))
    (is (map? (:compiled-program ability)))
    (is (catalog/available? :electromaster/brain-course))))

(deftest brain-course-v3-program-executes-to-accepted-test
  (let [state (catalog/initialize!)
        result (skill-runtime/execute!
                state :electromaster/brain-course "owner-1" {:action :start})]
    (is (= :accepted (:status result)))
    (is (empty? (:actions result)))
    (is (empty? (:vfx-signals result)))))
