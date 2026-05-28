(ns cn.li.mcmod.loot.dsl
  "Loot injection DSL for declarative loot table augmentation."
  (:require [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.util.log :as log]))

(defn create-loot-injection-registry-runtime
  ([] (create-loot-injection-registry-runtime {}))
  ([{:keys [registry]}]
   {:cn.li.mcmod.loot.dsl/runtime ::loot-injection-registry-runtime
    :registry (or registry (registry-core/atom-registry {}))}))

(defonce ^:private installed-loot-injection-registry-runtime
  (create-loot-injection-registry-runtime))

(defonce ^:private loot-injection-registry-runtime-override* (atom nil))

(defn- loot-injection-registry-state []
  (:registry (or @loot-injection-registry-runtime-override* installed-loot-injection-registry-runtime)))

(defrecord LootInjectionSpec
  [id target-table item-id weight quality min-count max-count])

(defn create-loot-injection-spec
  [injection-id options]
  (map->LootInjectionSpec
    {:id injection-id
     :target-table (:target-table options)
     :item-id (:item-id options)
     :weight (int (or (:weight options) 1))
     :quality (int (or (:quality options) 0))
     :min-count (float (or (:min-count options) 1.0))
     :max-count (float (or (:max-count options) 1.0))}))

(defn register-loot-injection!
  [spec]
  (when-not (string? (:id spec))
    (throw (ex-info "Loot injection :id must be string" {:spec spec})))
  (when-not (string? (:target-table spec))
    (throw (ex-info "Loot injection :target-table must be string" {:spec spec})))
  (when-not (string? (:item-id spec))
    (throw (ex-info "Loot injection :item-id must be string" {:spec spec})))
  (log/info "Registering loot injection:" (:id spec) "table:" (:target-table spec) "item:" (:item-id spec))
  (registry-core/swap-state! (loot-injection-registry-state) #(assoc % (:id spec) spec))
  spec)

(defn list-loot-injections [] (keys (registry-core/snapshot (loot-injection-registry-state))))
(defn get-loot-injection [injection-id] (registry-core/lookup (loot-injection-registry-state) injection-id))

(defn get-loot-injections-for-table
  [target-table]
  (->> (registry-core/snapshot (loot-injection-registry-state))
       vals
       (filter (fn [spec] (= (:target-table spec) target-table)))))

(defmacro defloot
  [injection-name & options]
  (if (map? injection-name)
    (let [options-map injection-name
          injection-id (:id options-map)]
      (when-not (string? injection-id)
        (throw (ex-info "Map-form defloot requires string :id" {:form options-map})))
      `(register-loot-injection!
         (create-loot-injection-spec ~injection-id ~(dissoc options-map :id))))
    (let [options-map (if (and (= 1 (count options)) (map? (first options)))
                        (first options)
                        (apply hash-map options))
          injection-id (or (:id options-map) (name injection-name))]
      `(def ~injection-name
         (register-loot-injection!
           (create-loot-injection-spec ~injection-id ~(dissoc options-map :id)))))))
