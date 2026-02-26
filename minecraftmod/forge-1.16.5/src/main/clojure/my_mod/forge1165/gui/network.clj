(ns my-mod.forge1165.gui.network
  "Forge 1.16.5 GUI Network Packet System
  
  Platform-agnostic design: Uses generic GUI handlers,
  eliminating hardcoded game concepts."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.network.client :as rpc-client]
            [my-mod.network.server :as rpc-server]
            [my-mod.util.log :as log])
  (:import [net.minecraft.network PacketBuffer]
           [net.minecraft.entity.player ServerPlayerEntity]
           [net.minecraft.util ResourceLocation]
           [net.minecraftforge.fml.network NetworkRegistry SimpleChannel NetworkEvent$Context]
           [java.util.function Supplier BiConsumer]))

;; ============================================================================
;; Network Channel
;; ============================================================================

(def PROTOCOL_VERSION "1")

(defonce network-channel
  (atom nil))

(defn create-channel []
  "Create the network channel for GUI packets"
  (NetworkRegistry/newSimpleChannel
    (ResourceLocation. "my_mod" "gui_channel")
    (constantly PROTOCOL_VERSION)
    (fn [version] (= version PROTOCOL_VERSION))
    (fn [version] (= version PROTOCOL_VERSION))))

;; =========================================================================
;; Shared Map Encoding Helpers
;; =========================================================================

(defn- write-value
  "Write a single value with type tag"
  [^PacketBuffer buffer value]
  (cond
    (integer? value) (do (.writeByte buffer 0) (.writeInt buffer value))
    (float? value) (do (.writeByte buffer 1) (.writeFloat buffer value))
    (string? value) (do (.writeByte buffer 2) (.writeUtf buffer value))
    (boolean? value) (do (.writeByte buffer 3) (.writeBoolean buffer value))
    (nil? value) (do (.writeByte buffer 4))
    :else (do (.writeByte buffer 5) (.writeUtf buffer (pr-str value)))))

(defn- read-value
  "Read a single value with type tag"
  [^PacketBuffer buffer]
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
  "Write a map of keyword->primitive values"
  [^PacketBuffer buffer data]
  (.writeInt buffer (count data))
  (doseq [[k v] data]
    (.writeUtf buffer (name k))
    (write-value buffer v)))

(defn- read-data-map
  "Read a map of keyword->primitive values"
  [^PacketBuffer buffer]
  (let [count (.readInt buffer)]
    (into {}
          (for [_ (range count)]
            (let [key (keyword (.readUtf buffer))
                  value (read-value buffer)]
              [key value])))))

;; ============================================================================
;; Packet: Button Click
;; ============================================================================

(defrecord ButtonClickPacket [gui-id button-id])

(defn encode-button-click
  "Encode button click packet to buffer"
  [^ButtonClickPacket packet ^PacketBuffer buffer]
  (.writeInt buffer (:gui-id packet))
  (.writeInt buffer (:button-id packet)))

(defn decode-button-click
  "Decode button click packet from buffer"
  [^PacketBuffer buffer]
  (let [gui-id (.readInt buffer)
        button-id (.readInt buffer)]
    (->ButtonClickPacket gui-id button-id)))

(defn handle-button-click
  "Handle button click packet on server"
  [^ButtonClickPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)
        player (.getSender ctx)]
    
    (.enqueueWork ctx
      (fn []
        (log/info "Handling button click:" (:button-id packet) 
                  "for GUI:" (:gui-id packet))
        
        ;; Get active container for player and dispatch using protocol
        (when-let [container (gui/get-player-container player)]
          (gui/safe-handle-button-click! container (:button-id packet) player))))
    
    (.setPacketHandled ctx true)))

;; ============================================================================
;; Packet: Text Input
;; ============================================================================

(defrecord TextInputPacket [gui-id field-id text])

(defn encode-text-input
  "Encode text input packet to buffer"
  [^TextInputPacket packet ^PacketBuffer buffer]
  (.writeInt buffer (:gui-id packet))
  (.writeInt buffer (:field-id packet))
  (.writeUtf buffer (:text packet)))

(defn decode-text-input
  "Decode text input packet from buffer"
  [^PacketBuffer buffer]
  (let [gui-id (.readInt buffer)
        field-id (.readInt buffer)
        text (.readUtf buffer)]
    (->TextInputPacket gui-id field-id text)))

(defn handle-text-input
  "Handle text input packet on server"
  [^TextInputPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)
        player (.getSender ctx)]
    
    (.enqueueWork ctx
      (fn []
        (log/info "Handling text input:" (:text packet) 
                  "for field:" (:field-id packet) 
                  "in GUI:" (:gui-id packet))
        
        ;; Get active container for player and dispatch using protocol
        (when-let [container (gui/get-player-container player)]
          (gui/safe-handle-text-input! container (:field-id packet) (:text packet) player))))
    
    (.setPacketHandled ctx true)))

