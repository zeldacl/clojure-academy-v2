(ns cn.li.ac.gui.reactive.register
  "Reactive GUI registration — installs handlers via client bridge merge.
   All registration happens in init-all! (called from content_loader), NOT at top level."
  (:require [cn.li.mcmod.client.platform-bridge :as bridge]
            [cn.li.ac.block.solar-gen.gui-reactive :as solar]
            [cn.li.ac.block.wind-gen.gui-reactive :as wind]
            [cn.li.ac.block.imag-fusor.gui-reactive :as fusor]
            [cn.li.ac.block.metal-former.gui-reactive :as mformer]
            [cn.li.ac.block.wireless-node.gui-reactive :as node]
            [cn.li.ac.block.wireless-matrix.gui-reactive :as matrix]
            [cn.li.ac.block.ability-interferer.gui-reactive :as interferer]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry atom
;; ============================================================================

(def ^:private screen-creators (atom {}))

;; ============================================================================
;; Bridge handler
;; ============================================================================

(defn- reactive-screen-handler
  [gui-key container menu player]
  (if-let [entry (get @screen-creators gui-key)]
    ((:create entry) container menu player)
    (do (log/warn "No reactive screen creator for:" gui-key) nil)))

;; ============================================================================
;; Registration (called during init-all!, NOT at top level)
;; ============================================================================

(defn- register-screen! [gui-key create-fn title]
  (swap! screen-creators assoc gui-key {:create create-fn :title title}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn install-bridge!
  "Install reactive screen handler into client bridge via merge.
   Called from content_loader.clj AFTER forge init has set up base bridge ops."
  []
  (bridge/merge-client-bridge!
    {:open-reactive-screen reactive-screen-handler})
  (log/info "Reactive screen bridge handler installed"))

(defn init-all!
  "Register all reactive screen creators and install bridge handler.
   Called from content_loader.clj at runtime content init (NO top-level side effects)."
  []
  (register-screen! :solar-gen solar/create-screen "Solar Generator")
  (register-screen! :imag-fusor fusor/create-screen "Imag Fusor")
  (register-screen! :metal-former mformer/create-screen "Metal Former")
  (register-screen! :wind-gen-base wind/create-screen "Wind Generator")
  (register-screen! :wireless-node node/create-screen "Wireless Node")
  (register-screen! :wireless-matrix matrix/create-screen "Wireless Matrix")
  (register-screen! :ability-interferer interferer/create-screen "Ability Interferer")
  (install-bridge!)
  (log/info (str "Reactive GUIs initialized: " (count @screen-creators))))
