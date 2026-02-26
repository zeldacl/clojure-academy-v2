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

;; ============================================================================
;; Packet: GUI State Sync (Server -> Client)
;; Universal packet that handles all GUI types via gui-id routing
;; ============================================================================

(defrecord GuiStateSyncPacket [payload])

(defn encode-gui-state-sync
  "Encode GUI state sync packet to buffer"
  [^GuiStateSyncPacket packet ^PacketBuffer buffer]
  (write-data-map buffer (:payload packet)))

(defn decode-gui-state-sync
  "Decode GUI state sync packet from buffer"
  [^PacketBuffer buffer]
  (->GuiStateSyncPacket (read-data-map buffer)))

(defn handle-gui-state-sync
  "Handle GUI state sync packet on client
  
  Routes to correct GUI handler via gui-id in payload."
  [^GuiStateSyncPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    (.enqueueWork ctx
      (fn []
        (gui/apply-gui-sync-payload! (:payload packet))))
    (.setPacketHandled ctx true)))

(defn broadcast-gui-state
  "Universal GUI state broadcast function (Forge 1.16.5 implementation)
  
  Accepts payload with gui-id and routes to correct client handler.
  Platform code doesn't need to know about specific GUI types."
  [world pos sync-data]
  (when @network-channel
    (let [gui-state (->GuiStateSyncPacket sync-data)]
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
            (log/debug "Broadcast GUI state (gui-id:" (:gui-id sync-data) ") to chunk at" pos)))
        (catch Exception e
          (log/debug "Error broadcasting GUI state:" (.getMessage e))))))))

;; ============================================================================
;; Registration Macro (DRY principle)
;; ============================================================================

(defmacro register-packet!
  "Macro to register a packet with encoder, decoder, and handler.
  
  Usage: (register-packet! channel PacketType id encode-fn decode-fn handle-fn)"
  [channel packet-type id encode-fn decode-fn handle-fn]
  `(do
     (.messageBuilder ~channel ~packet-type ~id)
     (.encoder (reify BiConsumer
                 (accept [_# packet# buffer#]
                   (~encode-fn packet# buffer#))))
     (.decoder (reify java.util.function.Function
                 (apply [_# buffer#]
                   (~decode-fn buffer#))))
     (.consumer (reify BiConsumer
                  (accept [_# packet# ctx-supplier#]
                    (~handle-fn packet# ctx-supplier#))))
     (.add)))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-packets!
  "Register all GUI network packets.
  
  Platform layer maintains a fixed set of 5 packet types:
  0 - ButtonClickPacket      (C→S: GUI button interactions)
  1 - TextInputPacket        (C→S: GUI text field inputs)
  2 - RpcRequestPacket       (C→S: Generic RPC calls)
  3 - RpcResponsePacket      (S→C: RPC responses)
  4 - GuiStateSyncPacket     (S→C: Universal GUI state sync)
  
  This set is complete and should not grow with business logic."
  []
  (log/info "Registering GUI network packets")
  
  (let [channel (create-channel)]
    (reset! network-channel channel)
    
    ;; Fixed packet registry - business-agnostic
    (register-packet! channel ButtonClickPacket    0 encode-button-click    decode-button-click    handle-button-click)
    (register-packet! channel TextInputPacket      1 encode-text-input      decode-text-input      handle-text-input)
    (register-packet! channel RpcRequestPacket     2 encode-rpc-request     decode-rpc-request     handle-rpc-request)
    (register-packet! channel RpcResponsePacket    3 encode-rpc-response    decode-rpc-response    handle-rpc-response)
    (register-packet! channel GuiStateSyncPacket   4 encode-gui-state-sync  decode-gui-state-sync  handle-gui-state-sync)
    
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
  ;; Register unified GUI state broadcast implementation
  ;; Platform code doesn't need to know about specific GUI types (matrix/node/etc)
  (gui/register-gui-sync-impl! broadcast-gui-state)
  (log/info "GUI network system initialized (Forge 1.16.5)"))
