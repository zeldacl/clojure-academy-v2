(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-test
  "Regression pins for PlasmaCannon's charge/fire model.

  These used to grep the source text for literal fragments; the fx layer has
  since moved behind arc-beam's spec builder and :level moved to
  skill-config/skill-definitions, so the assertions now read the data those
  refactors produce instead of the code that produces it."
  (:require [clojure.test :refer [deftest is testing]]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.skill-config.common :as config-common]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon :as pc]
            [cn.li.ac.content.ability.vecmanip.plasma-cannon-fx :as pc-fx]))

(defn- definition []
  (some #(when (= :plasma-cannon (:id %)) %) config-common/skill-definitions))

(deftest defskill-registration
  (testing "plasma-cannon is a level-5 charge-window vecmanip skill"
    (is (= :plasma-cannon (:id pc/plasma-cannon)))
    (is (= :vecmanip (:category-id pc/plasma-cannon)))
    (is (= :charge-window (:pattern pc/plasma-cannon)))
    ;; :level lives in skill-definitions — defskill rejects it outright.
    (is (= 5 (:level (definition))))))

(deftest key-up-keeps-the-context-alive
  (testing "the projectile keeps flying after the key is released"
    (is (false? (get-in pc/plasma-cannon [:input-policy :terminate-on-key-up?])))
    (is (true? (get-in pc/plasma-cannon [:input-policy :keep-active-on-key-up?])))))

;; Bug 2: charge CP is consumed manually inside the :charging branch, so the
;; declarative cost machinery must stay out of it entirely — a :tick cost here
;; would double-charge, and the 0.0 consume speeds would zero a :cost block.
(deftest plasma-cannon-cost-structure
  (testing "no declarative cost block; charge cost is paid manually"
    (is (nil? (:cost pc/plasma-cannon)))
    (is (= 0.0 (skill-config/cp-consume-speed :plasma-cannon)))
    (is (= 0.0 (skill-config/overload-consume-speed :plasma-cannon)))))

(deftest fx-handlers-updated
  (testing "the fx spec registers all four channels and forwards charge-pos"
    (let [handlers* (atom {})
          enqueued* (atom [])]
      (with-redefs [level-effects/register-level-effect! (fn [& _] nil)
                    fx-registry/register-fx-channel! (fn [topic handler]
                                                       (swap! handlers* assoc topic handler)
                                                       nil)
                    level-effects/enqueue-level-effect! (fn [_ _ _ payload & _]
                                                          (swap! enqueued* conj payload)
                                                          nil)]
        (pc-fx/init!)
        (is (= #{:plasma-cannon/fx-start
                 :plasma-cannon/fx-update
                 :plasma-cannon/fx-perform
                 :plasma-cannon/fx-end}
               (set (keys @handlers*))))
        ((get @handlers* :plasma-cannon/fx-start)
         "ctx-pc" :plasma-cannon/fx-start {:charge-pos {:x 1.0 :y 64.0 :z 2.0}})
        (is (= {:x 1.0 :y 64.0 :z 2.0}
               (:charge-pos (first @enqueued*))))))))
