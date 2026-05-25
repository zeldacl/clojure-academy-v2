(ns cn.li.ac.ability.client.screens.skill-tree-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [cn.li.ac.ability.client.screens.skill-tree :as screen]
            [cn.li.ac.ability.service.player-state :as ps]
            [cn.li.ac.ability.registry.skill-query :as skill]
            [cn.li.ac.ability.server.service.learning :as learning]
            [cn.li.mcmod.i18n :as i18n]))

(defn- reset-screen-fixture [f]
  (reset! (var-get #'cn.li.ac.ability.client.screens.skill-tree/screen-state)
          {:hover-skill nil
           :player-uuid nil
           :learn-context nil})
  (f)
  (reset! (var-get #'cn.li.ac.ability.client.screens.skill-tree/screen-state)
          {:hover-skill nil
           :player-uuid nil
           :learn-context nil}))

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
    (with-redefs [ps/get-player-state (fn [_] player-state)
              ps/get-or-create-player-state! (fn [_] player-state)
                  skill/get-skills-for-category (fn [_] [skill-spec])
                  skill/get-skill-icon-path (fn [_] "textures/abilities/generic/skills/brain_course.png")
                  learning/check-all-conditions (fn [_ _ _ _] {:pass? true :failures []})
                  i18n/*translate-fn* (fn [k] (get translate-map (str k) (str k)))]
            (screen/open-screen! "player-1")
      (let [render-data (screen/build-screen-render-data)
            node (first (:skill-nodes render-data))]
        (is (= "Generic" (get-in render-data [:ability-info :category-name])))
        (is (= "Brain Course" (:skill-name node)))
        (is (= "Undergo focused neural training to raise your maximum CP by 1000."
           (:skill-description node)))))))

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
    (with-redefs [ps/get-player-state (fn [_] player-state)
              ps/get-or-create-player-state! (fn [_] player-state)
                  skill/get-skills-for-category (fn [_] [skill-spec])
                  skill/get-skill-icon-path (fn [_] "textures/abilities/generic/skills/brain_course.png")
                  learning/check-all-conditions (fn [_ _ _ _] {:pass? true :failures []})
                  i18n/*translate-fn* (fn [k] (get translate-map (str k) (str k)))]
            (screen/open-screen! "player-1")
            (swap! (var-get #'cn.li.ac.ability.client.screens.skill-tree/screen-state)
              assoc :hover-skill :generic/brain-course)
      (let [texts (->> (screen/build-draw-ops 0 0)
                       (filter #(= :text (:kind %)))
                       (map :text)
                       set)]
        (is (contains? texts "Brain Course"))
        (is (contains? texts "Undergo focused neural training to raise your maximum CP by 1000."))))))