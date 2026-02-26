(ns my-mod.forge1201.gui.network
  "Forge 1.20.1 GUI Network Packet System"
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.registry :as gui-registry]
            [my-mod.wireless.gui.matrix-sync :as sync]
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

;; Matrix State Sync Packet
(defrecord MatrixStatePacket [pos-x pos-y pos-z plate-count placer-name is-working core-level capacity bandwidth range])

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
;; Matrix State Sync Packets
;; =========================================================================

(defn encode-matrix-state
  [^MatrixStatePacket packet ^FriendlyByteBuf buffer]
  (.writeInt buffer (.intValue (:pos-x packet)))
  (.writeInt buffer (.intValue (:pos-y packet)))
  (.writeInt buffer (.intValue (:pos-z packet)))
  (.writeInt buffer (.intValue (:plate-count packet)))
  (.writeUtf buffer (:placer-name packet))
  (.writeBoolean buffer (:is-working packet))
  (.writeInt buffer (.intValue (:core-level packet)))
  (.writeLong buffer (.longValue (:capacity packet)))
  (.writeLong buffer (.longValue (:bandwidth packet)))
  (.writeDouble buffer (:range packet)))

(defn decode-matrix-state
  [^FriendlyByteBuf buffer]
  (let [pos-x (.readInt buffer)
        pos-y (.readInt buffer)
        pos-z (.readInt buffer)
        plate-count (.readInt buffer)
        placer-name (.readUtf buffer)
        is-working (.readBoolean buffer)
        core-level (.readInt buffer)
        capacity (.readLong buffer)
        bandwidth (.readLong buffer)
        range (.readDouble buffer)]
    (->MatrixStatePacket pos-x pos-y pos-z plate-count placer-name is-working core-level capacity bandwidth range)))

(defn handle-matrix-state
  [^MatrixStatePacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        ;; Update client-side container with new state
        (when-let [container @gui-registry/client-container]
          (when (= (:pos-x packet) (try (.getX (.getPos (:tile-entity container))) (catch Exception _ nil)))
            ;; Position matches - update atoms
            (reset! (:plate-count container) (:plate-count packet))
            (reset! (:core-level container) (:core-level packet))
            (reset! (:is-working container) (:is-working packet))
            (reset! (:capacity container) (:capacity packet))
            (reset! (:bandwidth container) (:bandwidth packet))
            (reset! (:range container) (:range packet))
            (log/debug "Updated matrix state on client")))))
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

    ;; Matrix State Sync
    (.messageBuilder channel MatrixStatePacket 2)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-matrix-state packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-matrix-state buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-matrix-state packet ctx-supplier))))
    (.add)

    (log/info "Forge 1.20.1 GUI packets registered (RPC + Matrix Sync)")))

(defn broadcast-matrix-state-forge
  "Broadcast matrix state to nearby players (Forge 1.20.1 implementation)"
  [world pos sync-data]
  (when @network-channel
    (let [matrix-state (->MatrixStatePacket
                         (:pos-x sync-data)
                         (:pos-y sync-data)
                         (:pos-z sync-data)
                         (:plate-count sync-data)
                         (:placer-name sync-data)
                         (:is-working sync-data)
                         (:core-level sync-data)
                         (:capacity sync-data)
                         (:bandwidth sync-data)
                         (:range sync-data))]
      ;; Send to all players tracking this chunk
      (.send @network-channel
             PacketDistributor/TRACKING_CHUNK
             (.getChunkAt world pos)
             matrix-state)
      (log/debug "Broadcast matrix state to chunk:" pos))))



(defn send-rpc-request-to-server
  [msg-id payload request-id]
  (when @network-channel
    (.sendToServer @network-channel (->RpcRequestPacket msg-id request-id payload))
    (log/debug "Sent RPC request to server:" msg-id)))

(defmethod rpc-client/send-request :forge-1.20.1
  [msg-id payload request-id]
  (send-rpc-request-to-server msg-id payload request-id))

(defn init! []
  (register-packets!)
  ;; Register Forge sync implementation for matrix state
  (sync/register-sync-impl! :forge-1.20.1 broadcast-matrix-state-forge)
  (log/info "Forge 1.20.1 GUI network initialized"))
