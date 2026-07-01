(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker.

  State stored in Framework [:registry :integrations]."
  (:require [cn.li.mcmod.framework :as fw]))

(defn- default-integration-runtime-state []
  {:jei-get-all-categories (fn [] [])
   :jei-get-recipes (fn [_category] [])
   :jei-format-recipe identity
   :describe-recipe (fn [_recipe] "")})

;; ============================================================================
;; Integration hooks — Framework [:registry :integrations :hooks]
;; ============================================================================

(def ^:private hooks-path [:registry :integrations :hooks])

(defn- integration-hooks-snapshot []
  (if-let [fw-atom fw/*framework*]
    (or (get-in @fw-atom hooks-path) (default-integration-runtime-state))
    (default-integration-runtime-state)))

(defn register-integration-hooks!
  "Register integration hook functions. Duplicate keys must have same value."
  [hooks]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in hooks-path
           (fn [current]
             (let [base (or current (default-integration-runtime-state))]
               (reduce-kv (fn [m k v]
                            (if (and (contains? m k) (not= (get m k) v))
                              (throw (ex-info "Conflicting integration hook"
                                              {:key k :existing (get m k) :new v}))
                              (assoc m k v)))
                          base hooks)))))
  nil)

(defn reset-integration-hooks-for-test!
  []
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in hooks-path (default-integration-runtime-state)))
  nil)

(defn jei-get-all-categories []
  ((:jei-get-all-categories (integration-hooks-snapshot))))

(defn jei-get-recipes [category]
  ((:jei-get-recipes (integration-hooks-snapshot)) category))

(defn jei-format-recipe [recipe]
  ((:jei-format-recipe (integration-hooks-snapshot)) recipe))

(defn describe-recipe [recipe]
  ((:describe-recipe (integration-hooks-snapshot)) recipe))

;; ============================================================================
;; JEI NBT Subtype Item ID Registry — Framework [:registry :integrations :jei-nbt-subtype-ids]
;; Content registers item path-parts; JEI plugin reads at init time.
;; ============================================================================

(def ^:private nbt-subtype-path [:registry :integrations :jei-nbt-subtype-ids])

(defn register-jei-nbt-subtype-item-ids!
  "Register item path-part IDs (without mod-id prefix) for JEI NBT subtype handling.
  JEI uses this to avoid collapsing NBT-stateful item variants (e.g. empty/full)."
  [ids]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in nbt-subtype-path
           (fn [current] (into (or current []) ids))))
  nil)

(defn get-jei-nbt-subtype-item-ids
  "Return all registered item path-part IDs for JEI NBT subtype handling."
  []
  (if-let [fw-atom fw/*framework*]
    (get-in @fw-atom nbt-subtype-path [])
    []))

(defn reset-jei-nbt-subtype-ids-for-test!
  []
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in nbt-subtype-path []))
  nil)
