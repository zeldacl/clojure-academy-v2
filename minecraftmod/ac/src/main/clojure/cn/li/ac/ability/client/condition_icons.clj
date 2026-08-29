(ns cn.li.ac.ability.client.condition-icons
  "Pure skill-condition presentation projections."
  (:require [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.mcmod.i18n :as i18n]))

(def ^:private condition-icon-base-path "textures/abilities/condition/")

(defn- condition-icon-path [suffix]
  (str condition-icon-base-path suffix))

(defn- developer-type-texture [developer-type]
  (case (keyword developer-type)
    :portable "textures/item/developer_portable_empty.png"
    :advanced "textures/block/dev_advanced.png"
    "textures/block/dev_normal.png"))

(defn- developer-type-display-name [developer-type]
  (i18n/translate (str "skill_tree.academy.type_" (name (keyword developer-type)))))

(defn condition-display-info
  "Project a displayable condition into an icon path and localized hint.

  Level conditions intentionally do not appear: they are implicit in the
  detail title, while developer/prerequisite/any-level conditions are explicit."
  [{:keys [type] :as condition}]
  (case type
    :developer-type
    (let [required (:required condition (:developer-type condition))]
      {:icon-path (developer-type-texture required)
       :hint-text (i18n/translate "skill_tree.academy.requires"
                                  (developer-type-display-name required))})

    :prerequisite
    (let [sid (:skill-id condition)
          required (double (or (:required condition) (:min-exp condition) 0.0))
          skill-name (skill-query/skill-display-name sid)
          suffix (if (pos? required)
                   (str skill-name " (" (int (* 100.0 required)) "%)")
                   skill-name)]
      {:icon-path (skill-query/get-skill-icon-path sid)
       :hint-text (i18n/translate "skill_tree.academy.requires" suffix)})

    :any-skill-level
    (let [level (long (or (:required-level condition) (:level condition) 1))]
      {:icon-path (condition-icon-path (str "any" level ".png"))
       :hint-text (i18n/translate "skill_tree.academy.anyskill" level)})

    nil))