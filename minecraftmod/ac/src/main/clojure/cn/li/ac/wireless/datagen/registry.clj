(ns cn.li.ac.wireless.datagen.registry
  "Datagen registry for wireless domain.
   Provides lightweight metadata output for wireless configuration and labels."
  (:require [cn.li.ac.wireless.data.network-config :as network-config]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn- descriptor->entry
  [{:keys [path comment default]}]
  [(str "config.my_mod." path)
   (str comment " (default=" default ")")])

(defn- wireless-translation-map
  []
  (let [descriptor-entries (into {} (map descriptor->entry network-config/descriptors))]
    {:en_us (merge
              {"domain.my_mod.wireless" "Wireless System"
               "wireless.my_mod.network" "Wireless Network"
               "wireless.my_mod.node" "Wireless Node"}
              descriptor-entries)
     :zh_cn (merge
              {"domain.my_mod.wireless" "无线系统"
               "wireless.my_mod.network" "无线网络"
               "wireless.my_mod.node" "无线节点"}
              descriptor-entries)}))

(defn register-datagen-metadata!
  "Register wireless domain's datagen content.
   Called during datagen initialization phase."
  []
  (let [translation-map (wireless-translation-map)]
    (swap! metadata/translations
           (fn [existing]
             (-> existing
                 (update :en_us merge (:en_us translation-map))
                 (update :zh_cn merge (:zh_cn translation-map)))))
    {:domain :wireless
     :translations (count (:en_us translation-map))
     :config-descriptors (count network-config/descriptors)}))
