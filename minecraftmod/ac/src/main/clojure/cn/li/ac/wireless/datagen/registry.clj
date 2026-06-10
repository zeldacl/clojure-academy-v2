(ns cn.li.ac.wireless.datagen.registry
  "Datagen registry for wireless domain.
   Provides lightweight metadata output for wireless configuration and labels."
  (:require [cn.li.ac.wireless.config :as wireless-config]
            [cn.li.mcmod.datagen.metadata :as metadata]))

(defn- descriptor->entry
  [{:keys [path comment default]}]
  [(str "config.my_mod." path)
   (str comment " (default=" default ")")])

(defn- wireless-translation-map
  []
  (let [descriptor-entries (into {} (map descriptor->entry wireless-config/descriptors))]
    {:en_us (merge
              {"domain.my_mod.wireless" "Wireless System"
               "wireless.my_mod.network" "Wireless Network"
               "wireless.my_mod.node" "Wireless Node"}
              descriptor-entries)
     :zh_cn (merge
              {"domain.my_mod.wireless" "无线系统"
               "wireless.my_mod.network" "无线网络"
               "wireless.my_mod.node" "无线节点"}
              descriptor-entries)
     :zh_tw (merge
              {"domain.my_mod.wireless" "無線系統"
               "wireless.my_mod.network" "無線網路"
               "wireless.my_mod.node" "無線節點"}
              descriptor-entries)
     :ja_jp (merge
              {"domain.my_mod.wireless" "無線システム"
               "wireless.my_mod.network" "無線ネットワーク"
               "wireless.my_mod.node" "無線ノード"}
              descriptor-entries)
     :ko_kr (merge
              {"domain.my_mod.wireless" "무선 시스템"
               "wireless.my_mod.network" "무선 네트워크"
               "wireless.my_mod.node" "무선 노드"}
              descriptor-entries)
     :ru_ru (merge
              {"domain.my_mod.wireless" "Беспроводная система"
               "wireless.my_mod.network" "Беспроводная сеть"
               "wireless.my_mod.node" "Беспроводной узел"}
              descriptor-entries)}))

(defn register-datagen-metadata!
  "Register wireless domain's datagen content.
   Called during datagen initialization phase."
  []
  (let [translation-map (wireless-translation-map)]
    (metadata/merge-translations! translation-map)
    {:domain :wireless
     :translations (count (:en_us translation-map))
    :config-descriptors (count wireless-config/descriptors)}))
