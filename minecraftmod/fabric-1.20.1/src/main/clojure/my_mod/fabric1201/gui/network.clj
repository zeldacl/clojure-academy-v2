(ns my-mod.fabric1201.gui.network
  "Fabric 1.20.1 GUI Network Packet System
  
  Platform-agnostic design: All GUI framework functionality accessed through
  the platform-adapter, eliminating hardcoded game concepts."
  (:require [my-mod.gui.platform-adapter :as gui]
            [my-mod.network.client :as rpc-client]
            [my-mod.network.server :as rpc-server]
            [my-mod.util.log :as log])
  (:import [net.minecraft.network PacketByteBuf]
           [net.minecraft.server.network ServerPlayerEntity]
           [net.minecraft.util Identifier]
           [net.fabricmc.fabric.api.networking.v1 ServerPlayNetworking 
            ClientPlayNetworking PacketByteBufs]))

;; ============================================================================
;; Network Channel IDs
;; ============================================================================

(def BUTTON_CLICK_PACKET_ID 
  (Identifier. "my_mod" "gui_button_click"))

(def TEXT_INPUT_PACKET_ID 
  (Identifier. "my_mod" "gui_text_input"))

(def SYNC_DATA_PACKET_ID 
  (Identifier. "my_mod" "gui_sync_data"))

(def RPC_REQUEST_PACKET_ID
  (Identifier. "my_mod" "rpc_request"))

(def RPC_RESPONSE_PACKET_ID
  (Identifier. "my_mod" "rpc_response"))

(def GUI_STATE_SYNC_PACKET_ID
  (Identifier. "my_mod" "gui_state_sync"))

;; =========================================================================
;; Shared Map Encoding Helpers
;; =========================================================================

(defn- write-value
  "Write a single value with type tag"
  [^PacketByteBuf buffer value]
  (cond
    (integer? value) (do (.writeByte buffer 0) (.writeInt buffer value))
    (float? value) (do (.writeByte buffer 1) (.writeFloat buffer value))
    (string? value) (do (.writeByte buffer 2) (.writeString buffer value))
    (boolean? value) (do (.writeByte buffer 3) (.writeBoolean buffer value))
    (nil? value) (do (.writeByte buffer 4))
    :else (do (.writeByte buffer 5) (.writeString buffer (pr-str value)))))

(defn- read-value
  "Read a single value with type tag"
  [^PacketByteBuf buffer]
  (let [type-id (.readByte buffer)]
    (case type-id
      0 (.readInt buffer)
      1 (.readFloat buffer)
      2 (.readString buffer)
      3 (.readBoolean buffer)
      4 nil
      5 (.readString buffer)
      nil)))

(defn- write-data-map
  "Write a map of keyword->primitive values"
  [^PacketByteBuf buffer data]
  (.writeInt buffer (count data))
  (doseq [[k v] data]
    (.writeString buffer (name k))
    (write-value buffer v)))

(defn- read-data-map
  "Read a map of keyword->primitive values"
  [^PacketByteBuf buffer]
  (let [count (.readInt buffer)]
    (into {}
          (for [_ (range count)]
            (let [key (keyword (.readString buffer))
                  value (read-value buffer)]
              [key value])))))

;; ============================================================================
;; Packet: Button Click (Client -> Server)
;; ============================================================================

(defrecord ButtonClickPacket [gui-id button-id])

(defn encode-button-click
  "Encode button click packet to buffer"
  [^ButtonClickPacket packet ^PacketByteBuf buffer]
  (.writeInt buffer (:gui-id packet))
  (.writeInt buffer (:button-id packet)))

(defn decode-button-click
  "Decode button click packet from buffer"
  [^PacketByteBuf buffer]
  (let [gui-id (.readInt buffer)
        button-id (.readInt buffer)]
    (->ButtonClickPacket gui-id button-id)))

(defn handle-button-click-server
  "Handle button click packet on server"
  [player packet-data]
  (let [packet (decode-button-click packet-data)]
    (log/info "Handling button click:" (:button-id packet) 
              "for GUI:" (:gui-id packet))
    
    ;; Get active container for player and dispatch using protocol
    (when-let [container (gui/get-player-container player)]
      (gui/safe-handle-button-click! container (:button-id packet) player)))))

;; ============================================================================
;; Packet: Text Input (Client -> Server)
;; ============================================================================

(defrecord TextInputPacket [gui-id field-id text])

(defn encode-text-input
  "Encode text input packet to buffer"
  [^TextInputPacket packet ^PacketByteBuf buffer]
  (.writeInt buffer (:gui-id packet))
  (.writeInt buffer (:field-id packet))
  (.writeString buffer (:text packet)))

