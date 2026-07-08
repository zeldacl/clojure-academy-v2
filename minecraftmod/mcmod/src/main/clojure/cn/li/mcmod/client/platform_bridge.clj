(ns cn.li.mcmod.client.platform-bridge
  "Thin public API for client bridge operations.
   Delegates to framework atom [:platform :client-bridge]."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.util.log :as log]))

(defn- bridge-op [k & args]
  (when-let [f (get-in @(fw/fw-atom) [:platform :client-bridge k])]
    (apply f args)))

(defn install-client-bridge!
  "Install client bridge callbacks from a map of handler functions.
   REPLACES the entire client-bridge map."
  [ops-map]
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :client-bridge] ops-map)) nil)

(defn merge-client-bridge!
  "Merge additional handlers into the client bridge without replacing existing ops.
   Safe for incremental registration from content modules (ac)."
  [ops-map]
  (when-let [fw-atom (fw/fw-atom)]
    (swap! fw-atom update-in [:platform :client-bridge] merge ops-map))
  nil)

(defn reset-client-bridge-for-test!
  []
  (when-let [fw-atom (fw/fw-atom)] (swap! fw-atom assoc-in [:platform :client-bridge] nil)) nil)

(defn client-bridge-available? []
  (boolean (get-in @(fw/fw-atom) [:platform :client-bridge])))

;; All public wrapper functions — delegate to identically-named keys in the ops map
(defn on-slot-key-down!          [& args] (apply bridge-op :slot-key-down args))
(defn on-slot-key-tick!          [& args] (apply bridge-op :slot-key-tick args))
(defn on-slot-key-up!            [& args] (apply bridge-op :slot-key-up args))
(defn on-slot-key-abort!         [& args] (apply bridge-op :slot-key-abort args))
(defn on-movement-key-down!      [& args] (apply bridge-op :movement-key-down args))
(defn on-movement-key-tick!      [& args] (apply bridge-op :movement-key-tick args))
(defn on-movement-key-up!        [& args] (apply bridge-op :movement-key-up args))
(defn open-screen!               [& args] (apply bridge-op :open-screen args))
(defn run-client-effect!         [& args] (apply bridge-op :run-client-effect args))
(defn get-client-player          [& args] (apply bridge-op :get-client-player args))
(defn screen-active?             [& args] (apply bridge-op :screen-active? args))
(defn set-active-overlay-app!     [& args] (apply bridge-op :set-active-overlay-app args))
(defn close-screen!              [& args] (apply bridge-op :close-screen! args))
(defn send-system-message!       [& args] (apply bridge-op :send-system-message! args))
(defn game-time-ms               [& args] (apply bridge-op :game-time-ms args))
(defn font-width                 [& args] (apply bridge-op :font-width args))
(defn font-text-width            [& args] (apply bridge-op :font-text-width args))
(defn reactive-embed-host!       [& args] (apply bridge-op :reactive-embed-host! args))
(defn stop-all-media!            [& args] (apply bridge-op :stop-all-media! args))
(defn get-mouse-pos              [& args] (apply bridge-op :get-mouse-pos args))
(defn get-window-size            [& args] (apply bridge-op :get-window-size args))
(defn get-player-owner           [& args] (apply bridge-op :get-player-owner args))
(defn register-font!             [& args] (apply bridge-op :register-font! args))
(defn resolve-shader             [& args] (apply bridge-op :resolve-shader args))
(defn has-recipes?               [& args] (apply bridge-op :has-recipes? args))
(defn send-to-client!            [& args] (apply bridge-op :send-to-client! args))
(defn spawn-item-stack-at!       [& args] (apply bridge-op :spawn-item-stack-at! args))
(defn blit-textured-quad!        [& args] (apply bridge-op :blit-textured-quad! args))
(defn is-glfw-key-down?          [& args] (apply bridge-op :is-glfw-key-down? args))
(defn open-reactive-screen!      [& args] (apply bridge-op :open-reactive-screen args))

(defn call-adapter
  "Look up and call any bridge function by key. Returns nil if not found."
  [k & args]
  (apply bridge-op k args))