(ns cn.li.ac.wireless.data.persistence
  "Canonical NBT persistence for the current wireless runtime state."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.vblock-codec :as vblock-codec]
            [cn.li.ac.wireless.data.network-state :as network-state]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.ac.wireless.data.world-registry :as world-registry]
            [cn.li.ac.wireless.service.commands :as commands]
            [cn.li.mcmod.platform.nbt :as nbt]
            [cn.li.mcmod.util.log :as log]))

(def schema-version 1)

(defn network-to-nbt
  [network]
  (let [compound (nbt/create-nbt-compound)]
    (nbt/nbt-set-tag! compound "matrix" (vblock-codec/vblock-to-nbt (:matrix network)))
    (nbt/nbt-set-string! compound "ssid" (network-state/get-ssid network))
    (nbt/nbt-set-string! compound "password" (network-state/get-password network))
    (nbt/nbt-set-tag! compound "nodes" (vblock-codec/vblocks-to-nbt-list (network-state/get-nodes network)))
    (nbt/nbt-set-double! compound "buffer" (double (network-state/get-buffer network)))
    (nbt/nbt-set-int! compound "updateCounter" (int (network-state/get-update-counter network)))
    (nbt/nbt-set-boolean! compound "disposed" (network-state/is-disposed? network))
    compound))

(defn network-from-nbt
  [world-data compound]
  (let [matrix-vb (vb/vblock-from-nbt (nbt/nbt-get-compound compound "matrix") :matrix true)
        network (network-state/create-wireless-net
                  world-data
                  matrix-vb
                  (nbt/nbt-get-string compound "ssid")
                  (nbt/nbt-get-string compound "password"))]
    (-> network
        (network-state/set-state-value!
          :nodes
          (vec (vblock-codec/nbt-list->vblocks
                 (nbt/nbt-get-list compound "nodes")
                 :node
                 false
                 vb/from-foundation)))
        (network-state/set-state-value! :buffer (nbt/nbt-get-double compound "buffer"))
        (network-state/set-state-value! :update-counter (nbt/nbt-get-int compound "updateCounter"))
        (network-state/set-state-value! :disposed (nbt/nbt-get-boolean compound "disposed")))))

(defn connection-to-nbt
  [conn world]
  (let [compound (node-conn/node-connection-to-nbt conn world)]
    (nbt/nbt-set-boolean! compound "disposed" (node-conn/is-disposed? conn))
    compound))

(defn connection-from-nbt
  [world-data compound]
  (let [conn (node-conn/node-connection-from-nbt world-data compound)]
    (node-conn/set-disposed! conn (nbt/nbt-get-boolean compound "disposed"))
    conn))

(defn world-data-to-nbt
  [world-data world]
  (let [compound (nbt/create-nbt-compound)
        networks-list (nbt/create-nbt-list)
        connections-list (nbt/create-nbt-list)]
    (nbt/nbt-set-int! compound "schemaVersion" schema-version)
    (doseq [network (world-registry/networks world-data)]
      (when-not (network-state/is-disposed? network)
        (nbt/nbt-append! networks-list (network-to-nbt network))))
    (doseq [conn (world-registry/connections world-data)]
      (when-not (node-conn/is-disposed? conn)
        (nbt/nbt-append! connections-list (connection-to-nbt conn world))))
    (nbt/nbt-set-tag! compound "networks" networks-list)
    (nbt/nbt-set-tag! compound "connections" connections-list)
    compound))

(defn world-data-from-nbt
  [world compound]
  (let [world-data (world-registry/create-world-data world)
        networks-list (nbt/nbt-get-list compound "networks")
        connections-list (nbt/nbt-get-list compound "connections")
        networks-size (if networks-list (nbt/nbt-list-size networks-list) 0)
        connections-size (if connections-list (nbt/nbt-list-size connections-list) 0)]
    (log/info "[world-data-from-nbt] compound present:" (some? compound)
              "networks-list:" (some? networks-list) "size:" networks-size
              "connections-list:" (some? connections-list) "size:" connections-size)
    (doseq [index (range networks-size)]
      (when-let [network-compound (nbt/nbt-list-get-compound networks-list index)]
        (try
          (commands/rebuild-network-indexes!
            world-data
            (network-from-nbt world-data network-compound))
          (catch Exception e
            (log/warn "Skipping invalid wireless network NBT entry" index ":" (ex-message e))))))
    (doseq [index (range connections-size)]
      (when-let [connection-compound (nbt/nbt-list-get-compound connections-list index)]
        (try
          (commands/rebuild-connection-indexes!
            world-data
            (connection-from-nbt world-data connection-compound))
          (catch Exception e
            (log/warn "Skipping invalid wireless connection NBT entry" index ":" (ex-message e))))))
    world-data))