(defn decode-text-input
  "Decode text input packet from buffer"
  [^PacketByteBuf buffer]
  (let [gui-id (.readInt buffer)
        field-id (.readInt buffer)
        text (.readString buffer)]
    (->TextInputPacket gui-id field-id text)))

(defn handle-text-input-server
  "Handle text input packet on server"
  [player packet-data]
  (let [packet (decode-text-input packet-data)]
    (log/info "Handling text input:" (:text packet) 
              "for field:" (:field-id packet) 
              "in GUI:" (:gui-id packet))
    
    ;; Get active container for player and dispatch using protocol
    (when-let [container (gui/get-player-container player)]
      (gui/safe-handle-text-input! container (:field-id packet) (:text packet) player))))

;; ============================================================================
;; Packet: Sync Data (Server -> Client)
;; ============================================================================

(defrecord SyncDataPacket [gui-id data])

(defn encode-sync-data
  "Encode sync data packet to buffer"
  [^SyncDataPacket packet ^PacketByteBuf buffer]
  (.writeInt buffer (:gui-id packet))
  (write-data-map buffer (:data packet)))

(defn decode-sync-data
  "Decode sync data packet from buffer"
  [^PacketByteBuf buffer]
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
  [^RpcRequestPacket packet ^PacketByteBuf buffer]
  (.writeString buffer (:msg-id packet))
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-request
  "Decode RPC request from buffer"
  [^PacketByteBuf buffer]
  (let [msg-id (.readString buffer)
        request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcRequestPacket msg-id request-id payload)))

(defn encode-rpc-response
  "Encode RPC response to buffer"
  [^RpcResponsePacket packet ^PacketByteBuf buffer]
  (.writeInt buffer (:request-id packet))
  (write-data-map buffer (:payload packet)))

(defn decode-rpc-response
  "Decode RPC response from buffer"
  [^PacketByteBuf buffer]
  (let [request-id (.readInt buffer)
        payload (read-data-map buffer)]
    (->RpcResponsePacket request-id payload)))

(defn handle-sync-data-client
  "Handle sync data packet on client"
  [packet-data]
  (let [packet (decode-sync-data packet-data)]
    (log/debug "Handling sync data for GUI:" (:gui-id packet))
    
    ;; Get client container
    (when-let [container (gui/get-client-container)]
      (gui/apply-container-sync-packet container (:data packet))))))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-server-packets!
  "Register server-side packet receivers"
  []
  (log/info "Registering server-side GUI network packets (Fabric)")
  
  ;; Register Button Click handler
  (ServerPlayNetworking/registerGlobalReceiver
    BUTTON_CLICK_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler
      (receive [_ server player handler buf sender]
        (let [packet-data (.copy buf)]  ; Copy buffer for use on main thread
          ;; Execute on server thread
          (.execute server
            (fn []
              (handle-button-click-server player packet-data)))))))
  
  ;; Register Text Input handler
  (ServerPlayNetworking/registerGlobalReceiver
    TEXT_INPUT_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler
      (receive [_ server player handler buf sender]
        (let [packet-data (.copy buf)]
          (.execute server
            (fn []
              (handle-text-input-server player packet-data)))))))

  ;; Register RPC Request handler
  (ServerPlayNetworking/registerGlobalReceiver
    RPC_REQUEST_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayChannelHandler
      (receive [_ server player handler buf sender]
        (let [packet-data (.copy buf)]
          (.execute server
            (fn []
              (let [packet (decode-rpc-request packet-data)]
                (rpc-server/handle-request
                  (:msg-id packet)
                  (:request-id packet)
                  (:payload packet)
                  player
                  (fn [request-id response]
                    (let [resp-buf (PacketByteBufs/create)
                          resp (->RpcResponsePacket request-id response)]
                      (encode-rpc-response resp resp-buf)
                      (ServerPlayNetworking/send player RPC_RESPONSE_PACKET_ID resp-buf)))))))))))
  
  (log/info "Server-side GUI network packets registered (Fabric)"))

;; ============================================================================
;; GUI State Sync Packet (Universal - Server -> Client)
;; ============================================================================

(defrecord GuiStateSyncPacket [payload])

(defn encode-gui-state-sync
  "Encode GUI state sync packet to buffer"
  [^GuiStateSyncPacket packet ^PacketByteBuf buffer]
  (write-data-map buffer (:payload packet)))

(defn decode-gui-state-sync
  "Decode GUI state sync packet from buffer"
  [^PacketByteBuf buffer]
  (->GuiStateSyncPacket (read-data-map buffer)))

(defn handle-gui-state-sync-client
  "Handle GUI state sync packet on client (routes by gui-id)"
  [^GuiStateSyncPacket packet]
  (gui/apply-gui-sync-payload! (:payload packet)))

