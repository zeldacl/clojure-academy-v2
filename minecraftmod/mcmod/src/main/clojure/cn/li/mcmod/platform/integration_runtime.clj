(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker."
  (:require [cn.li.mcmod.platform.runtime :as prt]))

(defn- default-integration-runtime-state []
  {:jei-get-all-categories (fn [] [])
   :jei-get-recipes (fn [_category] [])
   :jei-format-recipe identity
   :describe-recipe (fn [_recipe] "")})

(defn create-integration-runtime
  ([] (create-integration-runtime {}))
  ([{:keys [state*]}]
   {:cn.li.mcmod.platform.integration-runtime/runtime ::integration-runtime
    :state* (or state* (atom (default-integration-runtime-state)))}))

(def ^:private _integration-runtime (delay (create-integration-runtime)))

(def ^:dynamic *integration-runtime* nil)

(defn- integration-hooks-atom []
  (:state* (or *integration-runtime*
                  @_integration-runtime)))

(defn- integration-hooks-snapshot []
  @(integration-hooks-atom))

(defn register-integration-hooks!
  [hooks]
  (doseq [[k v] hooks]
    (prt/register-hook! (integration-hooks-atom) k v
                        :duplicate-policy :same-value-idempotent
                        :label "integration-runtime"))
  nil)

(defn reset-integration-hooks-for-test!
  []
  (reset! (integration-hooks-atom) (default-integration-runtime-state))
  nil)

(defn jei-get-all-categories
  []
  ((:jei-get-all-categories (integration-hooks-snapshot))))

(defn jei-get-recipes
  [category]
  ((:jei-get-recipes (integration-hooks-snapshot)) category))

(defn jei-format-recipe
  [recipe]
  ((:jei-format-recipe (integration-hooks-snapshot)) recipe))

(defn describe-recipe
  [recipe]
  ((:describe-recipe (integration-hooks-snapshot)) recipe))

;; ============================================================================
;; JEI NBT Subtype Item ID Registry
;; Content registers item path-parts; JEI plugin reads at init time.
;; ============================================================================

(defonce ^:private jei-nbt-subtype-ids* (atom []))

(defn register-jei-nbt-subtype-item-ids!
  "Register item path-part IDs (without mod-id prefix) for JEI NBT subtype handling.
  JEI uses this to avoid collapsing NBT-stateful item variants (e.g. empty/full)."
  [ids]
  (swap! jei-nbt-subtype-ids* into ids)
  nil)

(defn get-jei-nbt-subtype-item-ids
  "Return all registered item path-part IDs for JEI NBT subtype handling."
  []
  @jei-nbt-subtype-ids*)

(defn reset-jei-nbt-subtype-ids-for-test!
  []
  (reset! jei-nbt-subtype-ids* [])
  nil)