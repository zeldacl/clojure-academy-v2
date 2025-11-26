(ns my-mod.fabric1201.gui.network
  "Fabric 1.20.1 GUI Network Packet System
  
  Platform-agnostic design: Uses container-dispatcher for polymorphic handling,
  eliminating hardcoded game concepts (Node, Matrix, etc.)."
  (:require [my-mod.wireless.gui.container-dispatcher :as dispatcher]
            [my-mod.wireless.gui.registry :as gui-registry]
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
    (when-let [container (gui-registry/get-player-container player)]
      (dispatcher/safe-handle-button-click! container (:button-id packet) player)))))

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
    (when-let [container (gui-registry/get-player-container player)]
      (dispatcher/safe-handle-text-input! container (:field-id packet) (:text packet) player))))

;; ============================================================================
;; Packet: Sync Data (Server -> Client)
;; ============================================================================

(defrecord SyncDataPacket [gui-id data])

(defn encode-sync-data
  "Encode sync data packet to buffer"
  [^SyncDataPacket packet ^PacketByteBuf buffer]
  (.writeInt buffer (:gui-id packet))
  
  ;; Encode data map
  (let [data (:data packet)]
    (.writeInt buffer (count data))
    (doseq [[k v] data]
      (.writeString buffer (name k))
      (cond
        (integer? v) (do (.writeByte buffer 0) (.writeInt buffer v))
        (float? v) (do (.writeByte buffer 1) (.writeFloat buffer v))
        (string? v) (do (.writeByte buffer 2) (.writeString buffer v))
        (boolean? v) (do (.writeByte buffer 3) (.writeBoolean buffer v))
        :else (log/warn "Unknown data type for sync:" (type v))))))

(defn decode-sync-data
  "Decode sync data packet from buffer"
  [^PacketByteBuf buffer]
  (let [gui-id (.readInt buffer)
        count (.readInt buffer)
        data (into {}
               (for [_ (range count)]
                 (let [key (keyword (.readString buffer))
                       type-id (.readByte buffer)
                       value (case type-id
                               0 (.readInt buffer)
                               1 (.readFloat buffer)
                               2 (.readString buffer)
                               3 (.readBoolean buffer)
                               nil)]
                   [key value])))]
    (->SyncDataPacket gui-id data)))

(defn handle-sync-data-client
  "Handle sync data packet on client"
  [packet-data]
  (let [packet (decode-sync-data packet-data)]
    (log/debug "Handling sync data for GUI:" (:gui-id packet))
    
    ;; Get client container
    (when-let [container (gui-registry/get-client-container)]
      (gui-registry/apply-container-sync-packet container (:data packet)))))

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
  
  (log/info "Server-side GUI network packets registered (Fabric)"))

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

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-server!
  "Initialize server-side network system"
  []
  (log/info "Initializing Fabric GUI network system (server)")
  (register-server-packets!)
  (log/info "Fabric GUI network system initialized (server)"))

(defn init-client!
  "Initialize client-side network system"
  []
  (log/info "Initializing Fabric GUI network system (client)")
  (register-client-packets!)
  (log/info "Fabric GUI network system initialized (client)"))
