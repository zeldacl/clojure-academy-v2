(ns cn.li.forge1201.wireless-imc
  "IMC (Inter-Mod Communication) support for the wireless energy system.

  External mods register handlers during InterModEnqueueEvent by sending IMC
  messages to this mod using the keys defined in WirelessImc. Handlers are
  invoked after the corresponding Forge event fires, so they receive the same
  information as a regular EventBus subscriber but via callback.

  Handler isolation: a handler that throws is logged at DEBUG and removed so
  it cannot disrupt subsequent ticks or other handlers."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [cn.li.acapi.wireless WirelessImc$NetworkEventHandler WirelessImc$NodeEventHandler]
           [cn.li.acapi.wireless.event WirelessNetworkEvent$NetworkCreated
                                       WirelessNetworkEvent$NetworkDestroyed
                                       WirelessNetworkEvent$NodeConnected
                                       WirelessNetworkEvent$NodeDisconnected
                                       WirelessNetworkEvent$GeneratorLinked
                                       WirelessNetworkEvent$ReceiverLinked]))

;; ============================================================================
;; Handler registries
;; ============================================================================

(defonce ^:private network-handlers (atom []))
(defonce ^:private node-handlers    (atom []))

(defn register-network-handler!
  "Register an IMC network event handler. Called from InterModProcessEvent."
  [^WirelessImc$NetworkEventHandler handler]
  (swap! network-handlers conj handler))

(defn register-node-handler!
  "Register an IMC node event handler. Called from InterModProcessEvent."
  [^WirelessImc$NodeEventHandler handler]
  (swap! node-handlers conj handler))

;; ============================================================================
;; Dispatch helpers
;; ============================================================================

(defn- invoke-safe
  "Call f, return nil. If it throws, log and return ::remove-handler."
  [f]
  (try (f) nil
       (catch Exception e
         (log/debug "IMC wireless handler threw exception, removing it:" (ex-message e))
         ::remove-handler)))

(defn- dispatch-network! [type ssid matrix]
  (let [bad (keep (fn [h]
                    (when (= ::remove-handler
                              (invoke-safe #(.onNetworkEvent ^WirelessImc$NetworkEventHandler h
                                                            type ssid matrix)))
                      h))
                  @network-handlers)]
    (when (seq bad)
      (swap! network-handlers #(remove (set bad) %)))))

(defn- dispatch-node! [type node]
  (let [bad (keep (fn [h]
                    (when (= ::remove-handler
                              (invoke-safe #(.onNodeEvent ^WirelessImc$NodeEventHandler h type node)))
                      h))
                  @node-handlers)]
    (when (seq bad)
      (swap! node-handlers #(remove (set bad) %)))))

;; ============================================================================
;; Event dispatch – dispatches wireless payload events to IMC handlers
;; ============================================================================

(defn- on-wireless-event [event]
  (cond
    (instance? WirelessNetworkEvent$NetworkCreated event)
    (let [^WirelessNetworkEvent$NetworkCreated e event]
      (dispatch-network! "created" (.getSsid e) (.getMatrix e)))

    (instance? WirelessNetworkEvent$NetworkDestroyed event)
    (let [^WirelessNetworkEvent$NetworkDestroyed e event]
      (dispatch-network! "destroyed" (.getSsid e) (.getMatrix e)))

    (instance? WirelessNetworkEvent$NodeConnected event)
    (let [^WirelessNetworkEvent$NodeConnected e event]
      (dispatch-node! "connected" (.getNode e)))

    (instance? WirelessNetworkEvent$NodeDisconnected event)
    (let [^WirelessNetworkEvent$NodeDisconnected e event]
      (dispatch-node! "disconnected" (.getNode e)))

    (instance? WirelessNetworkEvent$GeneratorLinked event)
    (let [^WirelessNetworkEvent$GeneratorLinked e event]
      (dispatch-node! "generator_linked" (.getNode e)))

    (instance? WirelessNetworkEvent$ReceiverLinked event)
    (let [^WirelessNetworkEvent$ReceiverLinked e event]
      (dispatch-node! "receiver_linked" (.getNode e)))

    :else nil))

(defn dispatch-event!
  "Dispatch a wireless runtime payload event to registered IMC handlers."
  [event]
  (on-wireless-event event)
  nil)

(defn init!
  "Initialize wireless IMC bridge. Dispatch is bound through platform-events."
  []
  (log/info "Wireless IMC dispatcher ready (direct payload dispatch mode)"))
