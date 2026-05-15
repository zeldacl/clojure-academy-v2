(ns cn.li.ac.wireless.persistence.nbt-codec
  "NBT serialization and deserialization for wireless domain objects.
  
  Converts domain objects to/from NBT format for Minecraft persistence."
  (:require [cn.li.ac.wireless.domain.network :as domain-net]
            [cn.li.ac.wireless.domain.node :as domain-node]
            [cn.li.ac.wireless.domain.energy :as domain-energy]
            [cn.li.ac.wireless.core.vblock :as core-vb]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; NBT Versioning
;; ============================================================================

(def current-nbt-version 2)

;; ============================================================================
;; VBlock Serialization (SSOT delegated to wireless.core.vblock)
;; ============================================================================

(def vblock-to-nbt core-vb/vblock-to-nbt)
(def vblock-from-nbt core-vb/vblock-from-nbt)

;; ============================================================================
;; Energy Serialization
;; ============================================================================

(defn energy-to-nbt
  "Serialize energy container to NBT.
  
  Args:
    energy: EnergyContainer record
    
  Returns:
    NBT compound"
  [energy]
  (let [compound (nbt/create-nbt-compound)]
    (nbt/nbt-set-double! compound "current" (:current energy))
    (nbt/nbt-set-double! compound "max" (:max-capacity energy))
    (nbt/nbt-set-double! compound "transfer-rate" (:transfer-rate energy))
    (nbt/nbt-set-double! compound "efficiency" (:efficiency energy))
    compound))

(defn energy-from-nbt
  "Deserialize energy container from NBT.
  
  Args:
    compound: NBT compound
    
  Returns:
    EnergyContainer record"
  [compound]
  (domain-energy/->EnergyContainer
    (nbt/nbt-get-double compound "current")
    (nbt/nbt-get-double compound "max")
    (nbt/nbt-get-double compound "transfer-rate")
    (nbt/nbt-get-double compound "efficiency")
    (System/currentTimeMillis)))

;; ============================================================================
;; Network Serialization
;; ============================================================================

(defn network-to-nbt
  "Serialize network to NBT for world persistence.
  
  Args:
    network: Network record
    
  Returns:
    NBT compound"
  [network]
  (let [compound (nbt/create-nbt-compound)
        nodes-list (nbt/create-nbt-list)]
    
    ;; Metadata
    (nbt/nbt-set-int! compound "version" current-nbt-version)
    (nbt/nbt-set-string! compound "id" (name (:id network)))
    (nbt/nbt-set-string! compound "ssid" (:ssid network))
    (nbt/nbt-set-string! compound "password" (:password network))
    (nbt/nbt-set-long! compound "created-at" (:created-at network))
    (nbt/nbt-set-long! compound "last-updated" (:last-updated network))
    
    ;; Matrix position
    (nbt/nbt-set-tag! compound "matrix" (vblock-to-nbt (:matrix-vblock network)))
    
    ;; Energy
    (nbt/nbt-set-tag! compound "energy" (energy-to-nbt (:energy network)))
    
    ;; Nodes
    (doseq [node-vblock (:nodes network)]
      (nbt/nbt-append! nodes-list (vblock-to-nbt node-vblock)))
    (nbt/nbt-set-tag! compound "nodes" nodes-list)
    
    ;; Metadata
    ;; metadata skipped (no map->nbt-compound in platform API)
    
    compound))

(defn network-from-nbt
  "Deserialize network from NBT.
  
  Args:
    compound: NBT compound
    
  Returns:
    Network record or nil if invalid"
  [compound]
  (try
    (let [_version (nbt/nbt-get-int compound "version")
          id (keyword (nbt/nbt-get-string compound "id"))
          ssid (nbt/nbt-get-string compound "ssid")
          password (nbt/nbt-get-string compound "password")
          matrix-nbt (nbt/nbt-get-compound compound "matrix")
          matrix (vblock-from-nbt matrix-nbt :matrix true)
          energy-nbt (nbt/nbt-get-compound compound "energy")
          energy (energy-from-nbt energy-nbt)
          nodes-nbt (nbt/nbt-get-list compound "nodes")
          nodes-size (nbt/nbt-list-size nodes-nbt)
          nodes (vec (for [i (range nodes-size)]
                       (vblock-from-nbt (nbt/nbt-list-get-compound nodes-nbt i) :node false)))
          metadata {}]
      
      ;; Construct network
      (domain-net/->Network
        id
        ssid
        password
        matrix
        nodes
        energy
        (nbt/nbt-get-long compound "created-at")
        (nbt/nbt-get-long compound "last-updated")
        metadata))
    (catch Exception e
      (log/error (str "Failed to deserialize network from NBT: " (.getMessage e)))
      nil)))

;; ============================================================================
;; Batch Serialization
;; ============================================================================

(defn networks-to-nbt-list
  "Serialize multiple networks to NBT list.
  
  Args:
    networks: Vector of Network records
    
  Returns:
    NBT list"
  [networks]
  (let [list (nbt/create-nbt-list)]
    (doseq [net networks]
      (nbt/nbt-append! list (network-to-nbt net)))
    list))

(defn networks-from-nbt-list
  "Deserialize networks from NBT list.
  
  Args:
    nbt-list: NBT list
    
  Returns:
    Vector of Network records"
  [nbt-list]
  (let [size (nbt/nbt-list-size nbt-list)]
    (vec (for [i (range size)]
           (network-from-nbt (nbt/nbt-list-get-compound nbt-list i))))))

;; ============================================================================
;; Node Serialization (for individual nodes if needed)
;; ============================================================================

(defn node-to-nbt
  "Serialize wireless node to NBT.
  
  Args:
    node: WirelessNode record
    
  Returns:
    NBT compound"
  [node]
  (let [compound (nbt/create-nbt-compound)]
    (nbt/nbt-set-string! compound "id" (name (:id node)))
    (nbt/nbt-set-int! compound "type" (case (:node-type node)
                                          :transmitter 0
                                          :receiver 1
                                          :relay 2
                                          :matrix 3
                                          0))
    (nbt/nbt-set-tag! compound "vblock" (vblock-to-nbt (:vblock node)))
    (when (:network-id node)
      (nbt/nbt-set-string! compound "network-id" (name (:network-id node))))
    (nbt/nbt-set-int! compound "signal-strength" (:signal-strength node))
    (when (:connected-since node)
      (nbt/nbt-set-long! compound "connected-since" (:connected-since node)))
    compound))

(defn node-from-nbt
  "Deserialize wireless node from NBT.
  
  Args:
    compound: NBT compound
    
  Returns:
    WirelessNode record"
  [compound]
  (let [type-id (nbt/nbt-get-int compound "type")
        node-type (case type-id
                    0 :transmitter
                    1 :receiver
                    2 :relay
                    3 :matrix
                    :receiver)
        network-id-str (nbt/nbt-get-string compound "network-id")
        network-id (when (and network-id-str (not (empty? network-id-str)))
                     (keyword network-id-str))]
    (domain-node/->WirelessNode
      (keyword (nbt/nbt-get-string compound "id"))
      (vblock-from-nbt (nbt/nbt-get-compound compound "vblock"))
      node-type
      network-id
      (nbt/nbt-get-int compound "signal-strength")
      (nbt/nbt-get-long compound "connected-since")
      {})))

;; ============================================================================
;; Validation
;; ============================================================================

(defn validate-nbt-network
  "Validate NBT network data.
  
  Args:
    compound: NBT compound to validate
    
  Returns:
    {:valid boolean :errors [string]}"
  [compound]
  (let [errors (cond-> []
                 (not (nbt/nbt-has-key? compound "ssid"))
                 (conj "Missing SSID")
                 
                 (not (nbt/nbt-has-key? compound "matrix"))
                 (conj "Missing matrix position")
                 
                 (not (nbt/nbt-has-key? compound "energy"))
                 (conj "Missing energy data"))]
    {:valid (empty? errors) :errors errors}))
