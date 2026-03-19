(ns cn.li.mcmod.gui.network
  "Network packet abstraction for GUI communication"
  (:require [cn.li.mcmod.util.log :as log]))

;; Multimethod for version-specific networking
(def ^:dynamic *forge-version* nil)

;; Packet types
(def packet-type-button-click :button-click)
(def packet-type-slot-change :slot-change)
(def packet-type-open-gui :open-gui)
(def packet-type-close-gui :close-gui)

;; Packet record
(defrecord Packet [type data])

;; Create packet
(defn create-packet [packet-type data]
  (map->Packet {:type packet-type :data data}))

;; Button click packet
(defn button-click-packet [container-id button-id]
  (create-packet packet-type-button-click
                 {:container-id container-id
                  :button-id button-id}))

;; Slot change packet
(defn slot-change-packet [container-id slot-index item-stack]
  (create-packet packet-type-slot-change
                 {:container-id container-id
                  :slot-index slot-index
                  :item-stack item-stack}))

;; Open GUI packet
(defn open-gui-packet [gui-id world-pos]
  (create-packet packet-type-open-gui
                 {:gui-id gui-id
                  :pos world-pos}))

;; Multimethod for sending packets
(defmulti send-to-server
  "Send packet to server"
  (fn [_packet] *forge-version*))

(defmulti send-to-client
  "Send packet to specific client"
  (fn [_packet _player] *forge-version*))

(defmethod send-to-server :default [packet]
  (log/info "send-to-server not implemented for" *forge-version* "packet:" packet))

(defmethod send-to-client :default [packet _]
  (log/info "send-to-client not implemented for" *forge-version* "packet:" packet))

;; Multimethod for registering packet handlers
(defmulti register-packet-handlers
  "Register version-specific packet handlers"
  (fn [] *forge-version*))

(defmethod register-packet-handlers :default []
  (log/info "register-packet-handlers not implemented for" *forge-version*))

;; Packet handler registry
(defonce packet-handlers (atom {}))

;; Register packet handler
(defn register-handler! [packet-type handler-fn]
  (swap! packet-handlers assoc packet-type handler-fn)
  (log/info "Registered packet handler for" packet-type))

;; Handle incoming packet
(defn handle-packet [packet player]
  (if-let [handler (get @packet-handlers (:type packet))]
    (try
      (handler (:data packet) player)
      (catch Exception e
        (log/info "Error handling packet:" (.getMessage e))
        (.printStackTrace e)))
    (log/info "No handler for packet type:" (:type packet))))

;; Default packet handlers
(defn default-button-click-handler [data player]
  (log/info "Button click from player" player ":" data)
  (let [{:keys [container-id button-id]} data]
    (when-let [container (cn.li.mcmod.gui.container/get-container container-id)]
      (when (cn.li.mcmod.gui.container/validate-container container player)
        (cn.li.mcmod.gui.container/handle-button-click! container button-id)))}))

(defn default-slot-change-handler [data player]
  (log/info "Slot change from player" player ":" data)
  (let [{:keys [container-id slot-index item-stack]} data]
    (when-let [container (cn.li.mcmod.gui.container/get-container container-id)]
      (when (cn.li.mcmod.gui.container/validate-container container player)
        (cn.li.mcmod.gui.container/set-slot-item! container slot-index item-stack)))))

;; Initialize default handlers
(defn init-default-handlers! []
  (register-handler! packet-type-button-click default-button-click-handler)
  (register-handler! packet-type-slot-change default-slot-change-handler))
