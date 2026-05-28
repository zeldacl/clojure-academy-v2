(ns cn.li.mcmod.platform.integration-runtime
  "Platform-neutral bridge for optional content integrations such as JEI and CraftTweaker.")

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

(def ^:dynamic *integration-runtime* nil)

(defonce ^:private installed-integration-runtime
  (create-integration-runtime))

(defn- integration-hooks-atom []
  (:state* (or *integration-runtime* installed-integration-runtime)))

(defn- integration-hooks-snapshot []
  @(integration-hooks-atom))

(defn register-integration-hooks!
  [hooks]
  (swap! (integration-hooks-atom) merge hooks)
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