;; ============================================================================
;; Packet: Sync Data (Server -> Client)
;; ============================================================================

(defrecord SyncDataPacket [gui-id data])

(defn encode-sync-data
  "Encode sync data packet to buffer"
  [^SyncDataPacket packet ^PacketBuffer buffer]
  (.writeInt buffer (:gui-id packet))
  (write-data-map buffer (:data packet)))

(defn decode-sync-data
  "Decode sync data packet from buffer"
  [^PacketBuffer buffer]
  (let [gui-id (.readInt buffer)
        data (read-data-map buffer)]
    (->SyncDataPacket gui-id data)))

;; =========================================================================
;; RPC: Request/Response
;; =========================================================================

(defrecord RpcRequestPacket [msg-id request-id payload])
(defrecord RpcResponsePacket [request-id payload])

(defn encode-rpc-request
  "Encode RPC request to buffer"
  [^RpcRequestPacket packet ^PacketBuffer buffer]
  (.writeUtf buffer (:msg-id packet))
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-request
  "Decode RPC request from buffer"
  [^PacketBuffer buffer]
  (let [msg-id (.readUtf buffer)
        request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcRequestPacket msg-id request-id payload)))

(defn encode-rpc-response
  "Encode RPC response to buffer"
  [^RpcResponsePacket packet ^PacketBuffer buffer]
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-response
  "Decode RPC response from buffer"
  [^PacketBuffer buffer]
  (let [request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcResponsePacket request-id payload)))

(defn handle-rpc-request
  "Handle RPC request on server"
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
                     net.minecraftforge.fml.network.PacketDistributor/PLAYER
                     player
                     (->RpcResponsePacket request-id response)))))))
    (.setPacketHandled ctx true)))

(defn handle-rpc-response
  "Handle RPC response on client"
  [^RpcResponsePacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        (rpc-client/handle-response (:request-id packet) (:payload packet))))
    (.setPacketHandled ctx true)))

(defn handle-sync-data
  "Handle sync data packet on client"
  [^SyncDataPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    
    (.enqueueWork ctx
      (fn []
        (log/debug "Handling sync data for GUI:" (:gui-id packet))
        
        ;; Get client container
        (when-let [container (gui/get-client-container)]
          (gui/apply-container-sync-packet container (:data packet)))))

    (.setPacketHandled ctx true)))

;; ============================================================================
;; Packet: GUI State Sync A (Server -> Client)
;; ============================================================================

(defrecord GuiStateSyncPacketA [payload])

(defn encode-gui-state-sync-a
  "Encode GUI state sync packet to buffer"
  [^GuiStateSyncPacketA packet ^PacketBuffer buffer]
  (write-data-map buffer (:payload packet)))

(defn decode-gui-state-sync-a
  "Decode GUI state sync packet from buffer"
  [^PacketBuffer buffer]
  (->GuiStateSyncPacketA (read-data-map buffer)))

(defn handle-gui-state-sync-a
  "Handle GUI state sync packet on client"
  [^GuiStateSyncPacketA packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        (gui/apply-gui-sync-payload! (:payload packet))))
    (.setPacketHandled ctx true)))

(defn broadcast-gui-state-a
  "Broadcast GUI state to nearby players (Forge 1.16.5 implementation)"
  [world pos sync-data]
  (when @network-channel
    (let [gui-state (->GuiStateSyncPacketA sync-data)]
      (try
        ;; Send to all players tracking the chunk
        (let [chunk-pos (try 
                         (.chunk (.getChunk world pos))
                         (catch Exception _ nil))]
          (when chunk-pos
            (.send @network-channel
                   (try 
                     (.TRACKING_CHUNK net.minecraftforge.fml.network.PacketDistributor chunk-pos)
                     (catch Exception _ 
                       net.minecraftforge.fml.network.PacketDistributor/ALL))
                   gui-state)
            (log/debug "Broadcast GUI state to players tracking chunk at" pos)))
        (catch Exception e
          (log/debug "Error broadcasting GUI state:" (.getMessage e)))))))

;; ============================================================================
;; Packet: GUI State Sync B (Server -> Client)
;; ============================================================================

(defrecord GuiStateSyncPacketB [payload])

(defn encode-gui-state-sync-b
  "Encode GUI state sync packet to buffer"
  [^GuiStateSyncPacketB packet ^PacketBuffer buffer]
  (write-data-map buffer (:payload packet)))

(defn decode-gui-state-sync-b
  "Decode GUI state sync packet from buffer"
  [^PacketBuffer buffer]
  (->GuiStateSyncPacketB (read-data-map buffer)))

