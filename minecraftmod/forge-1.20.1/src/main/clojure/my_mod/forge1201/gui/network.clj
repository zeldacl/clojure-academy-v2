(ns my-mod.forge1201.gui.network
  "Forge 1.20.1 GUI Network Packet System"
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.network.client :as rpc-client]
            [my-mod.network.server :as rpc-server]
            [my-mod.util.log :as log])
  (:import [net.minecraft.network FriendlyByteBuf]
           [net.minecraft.resources ResourceLocation]
           [net.minecraftforge.network NetworkRegistry SimpleChannel NetworkEvent$Context PacketDistributor]
           [java.util.function Supplier BiConsumer]))

(def protocol-version "1")

(defonce network-channel (atom nil))

(defn create-channel []
  (NetworkRegistry/newSimpleChannel
    (ResourceLocation. "my_mod" "gui_channel")
    (constantly protocol-version)
    (fn [version] (= version protocol-version))
    (fn [version] (= version protocol-version))))

;; =========================================================================
;; Shared Map Encoding Helpers
;; =========================================================================

(defn- write-value
  [^FriendlyByteBuf buffer value]
  (cond
    (integer? value) (do (.writeByte buffer 0) (.writeInt buffer value))
    (float? value) (do (.writeByte buffer 1) (.writeFloat buffer value))
    (string? value) (do (.writeByte buffer 2) (.writeUtf buffer value))
    (boolean? value) (do (.writeByte buffer 3) (.writeBoolean buffer value))
    (nil? value) (do (.writeByte buffer 4))
    :else (do (.writeByte buffer 5) (.writeUtf buffer (pr-str value)))))

(defn- read-value
  [^FriendlyByteBuf buffer]
  (let [type-id (.readByte buffer)]
    (case type-id
      0 (.readInt buffer)
      1 (.readFloat buffer)
      2 (.readUtf buffer)
      3 (.readBoolean buffer)
      4 nil
      5 (.readUtf buffer)
      nil)))

(defn- write-data-map
  [^FriendlyByteBuf buffer data]
  (.writeInt buffer (count data))
  (doseq [[k v] data]
    (.writeUtf buffer (name k))
    (write-value buffer v)))

(defn- read-data-map
  [^FriendlyByteBuf buffer]
  (let [count (.readInt buffer)]
    (into {}
          (for [_ (range count)]
            (let [key (keyword (.readUtf buffer))
                  value (read-value buffer)]
              [key value])))))

;; =========================================================================
;; RPC: Request/Response
;; =========================================================================

(defrecord RpcRequestPacket [msg-id request-id payload])
(defrecord RpcResponsePacket [request-id payload])

(defn encode-rpc-request [^RpcRequestPacket packet ^FriendlyByteBuf buffer]
  (.writeUtf buffer (:msg-id packet))
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-request [^FriendlyByteBuf buffer]
  (let [msg-id (.readUtf buffer)
        request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcRequestPacket msg-id request-id payload)))

(defn encode-rpc-response [^RpcResponsePacket packet ^FriendlyByteBuf buffer]
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-response [^FriendlyByteBuf buffer]
  (let [request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcResponsePacket request-id payload)))

(defn handle-rpc-request
  [^RpcRequestPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)
        player (.getSender ctx)]
    (.enqueueWork ctx
      (fn []
        (rpc-server/handle-request
          (:msg-id packet)
          (:request-id packet)
          (:payload packet)
          player
          (fn [request-id response]
            (when @network-channel
              (.send @network-channel
                     PacketDistributor/PLAYER
                     player
                     (->RpcResponsePacket request-id response)))))))
    (.setPacketHandled ctx true)))

(defn handle-rpc-response
  [^RpcResponsePacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        (rpc-client/handle-response (:request-id packet) (:payload packet))))
    (.setPacketHandled ctx true)))

;; =========================================================================
;; Registration
;; =========================================================================

(defn register-packets! []
  (log/info "Registering GUI network packets (Forge 1.20.1)")
  (let [channel (create-channel)]
    (reset! network-channel channel)

    ;; RPC Request
    (.messageBuilder channel RpcRequestPacket 0)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-rpc-request packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-rpc-request buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-rpc-request packet ctx-supplier))))
    (.add)

    ;; RPC Response
    (.messageBuilder channel RpcResponsePacket 1)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-rpc-response packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-rpc-response buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-rpc-response packet ctx-supplier))))
    (.add)

    (log/info "Forge 1.20.1 RPC packets registered")))

(defn send-rpc-request-to-server
  [msg-id payload request-id]
  (when @network-channel
    (.sendToServer @network-channel (->RpcRequestPacket msg-id request-id payload))
    (log/debug "Sent RPC request to server:" msg-id)))

(defmethod rpc-client/send-request :forge-1.20.1
  [msg-id payload request-id]
  (send-rpc-request-to-server msg-id payload request-id))

(defn init! []
  (register-packets!))