(defn broadcast-gui-state-fabric
  "Universal GUI state broadcast (Fabric 1.20.1 implementation)
  
  Platform-agnostic: Accepts payload with gui-id and routes on client side."
  [world pos sync-data]
  (let [gui-state (->GuiStateSyncPacket sync-data)
        buf (PacketByteBufs/create)]
    (encode-gui-state-sync gui-state buf)
    ;; Send to all players tracking chunk
    (try
      (let [server (try (.getServer world) (catch Exception _ nil))]
        (when server
          (doseq [player (try (.getPlayerManager (.getWorldProperties server (try (.getRegistryKey world) (catch Exception _ nil))))
                             (catch Exception _ nil))]
            (ServerPlayNetworking/send player GUI_STATE_SYNC_PACKET_ID buf))))
      (log/debug "Broadcast GUI state (gui-id:" (:gui-id sync-data) ") to players:" pos))
      (catch Exception e
        (log/debug "Error broadcasting GUI state:" (.getMessage e))))))



(defn register-client-packets!
  "Register client-side packet receivers"
  []
  (log/info "Registering client-side GUI network packets (Fabric)")
  
  ;; Register Sync Data handler
  (ClientPlayNetworking/registerGlobalReceiver
    SYNC_DATA_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking$PlayChannelHandler
      (receive [_ client handler buf sender]
        (let [packet-data (.copy buf)]
          ;; Execute on client thread
          (.execute client
            (fn []
              (handle-sync-data-client packet-data)))))))

  ;; Register RPC Response handler
  (ClientPlayNetworking/registerGlobalReceiver
    RPC_RESPONSE_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking$PlayChannelHandler
      (receive [_ client handler buf sender]
        (let [packet-data (.copy buf)]
          (.execute client
            (fn []
              (let [packet (decode-rpc-response packet-data)]
                (rpc-client/handle-response (:request-id packet) (:payload packet)))))))))

  ;; Register GUI State Sync handler (universal)
  (ClientPlayNetworking/registerGlobalReceiver
    GUI_STATE_SYNC_PACKET_ID
    (reify net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking$PlayChannelHandler
      (receive [_ client handler buf sender]
        (let [packet-data (.copy buf)]
          (.execute client
            (fn []
              (let [packet (decode-gui-state-sync packet-data)]
                (handle-gui-state-sync-client packet))))))))
  
  (log/info "Client-side GUI network packets registered (Fabric)"))

;; ============================================================================
;; Sending Packets
;; ============================================================================

(defn send-button-click-to-server
  "Send button click packet from client to server"
  [gui-id button-id]
  (let [buf (PacketByteBufs/create)
        packet (->ButtonClickPacket gui-id button-id)]
    (encode-button-click packet buf)
    (ClientPlayNetworking/send BUTTON_CLICK_PACKET_ID buf)
    (log/debug "Sent button click to server:" button-id)))

(defn send-text-input-to-server
  "Send text input packet from client to server"
  [gui-id field-id text]
  (let [buf (PacketByteBufs/create)
        packet (->TextInputPacket gui-id field-id text)]
    (encode-text-input packet buf)
    (ClientPlayNetworking/send TEXT_INPUT_PACKET_ID buf)
    (log/debug "Sent text input to server:" text)))

(defn send-sync-data-to-client
  "Send sync data packet from server to client"
  [player gui-id data]
  (let [buf (PacketByteBufs/create)
        packet (->SyncDataPacket gui-id data)]
    (encode-sync-data packet buf)
    (ServerPlayNetworking/send player SYNC_DATA_PACKET_ID buf)
    (log/debug "Sent sync data to client:" (keys data))))

(defn send-rpc-request-to-server
  "Send RPC request from client to server"
  [msg-id payload request-id]
  (let [buf (PacketByteBufs/create)
        packet (->RpcRequestPacket msg-id request-id payload)]
    (encode-rpc-request packet buf)
    (ClientPlayNetworking/send RPC_REQUEST_PACKET_ID buf)
    (log/debug "Sent RPC request to server:" msg-id)))

(defmethod rpc-client/send-request :fabric-1.20.1
  [msg-id payload request-id]
  (send-rpc-request-to-server msg-id payload request-id))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side network system"
  []
  (log/info "Initializing Fabric 1.20.1 GUI network system (server)")
  (register-server-packets!)
  ;; Register unified GUI state broadcast implementation
  (gui/register-gui-sync-impl! broadcast-gui-state-fabric)
  (log/info "Fabric 1.20.1 GUI network system initialized (server)"))

(defn init-client!
  "Initialize client-side network system"
  []
  (log/info "Initializing Fabric 1.20.1 GUI network system (client)")
  (register-client-packets!)
  (log/info "Fabric 1.20.1 GUI network system initialized (client)"))
