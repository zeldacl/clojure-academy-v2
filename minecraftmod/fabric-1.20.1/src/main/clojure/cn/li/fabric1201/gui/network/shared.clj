(ns cn.li.fabric1201.gui.network.shared
  "Shared Fabric GUI network channel and envelope helpers."
  (:require [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.config :as mod-config]
            [cn.li.mcmod.util.log :as log])
  (:import [io.netty.buffer Unpooled]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources ResourceLocation]))

(def ^ResourceLocation c2s-channel
  (ResourceLocation. mod-config/mod-id "clj_rpc_c2s"))

(def ^ResourceLocation s2c-channel
  (ResourceLocation. mod-config/mod-id "clj_rpc_s2c"))

(defn make-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (.writeByteArray ^bytes (packet-base/encode-payload-bytes payload))))

(defn read-buf-map
  [^FriendlyByteBuf buf]
  (packet-base/decode-payload-bytes
    (.readByteArray buf)
    #(log/error "Failed to deserialize Fabric network payload:" (ex-message %))))