(defn handle-gui-state-sync-b
  "Handle GUI state sync packet on client"
  [^GuiStateSyncPacketB packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        (gui/apply-gui-sync-payload! (:payload packet))))
    (.setPacketHandled ctx true)))

(defn broadcast-gui-state-b
  "Broadcast GUI state to nearby players (Forge 1.16.5 implementation)"
  [world pos sync-data]
  (when @network-channel
    (let [gui-state (->GuiStateSyncPacketB sync-data)]
      (try
        ;; Send to all players tracking the chunk
        (let [chunk-pos (try 
                         (.chunk (.getChunk world pos))
                         (catch Exception _ nil))]
          (when chunk-pos
            (.send @network-channel
                   (try 
                     (.TRACKING_CHUNK net.minecraftforge.fml.network.PacketDistributor chunk-pos)
                     (catch Exception _ 
                       net.minecraftforge.fml.network.PacketDistributor/ALL))
                   gui-state)
            (log/debug "Broadcast GUI state to players tracking chunk at" pos)))
        (catch Exception e
          (log/debug "Error broadcasting GUI state:" (.getMessage e)))))))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-packets!
  "Register all GUI packets"
  []
  (log/info "Registering GUI network packets")
  
  (let [channel (create-channel)]
    (reset! network-channel channel)
    
    ;; Register Button Click Packet (Client -> Server)
    (.messageBuilder channel ButtonClickPacket 0)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-button-click packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-button-click buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-button-click packet ctx-supplier))))
    (.add)
    
    ;; Register Text Input Packet (Client -> Server)
    (.messageBuilder channel TextInputPacket 1)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-text-input packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-text-input buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-text-input packet ctx-supplier))))
    (.add)
    
    ;; Register Sync Data Packet (Server -> Client)
    (.messageBuilder channel SyncDataPacket 2)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-sync-data packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-sync-data buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-sync-data packet ctx-supplier))))
    (.add)

    ;; Register RPC Request Packet (Client -> Server)
    (.messageBuilder channel RpcRequestPacket 3)
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

    ;; Register RPC Response Packet (Server -> Client)
    (.messageBuilder channel RpcResponsePacket 4)
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

    ;; Register GUI State Sync Packet A (Server -> Client)
    (.messageBuilder channel GuiStateSyncPacketA 5)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-gui-state-sync-a packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-gui-state-sync-a buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-gui-state-sync-a packet ctx-supplier))))
    (.add)

    ;; Register GUI State Sync Packet B (Server -> Client)
    (.messageBuilder channel GuiStateSyncPacketB 6)
    (.encoder (reify BiConsumer
                (accept [_ packet buffer]
                  (encode-gui-state-sync-b packet buffer))))
    (.decoder (reify java.util.function.Function
                (apply [_ buffer]
                  (decode-gui-state-sync-b buffer))))
    (.consumer (reify BiConsumer
                 (accept [_ packet ctx-supplier]
                   (handle-gui-state-sync-b packet ctx-supplier))))
    (.add)
    
    (log/info "GUI network packets registered successfully")))

;; ============================================================================
;; Sending Packets
;; ============================================================================

(defn send-button-click-to-server
  "Send button click packet from client to server"
  [gui-id button-id]
  (when @network-channel
    (.sendToServer @network-channel (->ButtonClickPacket gui-id button-id))
    (log/debug "Sent button click to server:" button-id)))

(defn send-text-input-to-server
  "Send text input packet from client to server"
  [gui-id field-id text]
  (when @network-channel
    (.sendToServer @network-channel (->TextInputPacket gui-id field-id text))
    (log/debug "Sent text input to server:" text)))

(defn send-sync-data-to-client
  "Send sync data packet from server to client"
  [player gui-id data]
  (when @network-channel
    (.send @network-channel 
           net.minecraftforge.fml.network.PacketDistributor/PLAYER 
           player
           (->SyncDataPacket gui-id data))
    (log/debug "Sent sync data to client:" (keys data))))

(defn send-rpc-request-to-server
  "Send RPC request from client to server"
  [msg-id payload request-id]
  (when @network-channel
    (.sendToServer @network-channel (->RpcRequestPacket msg-id request-id payload))
    (log/debug "Sent RPC request to server:" msg-id)))

(defmethod rpc-client/send-request :forge-1.16.5
  [msg-id payload request-id]
  (send-rpc-request-to-server msg-id payload request-id))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize network system"
  []
  (log/info "Initializing GUI network system (Forge 1.16.5)")
  (register-packets!)
  ;; Register Forge 1.16.5 sync implementations
  (gui/register-gui-sync-impls! :forge-1.16.5 broadcast-gui-state-a broadcast-gui-state-b)
  (log/info "GUI network system initialized (Forge 1.16.5)"))
