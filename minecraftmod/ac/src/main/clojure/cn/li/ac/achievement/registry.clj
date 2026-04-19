(ns cn.li.ac.achievement.registry
  "Query helpers over achievement metadata."
  (:require [cn.li.ac.achievement.data :as data]))

(defn all-achievements
  []
  data/achievements)

(defn all-tabs
  []
  data/tabs)

(defn get-by-id
  [achievement-id]
  (some #(when (= achievement-id (:id %)) %) data/achievements))

(defn by-tab
  [tab-id]
  (filter #(= tab-id (:tab %)) data/achievements))

(defn translation-maps
  "Return {:en_us {...} :zh_cn {...}} merged from all achievement entries."
  []
  (reduce
    (fn [acc ach]
      (-> acc
          (update :en_us merge (get-in ach [:translation :en_us] {}))
          (update :zh_cn merge (get-in ach [:translation :zh_cn] {}))))
    {:en_us {} :zh_cn {}}
    data/achievements))

(defn find-by-trigger
  [kind payload]
  (->> data/achievements
       (filter
         (fn [{:keys [trigger-key]}]
           (and trigger-key
                (= kind (:kind trigger-key))
                (case kind
                  :level-change
                  (and (= (:category payload) (:category trigger-key))
                       (= (:level payload) (:level trigger-key)))

                  :skill-learn
                  (= (:skill-id payload) (:skill-id trigger-key))

                  :skill-perform
                  (= (:skill-id payload) (:skill-id trigger-key))

                  :custom
                  (= (:event-id payload) (:event-id trigger-key))

                  false)))))
       (map :id))

