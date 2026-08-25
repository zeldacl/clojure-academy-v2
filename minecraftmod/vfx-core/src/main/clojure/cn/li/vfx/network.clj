(ns cn.li.vfx.network
  "Typed VFX protocol adapter.  Minecraft loader handlers call this after
   decoding the physical channel; no Clojure map is retained as a wire ABI.")

(import '(java.util UUID))
(import '(cn.li.mcmod.runtime.vfx VfxPacketKind VfxLifecyclePacket VfxCatalogHello VfxCatalogAck VfxPacket VfxChannels))

(def ^:const server-to-client-channel VfxChannels/SERVER_TO_CLIENT)
(def ^:const client-to-server-channel VfxChannels/CLIENT_TO_SERVER)
(def ^:const protocol-version 1)
(def ^:const max-packet-bytes 32768)
(def ^:const max-parameters 64)
(def s2c-kinds #{VfxPacketKind/CATALOG_HELLO VfxPacketKind/SPAWN VfxPacketKind/DELTA
                 VfxPacketKind/EVENT VfxPacketKind/DESTROY VfxPacketKind/RELEASE
                 VfxPacketKind/SNAPSHOT VfxPacketKind/OWNER_RESET})
(def c2s-kinds #{VfxPacketKind/CATALOG_ACK})

(defn- utf8-bytes ^bytes [^String value] (.getBytes value "UTF-8"))

(defn- uuid [value]
  (cond
    (instance? UUID value) value
    (string? value) (try (UUID/fromString value)
                         (catch IllegalArgumentException _ (UUID/nameUUIDFromBytes (utf8-bytes value))))
    :else (UUID/nameUUIDFromBytes (utf8-bytes (pr-str value)))))

(defn operation-kind [op]
  (case op
    :spawn VfxPacketKind/SPAWN
    :snapshot VfxPacketKind/SNAPSHOT
    :update VfxPacketKind/DELTA
    :trigger VfxPacketKind/EVENT
    :destroy VfxPacketKind/DESTROY
    :release VfxPacketKind/RELEASE
    :clear-owner VfxPacketKind/OWNER_RESET
    (throw (ex-info "unknown VFX network operation" {:op op}))))

(defn signal->packet
  "Build the fixed identity packet. Parameter/event payloads are encoded by
   the loader-specific typed codec, never by an arbitrary Clojure map."
  [{:keys [op world-epoch instance-id asset-id state-seq event-seq owner
           start-server-tick seed]}]
  (VfxLifecyclePacket. (operation-kind op)
                       (long (or world-epoch 0))
                       (long (or instance-id 0))
                       (int (or asset-id 0))
                       (long (or state-seq 0))
                       (long (or event-seq 0))
                       (uuid owner)
                       (long (or start-server-tick 0))
                       (long (or seed 0))))

(defn packet->signal [^VfxPacket packet]
  (when-not (instance? VfxLifecyclePacket packet)
    (throw (ex-info "packet does not carry a VFX lifecycle identity" {:packet packet})))
  (let [^VfxLifecyclePacket p packet
        op (case (.kind p)
             VfxPacketKind/SPAWN :spawn
             VfxPacketKind/SNAPSHOT :snapshot
             VfxPacketKind/DELTA :update
             VfxPacketKind/EVENT :trigger
             VfxPacketKind/DESTROY :destroy
             VfxPacketKind/RELEASE :release
             VfxPacketKind/OWNER_RESET :clear-owner
             (throw (ex-info "packet kind is not a lifecycle signal" {:kind (.kind p)})))]
    {:op op :world-epoch (.worldEpoch p) :instance-id (.instanceId p)
     :asset-id (.assetId p) :state-seq (.stateSequence p)
     :event-seq (.eventSequence p) :owner (.owner p)
     :start-server-tick (.startServerTick p) :seed (.seed p)}))

(defn catalog-hello [catalog-hash]
  (VfxCatalogHello. protocol-version (long catalog-hash) max-packet-bytes max-parameters))

(defn catalog-ack [catalog-hash accepted reason]
  (VfxCatalogAck. protocol-version (long catalog-hash) (boolean accepted) (str (or reason ""))))

(defn validate-direction! [direction ^VfxPacket packet]
  (let [allowed (case direction :s2c s2c-kinds :c2s c2s-kinds nil)]
    (when-not (and allowed (contains? allowed (.kind packet)))
      (throw (ex-info "VFX packet direction rejected" {:direction direction :kind (.kind packet)})))
    packet))