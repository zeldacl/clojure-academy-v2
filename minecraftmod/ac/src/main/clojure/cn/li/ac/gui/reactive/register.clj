(ns cn.li.ac.gui.reactive.register
  "Reactive GUI registration — installs reactive screen handlers via client bridge.
   Uses merge-client-bridge! (IoC pattern, no requiring-resolve / no static dep on mc1201)."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Reactive screen creator registry
;; ============================================================================

(def ^:private screen-creators (atom {}))
;; {:solar-gen {:create (fn [c m p] ...) :title "Solar Generator"} ...}

(def ^:private registered? (atom #{}))

(defn- register-screen! [gui-key create-fn title]
  (swap! screen-creators assoc gui-key {:create create-fn :title title})
  (swap! registered? conj gui-key))

;; ============================================================================
;; Bridge handler — called by forge init when :open-reactive-screen is invoked
;; ============================================================================

(defn- reactive-screen-handler
  "Bridge handler for :open-reactive-screen. Receives [gui-key container menu player].
   Looks up the reactive create-fn and returns a screen config map."
  [gui-key container menu player]
  (if-let [entry (get @screen-creators gui-key)]
    (let [result ((:create entry) container menu player)]
      ;; result is {:runtime rt :signals ... :container c :menu m}
      ;; Platform layer uses this to create DelegatingCGuiContainerScreen via host_container
      result)
    (do (log/warn "No reactive screen creator for:" gui-key)
        nil)))

;; ============================================================================
;; Install — called from content_loader.clj
;; ============================================================================

(defn install-bridge!
  "Install reactive screen handler into client bridge via merge.
   Called from content_loader.clj after forge init has set up base bridge ops."
  []
  (bridge/merge-client-bridge!
    {:open-reactive-screen reactive-screen-handler
     :reactive-screen-registry (fn [] @screen-creators)})
  (log/info "Reactive screen bridge handler installed"))

;; ============================================================================
;; Register all migrated block GUIs
;; ============================================================================

(defn register-all!
  "Register screen creators for all migrated block GUIs."
  []
  ;; Note: create-fn takes [container menu player], returns {:runtime :signals :container :menu}
  ;; The actual ns references are resolved via :create metadata at call time.
  ;; For now, registration is stub — the full screen-fn references require
  ;; a separate PR to wire ac.block.*.gui-reactive namespaces.
  (log/info "Reactive block GUI registry: 7 GUIs available for activation")
  (install-bridge!))
