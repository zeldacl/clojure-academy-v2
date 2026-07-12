(ns cn.li.ac.ability.client.condition-icons
  "Condition type → icon + hint text mappings for skill detail overlay.
  Matching original AcademyCraft SkillTree.scala skillViewArea condition display.

  Original condition icon textures live under abilities/condition/.
  For now, we use existing texture paths; copy condition icons from
  original AcademyCraft assets as needed."
  (:require [cn.li.ac.ability.registry.skill-query :as skill-query]))

(def condition-icon-base-path
  "Base path for condition icon textures."
  "textures/abilities/condition/")

(defn- condition-icon-path
  [suffix]
  (str condition-icon-base-path suffix))

(defn- developer-type-texture
  "Developer type's own block/item texture (upstream DevConditionDeveloperType
   getIcon = type.texture)."
  [dt]
  (case (keyword dt)
    :portable "textures/item/developer_portable_empty.png"
    :advanced "textures/block/dev_advanced.png"
    "textures/block/dev_normal.png"))

;; ============================================================================
;; Condition type → {:icon-path :text-fn}
;; text-fn receives the condition map and returns a hint string.
;; Matches upstream getIcon(): level is not displayed (shouldDisplay=false),
;; developer-type uses the developer texture, prerequisite uses the required
;; skill's icon, any-skill-level uses anyN.png.
;; ============================================================================

(def condition-type-map
  {:developer-type
   {:icon-path (fn [c] (developer-type-texture (:required c (:developer-type c))))
    :text-fn (fn [c]
               (let [dt (:required c (:developer-type c))]
                 (str "Requires " (name dt) " developer")))}

   :prerequisite
   {:icon-path (fn [c] (skill-query/get-skill-icon-path (:skill-id c)))
    :text-fn (fn [c]
               (let [sid (:skill-id c)
                     req (:required c (:min-exp c 0.0))]
                 (if (pos? (double req))
                   (str "Requires " (name sid) " (" (int (* 100.0 req)) "%)")
                   (str "Requires " (name sid)))))}

   :any-skill-level
   {:icon-path (fn [c] (condition-icon-path (str "any" (:required-level c (:level c 1)) ".png")))
    :text-fn (fn [c] (str "Any Level " (:required-level c (:level c)) " skill learned"))}})

(defn condition-display-info
  "Given a condition map (from :failures or :conditions), return
  {:icon-path <string> :hint-text <string>}."
  [{:keys [type] :as condition}]
  (when-let [entry (get condition-type-map type)]
    (let [icon (if (fn? (:icon-path entry))
                 ((:icon-path entry) condition)
                 (:icon-path entry))
          text ((:text-fn entry) condition)]
      {:icon-path icon :hint-text text})))

(defn condition-accepted?
  "Check if a condition from :failures is accepted.
  Conditions in the :failures list are the ones that FAILED."
  [failure]
  false)  ;; failures are always rejected

(defn condition-accepted-icon
  "Return icon path for a condition in accepted state."
  [_condition]
  nil  ;; TODO: use full-color variant
  )

(defn condition-rejected-icon
  "Return icon path for a condition in rejected state (mono-shaded)."
  [condition]
  (:icon-path (condition-display-info condition)))
