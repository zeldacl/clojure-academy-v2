(ns cn.li.fabric262.gui.network.shared
  "Shared Fabric GUI network channel and envelope helpers."
  (:require [cn.li.mcbase.gui.network.packet :as packet-base]
            [cn.li.mcbase.runtime.sync-codec :as sync-codec]
            [cn.li.mcmod.config :as mod-config]
            [cn.li.mcmod.util.log :as log])
  (:import [io.netty.buffer Unpooled]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources Identifier]))

(def ^Identifier c2s-channel
  (Identifier/fromNamespaceAndPath mod-config/mod-id "clj_rpc_c2s"))

(def ^Identifier s2c-channel
  (Identifier/fromNamespaceAndPath mod-config/mod-id "clj_rpc_s2c"))

(def ^Identifier runtime-sync-s2c-channel
  (Identifier/fromNamespaceAndPath mod-config/mod-id "runtime_sync_v2"))

(defn make-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (.writeByteArray ^bytes (packet-base/encode-payload-bytes payload))))

(defn read-buf-map
  [^FriendlyByteBuf buf]
  (packet-base/decode-payload-bytes
    (.readByteArray buf)
    #(log/error "Failed to deserialize Fabric network payload:" (ex-message %))))

(defn make-runtime-sync-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (sync-codec/write-payload! payload)))

(defn read-runtime-sync-payload
  [^FriendlyByteBuf buf]
  (sync-codec/read-payload! buf))

(defn encode-map-bytes [payload]
  (packet-base/encode-payload-bytes payload))

(defn decode-map-bytes [^bytes payload]
  (packet-base/decode-payload-bytes payload
    #(log/error "Failed to deserialize Fabric typed payload:" (ex-message %))))

(defn- buffer->bytes [^FriendlyByteBuf buf]
  (let [length (.readableBytes buf)
        bytes (byte-array length)]
    (.readBytes buf bytes)
    bytes))

(defn encode-runtime-bytes [payload]
  (buffer->bytes (doto (FriendlyByteBuf. (Unpooled/buffer))
                   (sync-codec/write-payload! payload))))

(defn decode-runtime-bytes [^bytes payload]
  (sync-codec/read-payload! (FriendlyByteBuf. (Unpooled/wrappedBuffer payload))))
