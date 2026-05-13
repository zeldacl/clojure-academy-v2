(ns cn.li.ac.wireless.data.network-nbt
  "NBT serialization/deserialization for WirelessNet.
  Isolated so that the data layer can be required without pulling in NBT utilities."
  (:require [cn.li.ac.wireless.core.vblock :as vb]
            [cn.li.ac.wireless.data.network :as network]
            [cn.li.mcmod.platform.nbt :as nbt]))

(defn network-to-nbt
  "Serialize network to NBT."
  [net]
  (let [nbt-compound (nbt/create-nbt-compound)
        list-obj (nbt/create-nbt-list)
        world (:world (:world-data net))]
    (nbt/nbt-set-tag! nbt-compound "matrix" (vb/vblock-to-nbt (:matrix net)))
    (nbt/nbt-set-string! nbt-compound "ssid" @(:ssid net))
    (nbt/nbt-set-string! nbt-compound "password" @(:password net))
    (nbt/nbt-set-double! nbt-compound "buffer" @(:buffer net))
    (doseq [node-vb @(:nodes net)]
      (when (or (not (vb/is-chunk-loaded? node-vb world))
                (vb/vblock-get node-vb world))
        (nbt/nbt-append! list-obj (vb/vblock-to-nbt node-vb))))
    (nbt/nbt-set-tag! nbt-compound "list" list-obj)
    nbt-compound))

(defn network-from-nbt
  "Deserialize network from NBT."
  [world-data nbt-compound]
  (let [matrix (vb/vblock-from-nbt (nbt/nbt-get-compound nbt-compound "matrix"))
        ssid (nbt/nbt-get-string nbt-compound "ssid")
        password (nbt/nbt-get-string nbt-compound "password")
        buffer (nbt/nbt-get-double nbt-compound "buffer")
        list-obj (nbt/nbt-get-list nbt-compound "list")
        size (nbt/nbt-list-size list-obj)
        nodes (vec (for [i (range size)]
                     (vb/vblock-from-nbt (nbt/nbt-list-get-compound list-obj i))))
        net (network/create-wireless-net world-data matrix ssid password)]
    (reset! (:buffer net) buffer)
    (reset! (:nodes net) nodes)
    net))
