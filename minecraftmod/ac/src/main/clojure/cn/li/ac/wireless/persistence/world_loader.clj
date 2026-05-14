(ns cn.li.ac.wireless.persistence.world-loader
  "World data persistence for wireless networks.
  
  Handles loading/saving networks to Minecraft world data
  and managing the global network registry."
  (:require [cn.li.ac.wireless.persistence.nbt-codec :as codec]
            [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.foundation.vblock :as vb]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; World Data Constants
;; ============================================================================

(def wireless-networks-key "WirelessNetworks")
(def world-version-key "WirelessVersion")
(def current-world-version 1)

;; ============================================================================
;; Load from World
;; ============================================================================

(defn load-networks-from-world
  "Load all wireless networks from world NBT data.
  
  Args:
    world-compound: Root NBT compound from world save
    
  Returns:
    {:networks [network1 network2 ...]
     :version int
     :valid boolean}"
  [world-compound]
  (try
    (let [version (or (nbt/nbt-get-int world-compound world-version-key) current-world-version)
          networks-nbt (nbt/nbt-get-list world-compound wireless-networks-key)
          networks (if networks-nbt
                    (codec/networks-from-nbt-list networks-nbt)
                    [])]
      (log/info (str "Loaded " (count networks) " wireless networks from world"))
      {:networks networks
       :version version
       :valid true})
    (catch Exception e
      (log/warn (str "Failed to load wireless networks from world: " (.getMessage e)))
      {:networks []
       :version current-world-version
       :valid false})))

(defn load-network-by-id
  "Load specific network from world data.
  
  Args:
    world-compound: Root NBT compound
    network-id: keyword network ID
    
  Returns:
    Network or nil"
  [world-compound network-id]
  (try
    (let [networks-nbt (nbt/nbt-get-list world-compound wireless-networks-key)
          size (nbt/nbt-list-size networks-nbt)]
      (some (fn [i]
              (let [net-nbt (nbt/nbt-list-get-compound networks-nbt i)
                    net (codec/network-from-nbt net-nbt)]
                (when (and net (= network-id (:id net)))
                  net)))
            (range size)))
    (catch Exception e
      (log/warn (str "Failed to load network " network-id ": " (.getMessage e)))
      nil)))

;; ============================================================================
;; Save to World
;; ============================================================================

(defn save-networks-to-world
  "Save wireless networks to world NBT data.
  
  Args:
    world-compound: Root NBT compound to modify
    networks: Vector of Network records
    
  Returns:
    Updated world-compound"
  [world-compound networks]
  (try
    (let [networks-list (codec/networks-to-nbt-list networks)]
      (nbt/nbt-set-int! world-compound world-version-key current-world-version)
      (nbt/nbt-set-tag! world-compound wireless-networks-key networks-list)
      (log/info (str "Saved " (count networks) " wireless networks to world"))
      world-compound)
    (catch Exception e
      (log/error (str "Failed to save wireless networks: " (.getMessage e)))
      world-compound)))

(defn save-network-to-world
  "Save or update single network in world.
  
  Args:
    world-compound: Root NBT compound
    network: Network to save
    
  Returns:
    Updated world-compound"
  [world-compound network]
  (try
    (let [existing (:networks (load-networks-from-world world-compound))
          index (some (fn [[i n]] (when (= (:id network) (:id n)) i))
                      (map-indexed vector existing))
          updated (if index
                    (do
                      (log/info (str "Updated network " (:id network)))
                      (assoc (vec existing) index network))
                    (do
                      (log/info (str "Added new network " (:id network)))
                      (conj (vec existing) network)))
          networks-list (codec/networks-to-nbt-list updated)]
      (nbt/nbt-set-int! world-compound world-version-key current-world-version)
      (nbt/nbt-set-tag! world-compound wireless-networks-key networks-list)
      world-compound)
    (catch Exception e
      (log/error (str "Failed to save network " (:id network) ": " (.getMessage e)))
      world-compound)))

(defn remove-network-from-world
  "Remove network from world NBT data.
  
  Args:
    world-compound: Root NBT compound
    network-id: keyword network ID
    
  Returns:
    Updated world-compound"
  [world-compound network-id]
  (try
    (let [existing (:networks (load-networks-from-world world-compound))
          has-network? (some #(= network-id (:id %)) existing)]
      (when has-network?
        (let [filtered (codec/networks-to-nbt-list
                         (filterv #(not= network-id (:id %)) existing))]
          (nbt/nbt-set-int! world-compound world-version-key current-world-version)
          (nbt/nbt-set-tag! world-compound wireless-networks-key filtered)
          (log/info (str "Removed network " network-id))))
      
      world-compound)
    (catch Exception e
      (log/error (str "Failed to remove network " network-id ": " (.getMessage e)))
      world-compound)))

;; ============================================================================
;; Batch Operations
;; ============================================================================

(defn load-and-index-networks
  "Load networks and build spatial index.
  
  Args:
    world-compound: Root NBT compound
    
  Returns:
    {:networks [network1 network2 ...]
     :spatial-index {chunk-key [network-ids]}
     :valid boolean}"
  [world-compound]
  (let [{:keys [networks valid]} (load-networks-from-world world-compound)]
    (if valid
      (let [spatial-index (reduce (fn [index net]
                                    (let [chunk-key (vb/vblock->chunk-key (:matrix-vblock net))]
                                      (update index chunk-key
                                             (fn [ids] (conj (or ids []) (:id net))))))
                                  {}
                                  networks)]
        {:networks networks
         :spatial-index spatial-index
         :valid true})
      {:networks []
       :spatial-index {}
       :valid false})))

(defn sync-networks-to-world!
  "Synchronize all networks from registry to world.
  
  Use after bulk operations or migrations.
  
  Args:
    world-compound: Root NBT compound to modify
    registry-atom: Atom containing networks
    
  Returns:
    Updated world-compound"
  [world-compound registry-atom]
  (let [networks (vals @registry-atom)]
    (save-networks-to-world world-compound networks)))

;; ============================================================================
;; Migration and Validation
;; ============================================================================

(defn validate-world-networks
  "Validate all networks in world.
  
  Args:
    world-compound: Root NBT compound
    
  Returns:
    {:valid boolean
     :networks-count int
     :invalid-networks [ids]
     :errors [string]}"
  [world-compound]
  (try
    (let [networks-nbt (nbt/nbt-get-list world-compound wireless-networks-key)
          size (nbt/nbt-list-size networks-nbt)
          results (vec (for [i (range size)]
                         (let [net-nbt (nbt/nbt-list-get-compound networks-nbt i)
                               net (codec/network-from-nbt net-nbt)]
                           (if net
                             {:valid true :network net}
                             {:valid false :id (str "unknown-" i)}))))]
      
      (let [invalid (filterv (fn [r] (not (:valid r))) results)
            error-messages (mapv (fn [r] (str "Invalid network: " (:id r))) invalid)]
        {:valid (empty? invalid)
         :networks-count (count results)
         :invalid-networks (mapv :id invalid)
         :errors error-messages}))
    (catch Exception e
      {:valid false
       :networks-count 0
       :invalid-networks []
       :errors [(str "Validation error: " (.getMessage e))]})))

(defn repair-world-networks
  "Remove invalid networks from world.
  
  Args:
    world-compound: Root NBT compound
    
  Returns:
    {:repaired-count int
     :valid-count int}"
  [world-compound]
  (try
    (let [networks-nbt (nbt/nbt-get-list world-compound wireless-networks-key)
          size (nbt/nbt-list-size networks-nbt)
          valid-networks (vec (for [i (range size)
                                    :let [net-nbt (nbt/nbt-list-get-compound networks-nbt i)
                                          net (codec/network-from-nbt net-nbt)]
                                    :when net]
                                net))]
      
      (let [repaired (- size (count valid-networks))]
        (save-networks-to-world world-compound valid-networks)
        (log/warn (str "Repaired world: removed " repaired " invalid networks"))
        {:repaired-count repaired
         :valid-count (count valid-networks)}))
    (catch Exception e
      (log/error (str "Failed to repair world networks: " (.getMessage e)))
      {:repaired-count 0
       :valid-count 0})))

;; ============================================================================
;; Backup and Restore
;; ============================================================================

(defn backup-world-networks
  "Create backup of all networks.
  
  Args:
    world-compound: Root NBT compound
    
  Returns:
    Backup NBT compound"
  [world-compound]
  (try
    (let [backup (nbt/create-nbt-compound)
          networks-nbt (nbt/nbt-get-list world-compound wireless-networks-key)]
      (when networks-nbt
        (let [networks-copy (nbt/create-nbt-list)]
          (doseq [i (range (nbt/nbt-list-size networks-nbt))]
            (nbt/nbt-append! networks-copy
                            (nbt/nbt-list-get-compound networks-nbt i)))
          (nbt/nbt-set-tag! backup wireless-networks-key networks-copy)))
      (nbt/nbt-set-long! backup "backup-time" (System/currentTimeMillis))
      backup)
    (catch Exception e
      (log/error (str "Failed to backup networks: " (.getMessage e)))
      nil)))

(defn restore-world-networks
  "Restore networks from backup.
  
  Args:
    world-compound: Root NBT compound (will be modified)
    backup: Backup NBT compound
    
  Returns:
    Updated world-compound"
  [world-compound backup]
  (try
    (if-let [networks-nbt (nbt/nbt-get-list backup wireless-networks-key)]
      (do
        (nbt/nbt-set-tag! world-compound wireless-networks-key networks-nbt)
        (log/info "Restored networks from backup")
        world-compound)
      (do
        (log/warn "Backup has no networks")
        world-compound))
    (catch Exception e
      (log/error (str "Failed to restore networks: " (.getMessage e)))
      world-compound)))