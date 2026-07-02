(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker.

  State stored in Framework [:registry :integrations]."
  (:require [cn.li.mcmod.framework :as fw]))

(def ^:private default-jei-get-all-categories (fn [] []))
(def ^:private default-jei-get-recipes (fn [_category] []))
(def ^:private default-jei-format-recipe identity)
(def ^:private default-describe-recipe (fn [_recipe] ""))

;; ============================================================================
;; Integration hooks — Framework [:registry :integrations :hooks]
;; ============================================================================

(def ^:private hooks-path [:registry :integrations :hooks])

(defn- registered-hooks
  []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom hooks-path {})
    {}))

(defn register-integration-hooks!
  "Register integration hook functions.

  Throws only when a slot was already claimed by content with a different fn."
  [hooks]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in hooks-path
           (fn [current]
             (let [base (or current {})]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting integration hook"
                                              {:key k :existing (get m k) :new v}))
                              (assoc m k v)))
                          base hooks)))))
  nil)

(defn reset-integration-hooks-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in hooks-path {}))
  nil)

(defn jei-get-all-categories []
  ((or (:jei-get-all-categories (registered-hooks))
       default-jei-get-all-categories)))

(defn jei-get-recipes [category]
  ((or (:jei-get-recipes (registered-hooks))
       default-jei-get-recipes) category))

(defn jei-format-recipe [recipe]
  ((or (:jei-format-recipe (registered-hooks))
       default-jei-format-recipe) recipe))

(defn describe-recipe [recipe]
  ((or (:describe-recipe (registered-hooks))
       default-describe-recipe) recipe))

;; ============================================================================
;; JEI NBT Subtype Item ID Registry — Framework [:registry :integrations :jei-nbt-subtype-ids]
;; Content registers item path-parts; JEI plugin reads at init time.
;; ============================================================================

(def ^:private nbt-subtype-path [:registry :integrations :jei-nbt-subtype-ids])

(defn register-jei-nbt-subtype-item-ids!
  "Register item path-part IDs (without mod-id prefix) for JEI NBT subtype handling.
  JEI uses this to avoid collapsing NBT-stateful item variants (e.g. empty/full)."
  [ids]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in nbt-subtype-path
           (fn [current] (into (or current []) ids))))
  nil)

(defn get-jei-nbt-subtype-item-ids
  "Return all registered item path-part IDs for JEI NBT subtype handling."
  []
  (if-let [fw-atom (fw/fw-atom)]
    (get-in @fw-atom nbt-subtype-path [])
    []))

(defn reset-jei-nbt-subtype-ids-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom assoc-in nbt-subtype-path []))
  nil)
