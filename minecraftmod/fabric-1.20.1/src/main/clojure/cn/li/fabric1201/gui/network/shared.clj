(ns cn.li.fabric1201.gui.network.shared
  "Shared Fabric GUI network channel and envelope helpers."
  (:require [cn.li.mc1201.gui.network.packet :as packet-base]
            [cn.li.mcmod.config :as mod-config]
            [cn.li.mcmod.util.log :as log])
  (:import [io.netty.buffer Unpooled]
           [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources ResourceLocation]))

(def c2s-channel
  (ResourceLocation. mod-config/*mod-id* "clj_rpc_c2s"))

(def s2c-channel
  (ResourceLocation. mod-config/*mod-id* "clj_rpc_s2c"))

(defn jproxy
  [^Class iface invoke-fn]
  (java.lang.reflect.Proxy/newProxyInstance
    (.getClassLoader iface)
    (into-array Class [iface])
    (reify java.lang.reflect.InvocationHandler
      (invoke [_ _ method args]
        (let [^java.lang.reflect.Method method method]
          (invoke-fn (.getName method) args))))))

(defn make-buf
  [payload]
  (doto (FriendlyByteBuf. (Unpooled/buffer))
    (.writeUtf (packet-base/encode-payload payload))))

(defn read-buf-map
  [^FriendlyByteBuf buf]
  (let [raw (try
              (.readUtf buf 1048576)
              (catch Throwable _
                (.readUtf buf)))]
    (packet-base/decode-payload
      raw
      #(log/error "Failed to deserialize Fabric network payload:" (ex-message %)))))
