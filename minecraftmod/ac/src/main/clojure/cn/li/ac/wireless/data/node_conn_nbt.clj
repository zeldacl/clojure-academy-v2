(ns cn.li.ac.wireless.data.node-conn-nbt
  "NBT serialization/deserialization for NodeConn.
  Isolated so that the data layer can be required without pulling in NBT utilities."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.node-conn :as node-conn]
            [cn.li.mcmod.platform.nbt :as nbt]))

(defn node-connection-to-nbt
  "Serialize node connection to NBT."
  [conn]
  (let [nbt-compound (nbt/create-nbt-compound)
        receivers-list (nbt/create-nbt-list)
        generators-list (nbt/create-nbt-list)
        world (:world (:world-data conn))]
    (nbt/nbt-set-tag! nbt-compound "node" (vb/vblock-to-nbt (:node conn)))
    (doseq [receiver-vb @(:receivers conn)]
      (when (or (not (vb/is-chunk-loaded? receiver-vb world))
                (vb/vblock-get receiver-vb world))
        (nbt/nbt-append! receivers-list (vb/vblock-to-nbt receiver-vb))))
    (doseq [generator-vb @(:generators conn)]
      (when (or (not (vb/is-chunk-loaded? generator-vb world))
                (vb/vblock-get generator-vb world))
        (nbt/nbt-append! generators-list (vb/vblock-to-nbt generator-vb))))
    (nbt/nbt-set-tag! nbt-compound "receivers" receivers-list)
    (nbt/nbt-set-tag! nbt-compound "generators" generators-list)
    nbt-compound))

(defn node-connection-from-nbt
  "Deserialize node connection from NBT."
  [world-data nbt-compound]
  (let [node-vb (vb/vblock-from-nbt (nbt/nbt-get-compound nbt-compound "node"))
        receivers-list (nbt/nbt-get-list nbt-compound "receivers")
        generators-list (nbt/nbt-get-list nbt-compound "generators")
        receivers-size (nbt/nbt-list-size receivers-list)
        generators-size (nbt/nbt-list-size generators-list)
        receivers (vec (for [i (range receivers-size)]
                         (vb/vblock-from-nbt (nbt/nbt-list-get-compound receivers-list i))))
        generators (vec (for [i (range generators-size)]
                          (vb/vblock-from-nbt (nbt/nbt-list-get-compound generators-list i))))
        conn (node-conn/create-node-conn world-data node-vb)]
    (reset! (:receivers conn) receivers)
    (reset! (:generators conn) generators)
    conn))
