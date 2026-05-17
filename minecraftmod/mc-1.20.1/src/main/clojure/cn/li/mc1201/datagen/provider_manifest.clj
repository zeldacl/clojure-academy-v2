(ns cn.li.mc1201.datagen.provider-manifest
  "Shared datagen provider manifests.

  Provider creation remains platform-specific, but provider order and logical
  provider set live here so Forge/Fabric setup files cannot drift apart."
  (:require [clojure.string :as str]))

(def ^:private provider-groups
  {:lang {:label "Lang"
          :summary-label "lang"
          :languages ["en_us" "zh_cn"]
          :forge {:factory :lang}
          :fabric {:factory :lang}}
   :blockstate {:label "BlockState"
                :summary-label "blockstate"
                :forge {:factory :blockstate}
                :fabric {:factory :blockstate}}
   :item-model {:label "Item Model"
                :summary-label "item-model"
                :forge {:factory :item-model}
                :fabric {:factory :item-model}}
   :recipe {:label "Recipe"
            :summary-label "recipe"
            :forge {:factory :recipe}
            :fabric {:factory :recipe}}
   :advancement {:label "Advancement"
                 :summary-label "advancement"
                 :forge {:factory :advancement}
                 :fabric {:factory :advancement}}})

(def ^:private platform-orders
  {:forge-1.20.1 [:blockstate :item-model :lang :recipe :advancement]
   :fabric-1.20.1 [:lang :blockstate :item-model :advancement :recipe]})

(def ^:private platform-provider-keys
  {:forge-1.20.1 :forge
   :fabric-1.20.1 :fabric})

(defn- provider-group
  [group-id]
  (or (get provider-groups group-id)
      (throw (ex-info "Unknown datagen provider group"
                      {:provider-group group-id
                       :known-groups (sort (keys provider-groups))}))))

(defn- provider-entry
  [platform-key group-id]
  (let [{:keys [label summary-label] :as group} (provider-group group-id)
  provider-key (or (get platform-provider-keys platform-key)
       (throw (ex-info "Unknown datagen provider platform key"
           {:platform platform-key
            :known-platforms (sort (keys platform-provider-keys))})))
  platform-spec (get group provider-key)]
    (when-not platform-spec
      (throw (ex-info "Provider group is not supported on platform"
                      {:platform platform-key
                       :provider-group group-id})))
    (merge {:group group-id
            :id group-id
            :label label
            :summary-label summary-label}
           platform-spec)))

(defn- expand-fabric-language-provider
  [{:keys [languages label summary-label] :as group}]
  (mapv (fn [language-code]
          {:group :lang
           :id (keyword (str "lang-" language-code))
           :label (str label " " language-code)
           :summary-label summary-label
           :factory (get-in group [:fabric :factory])
           :language language-code})
        languages))

(defn providers-for
  [platform-key]
  (let [order (or (get platform-orders platform-key)
                  (throw (ex-info "Unknown datagen provider manifest"
                                  {:platform platform-key
                                   :known-platforms (sort (keys platform-orders))})))]
    (->> order
         (mapcat (fn [group-id]
                   (if (and (= platform-key :fabric-1.20.1)
                            (= group-id :lang))
                     (expand-fabric-language-provider (provider-group group-id))
                     [(provider-entry platform-key group-id)])))
         vec)))

(defn registering-message
  [mod-id {:keys [label]}]
  (str "[" mod-id "] Registering " label " DataGenerator..."))

(defn summary-message
  [mod-id platform-key]
  (let [summary-labels (->> (providers-for platform-key)
                            (map :summary-label)
                            distinct
                            (str/join "+"))]
    (str "[" mod-id "] " (name platform-key)
         " DataGenerator setup registered " summary-labels " providers.")))