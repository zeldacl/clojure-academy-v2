(ns cn.li.mcmod.datagen.metadata
  "Platform-neutral datagen metadata registry populated by game content.")

(defonce ^:private achievement-tabs
  (atom []))

(defonce ^:private achievements
  (atom []))

(defonce ^:private translations
  (atom {:en_us {} :zh_cn {}}))

(defonce ^:private recipes
  (atom []))

(defonce ^:private item-overlay-fns
  (atom {}))

(defn register-achievement-data!
  [{:keys [tabs all-achievements translation-maps]}]
  (reset! achievement-tabs (vec (or tabs [])))
  (reset! achievements (vec (or all-achievements [])))
  (reset! translations (or translation-maps {:en_us {} :zh_cn {}}))
  nil)

(defn get-achievement-tabs []
  @achievement-tabs)

(defn get-achievements []
  @achievements)

(defn get-translation-maps []
  @translations)

(defn register-recipes!
  [all-recipes]
  (reset! recipes (vec (or all-recipes [])))
  nil)

(defn get-recipes []
  @recipes)

(defn register-item-overlay-fn!
  [overlay-id f]
  (swap! item-overlay-fns assoc (keyword overlay-id) f)
  nil)

(defn invoke-item-overlay-fn
  [overlay-id & args]
  (when-let [f (get @item-overlay-fns (keyword overlay-id))]
    (apply f args)))