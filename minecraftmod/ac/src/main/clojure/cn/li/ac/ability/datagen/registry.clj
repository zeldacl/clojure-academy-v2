(ns cn.li.ac.ability.datagen.registry
  "Datagen registry for ability domain: achievements and recipes.
   Registers metadata during datagen phase for inclusion in JSON outputs."
  (:require [clojure.string :as str]
            [cn.li.ac.ability.datagen.teleporter-translations :as teleporter-translations]
            [cn.li.ac.achievement.registry :as achievement-registry]
            [cn.li.ac.achievement.data :as achievement-data]
            [cn.li.ac.ability.registry.category :as category-registry]
            [cn.li.ac.ability.service.registry :as skill-registry]
            [cn.li.ac.recipe.crafting-recipes :as crafting-recipes]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn- title-case-id
  [v]
  (let [text (-> (name v)
        (str/replace #"[-_]" " "))]
    (->> (str/split text #"\s+")
      (remove str/blank?)
      (map str/capitalize)
      (str/join " "))))

(defn- explicit-skill-translations
  [skills locale]
  (into {}
        (mapcat (fn [{:keys [translations]}]
                  (seq (get translations locale {}))))
        skills))

(defn- ability-translation-map
  []
  (let [categories (category-registry/get-all-categories)
        skills (skill-registry/list-skills)
      teleporter-translation-map (teleporter-translations/translation-map)
        category-name-entries
        (into {}
              (keep (fn [{:keys [id name-key]}]
                      (when (and (keyword? id) (string? name-key))
                        [name-key (title-case-id id)])))
              categories)
        skill-name-entries
        (into {}
              (keep (fn [{:keys [id name-key]}]
                      (when (and (keyword? id) (string? name-key))
                        [name-key (title-case-id id)])))
              skills)
        skill-desc-entries
        (into {}
              (keep (fn [{:keys [id description-key]}]
                      (when (and (keyword? id) (string? description-key))
                        [description-key (str "Ability skill: " (title-case-id id))])))
              skills)
        explicit-en (explicit-skill-translations skills :en_us)
        explicit-zh (explicit-skill-translations skills :zh_cn)
        en (merge category-name-entries
                  skill-name-entries
                  skill-desc-entries
                  explicit-en
                  (:en_us teleporter-translation-map))
        zh (merge en
                  explicit-zh
                  (:zh_cn teleporter-translation-map))]
      {:en_us en
       :zh_cn zh}))

(defn register-datagen-metadata!
  "Register ability domain's datagen content into shared metadata registry.
   Called during datagen initialization phase."
  []
  (let [achievement-tabs (achievement-registry/all-tabs)
        achievements achievement-data/achievements
        recipes (crafting-recipes/get-all-recipes)
        achievement-translation-map (achievement-registry/translation-maps)
        ability-translation-map* (ability-translation-map)]
    (swap! metadata/achievement-tabs (fn [_] achievement-tabs))
    (swap! metadata/achievements (fn [_] achievements))
    (swap! metadata/translations
           (fn [existing]
             (-> existing
                 (update :en_us merge (:en_us achievement-translation-map) (:en_us ability-translation-map*))
                 (update :zh_cn merge (:zh_cn achievement-translation-map) (:zh_cn ability-translation-map*)))))
    (swap! metadata/recipes (fn [_] recipes))))
