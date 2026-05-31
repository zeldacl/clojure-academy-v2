(ns cn.li.ac.ability.client.screens.skill-tree-test
  (:require 
            [cn.li.ac.ability.service.runtime-store :as store]
[clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.managed-screens :as managed-screens]
            [cn.li.ac.ability.client.screens.skill-tree :as screen]            [cn.li.ac.ability.registry.skill-query :as skill]
            [cn.li.ac.ability.rules.learning-rules :as learning-rules]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.i18n :as i18n]))

(defn- reset-screen-fixture [f]
  (managed-screens/call-with-managed-screen-runtime
    (managed-screens/create-managed-screen-runtime)
    (fn []
      (binding [runtime-hooks/*client-session-id* :test-session
                runtime-hooks/*player-state-owner* {:client-session-id :test-session}]
        (f)))))

(use-fixtures :each reset-screen-fixture)

(deftest build-screen-render-data-translates-skill-name-and-description-test
  (let [player-state {:ability-data {:category-id :generic
                                     :category {:category-id :generic
                                                :name-key "ability.category.generic"}
                                     :learned-skills #{:generic/brain-course}
                                     :skill-exps {:generic/brain-course 0.35}
                                     :level 3}
                      :resource-data {:cur-cp 1000.0
                                      :max-cp 2000.0
                                      :cur-overload 10.0
                                      :max-overload 100.0}}
        skill-spec {:id :generic/brain-course
                    :category-id :generic
                    :name-key "ability.skill.generic.brain_course"
                    :description-key "ability.skill.generic.brain_course.desc"
                    :icon "textures/abilities/generic/skills/brain_course.png"
                    :ui-position [30 110]
                    :level 3
                    :prerequisites []}
        translate-map {"ability.category.generic" "Generic"
                       "ability.skill.generic.brain_course" "Brain Course"
                       "ability.skill.generic.brain_course.desc" "Undergo focused neural training to raise your maximum CP by 1000."}]
      (with-redefs [store/get-player-state* (fn [_ _] player-state)
              store/get-or-create-player-state! (fn [_ _] player-state)
                  skill/get-skills-for-category (fn [_] [skill-spec])
                  skill/get-skill-icon-path (fn [_] "textures/abilities/generic/skills/brain_course.png")
                  learning-rules/check-all-conditions (fn [_ _ _ _] {:pass? true :failures []})
                  i18n/*translate-fn* (fn [k] (get translate-map (str k) (str k)))]
            (screen/open-screen! "player-1")
      (let [render-data (screen/build-screen-render-data "player-1")
            node (first (:skill-nodes render-data))]
        (is (= "Generic" (get-in render-data [:ability-info :category-name])))
        (is (= "Brain Course" (:skill-name node)))
        (is (= "Undergo focused neural training to raise your maximum CP by 1000."
           (:skill-description node)))))))

(deftest screen-owner-requires-explicit-session-and-player-test
  (binding [runtime-hooks/*client-session-id* nil]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Client read model owner requires :client-session-id"
                          (screen/screen-state-snapshot "player-1"))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Client read model owner requires :player-uuid"
                        (screen/screen-state-snapshot {:client-session-id :session-a}))))

(deftest build-draw-ops-includes-hover-description-tooltip-test
  (let [player-state {:ability-data {:category-id :generic
                                     :category {:category-id :generic
                                                :name-key "ability.category.generic"}
                                     :learned-skills #{:generic/brain-course}
                                     :skill-exps {:generic/brain-course 0.35}
                                     :level 3}
                      :resource-data {:cur-cp 1000.0
                                      :max-cp 2000.0
                                      :cur-overload 10.0
                                      :max-overload 100.0}}
        skill-spec {:id :generic/brain-course
                    :category-id :generic
                    :name-key "ability.skill.generic.brain_course"
                    :description-key "ability.skill.generic.brain_course.desc"
                    :icon "textures/abilities/generic/skills/brain_course.png"
                    :ui-position [30 110]
                    :level 3
                    :prerequisites []}
        translate-map {"ability.category.generic" "Generic"
                       "ability.skill.generic.brain_course" "Brain Course"
                       "ability.skill.generic.brain_course.desc" "Undergo focused neural training to raise your maximum CP by 1000."}]
      (with-redefs [store/get-player-state* (fn [_ _] player-state)
              store/get-or-create-player-state! (fn [_ _] player-state)
                  skill/get-skills-for-category (fn [_] [skill-spec])
                  skill/get-skill-icon-path (fn [_] "textures/abilities/generic/skills/brain_course.png")
                  learning-rules/check-all-conditions (fn [_ _ _ _] {:pass? true :failures []})
                  i18n/*translate-fn* (fn [k] (get translate-map (str k) (str k)))]
            (screen/open-screen! "player-1")
            (screen/on-mouse-move "player-1" 30 110)
      (let [texts (->> (screen/build-draw-ops "player-1" 0 0)
                       (filter #(= :text (:kind %)))
                       (map :text)
                       set)]
        (is (contains? texts "Brain Course"))
        (is (contains? texts "Undergo focused neural training to raise your maximum CP by 1000."))))))

      (deftest screen-state-isolated-by-player-owner-test
        (screen/open-screen! "player-1" {:developer-type :portable})
        (screen/open-screen! "player-2" {:developer-type :normal})
        (is (= {:developer-type :portable}
          (:learn-context (screen/screen-state-snapshot "player-1"))))
        (is (= {:developer-type :normal}
          (:learn-context (screen/screen-state-snapshot "player-2"))))
        (screen/close-screen! "player-1")
        (is (nil? (:player-uuid (screen/screen-state-snapshot "player-1"))))
        (is (= "player-2" (:player-uuid (screen/screen-state-snapshot "player-2")))))

(deftest screen-runtime-isolation-test
  (let [runtime-b (managed-screens/create-managed-screen-runtime)]
    (screen/open-screen! "player-1" {:developer-type :portable})
    (managed-screens/call-with-managed-screen-runtime
      runtime-b
      (fn []
        (screen/open-screen! "player-1" {:developer-type :normal})
        (is (= {:developer-type :normal}
               (:learn-context (screen/screen-state-snapshot "player-1"))))))
    (is (= {:developer-type :portable}
           (:learn-context (screen/screen-state-snapshot "player-1"))))))

