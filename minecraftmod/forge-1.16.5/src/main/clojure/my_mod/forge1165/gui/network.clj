(ns my-mod.forge1165.gui.network
  "Forge 1.16.5 GUI Network Packet System"
  (:require [my-mod.wireless.gui.node-container :as node-container]
            [my-mod.wireless.gui.matrix-container :as matrix-container]
            [my-mod.wireless.gui.registry :as gui-registry]
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
        
        ;; Get active container for player
        (when-let [container (gui-registry/get-player-container player)]
          (cond
            ;; Node container button
            (and (= (:gui-id packet) gui-registry/gui-wireless-node)
                 (instance? my_mod.wireless.gui.node_container.NodeContainer container))
            (node-container/handle-button-click! container (:button-id packet) player)
            
            ;; Matrix container button
            (and (= (:gui-id packet) gui-registry/gui-wireless-matrix)
                 (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container))
            (matrix-container/handle-button-click! container (:button-id packet) player)
            
            :else
            (log/warn "Unknown GUI or container type for button click")))))
    
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
        
        ;; Get active container for player
        (when-let [container (gui-registry/get-player-container player)]
          (cond
            ;; Node container text input
            (and (= (:gui-id packet) gui-registry/gui-wireless-node)
                 (instance? my_mod.wireless.gui.node_container.NodeContainer container))
            (node-container/handle-text-input! container (:field-id packet) (:text packet) player)
            
            ;; Matrix container text input
            (and (= (:gui-id packet) gui-registry/gui-wireless-matrix)
                 (instance? my_mod.wireless.gui.matrix_container.MatrixContainer container))
            (matrix-container/handle-text-input! container (:field-id packet) (:text packet) player)
            
            :else
            (log/warn "Unknown GUI or container type for text input")))))
    
    (.setPacketHandled ctx true)))

;; ============================================================================
;; Packet: Sync Data (Server -> Client)
;; ============================================================================

(defrecord SyncDataPacket [gui-id data])

(defn encode-sync-data
  "Encode sync data packet to buffer"
  [^SyncDataPacket packet ^PacketBuffer buffer]
  (.writeInt buffer (:gui-id packet))
  
  ;; Encode data map as NBT
  (let [data (:data packet)]
    (.writeInt buffer (count data))
    (doseq [[k v] data]
      (.writeUtf buffer (name k))
      (cond
        (integer? v) (do (.writeByte buffer 0) (.writeInt buffer v))
        (float? v) (do (.writeByte buffer 1) (.writeFloat buffer v))
        (string? v) (do (.writeByte buffer 2) (.writeUtf buffer v))
        (boolean? v) (do (.writeByte buffer 3) (.writeBoolean buffer v))
        :else (log/warn "Unknown data type for sync:" (type v))))))

(defn decode-sync-data
  "Decode sync data packet from buffer"
  [^PacketBuffer buffer]
  (let [gui-id (.readInt buffer)
        count (.readInt buffer)
        data (into {}
               (for [_ (range count)]
                 (let [key (keyword (.readUtf buffer))
                       type-id (.readByte buffer)
                       value (case type-id
                               0 (.readInt buffer)
                               1 (.readFloat buffer)
                               2 (.readUtf buffer)
                               3 (.readBoolean buffer)
                               nil)]
                   [key value])))]
    (->SyncDataPacket gui-id data)))

(defn handle-sync-data
  "Handle sync data packet on client"
  [^SyncDataPacket packet ^Supplier context-supplier]
  (let [ctx (.get context-supplier)]
    
    (.enqueueWork ctx
      (fn []
        (log/debug "Handling sync data for GUI:" (:gui-id packet))
        
        ;; Get client container
        (when-let [container (gui-registry/get-client-container)]
          (gui-registry/apply-container-sync-packet container (:data packet)))))
    
    (.setPacketHandled ctx true)))

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

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init!
  "Initialize network system"
  []
  (log/info "Initializing GUI network system")
  (register-packets!)
  (log/info "GUI network system initialized"))
