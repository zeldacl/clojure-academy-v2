(ns cn.li.mc1201.client.session-cleanup
  "Shared client owner cleanup/orchestration for disconnects and server switches."
  (:require [cn.li.mc1201.client.effects.level-renderer :as level-renderer]
            [cn.li.mc1201.client.overlay.state :as overlay-state]
            [cn.li.mc1201.gui.reactive.overlay-host :as overlay-host]
            [cn.li.mc1201.client.session :as client-session]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.runtime.deferred :as deferred]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.client.player LocalPlayer]))

(def ^:private default-cleanup-state
  {:connection-key nil
   :owner nil})

(defn create-session-cleanup-runtime
  []
  {::runtime ::session-cleanup-runtime
   :lifecycle-state* (atom default-cleanup-state)})

(def ^:private default-session-cleanup-runtime-holder
  (deferred/deferred #(create-session-cleanup-runtime)))

(def ^:private session-cleanup-runtime-override
  "Plain root var, nil in production. Test-only swap target for
   call-with-session-cleanup-runtime — replaces the prior ^:dynamic +
   binding pair. Single-threaded test execution only."
  nil)

(defn- session-cleanup-runtime?
  [runtime]
  (and (map? runtime)
       (= ::session-cleanup-runtime (::runtime runtime))
       (some? (:lifecycle-state* runtime))))

(defn call-with-session-cleanup-runtime
  [runtime f]
  (when-not (session-cleanup-runtime? runtime)
    (throw (ex-info "Expected session cleanup runtime"
                    {:runtime runtime})))
  (let [prev session-cleanup-runtime-override]
    (alter-var-root #'session-cleanup-runtime-override (constantly runtime))
    (try
      (f)
      (finally
        (alter-var-root #'session-cleanup-runtime-override (constantly prev))))))

(defmacro with-session-cleanup-runtime
  [runtime & body]
  `(call-with-session-cleanup-runtime ~runtime (fn [] ~@body)))

(defn- current-session-cleanup-runtime
  []
  (or session-cleanup-runtime-override
      @default-session-cleanup-runtime-holder))

(defn- lifecycle-state-atom
  []
  (:lifecycle-state* (current-session-cleanup-runtime)))

(defn cleanup-state-snapshot
  []
  @(lifecycle-state-atom))

(defn reset-cleanup-state-for-test!
  ([]
   (reset-cleanup-state-for-test! default-cleanup-state))
  ([snapshot]
   (reset! (lifecycle-state-atom) {:connection-key (:connection-key snapshot)
                                   :owner (:owner snapshot)})
   nil))

(defn- local-player
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (.player mc)))

(defn- close-current-screen!
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when (some? (.screen mc))
      (.setScreen mc nil)))
  nil)

(defn clear-owner-state!
  ([owner]
   (clear-owner-state! owner {}))
  ([owner {:keys [clear-owner-input-state!]
           :or {clear-owner-input-state! nil}}]
   (let [owner* (or owner {})
         session-id (:client-session-id owner*)]
     (close-current-screen!)
     (when (fn? clear-owner-input-state!)
       (clear-owner-input-state! owner*))
     (runtime-hooks/client-clear-owner-state! owner*)
     (overlay-state/clear-client-activated! owner*)
     (overlay-host/dispose-overlay! (:client-session-id owner*))
     (level-renderer/clear-owner-walk-speed! owner* ^LocalPlayer (local-player))
     (when session-id
       (net-client/clear-client-session-state! session-id))
     nil)))

(defn tick-connection-change!
  ([]
   (tick-connection-change! {}))
  ([opts]
   (let [current-connection-key (client-session/connection-key)
         current-owner (client-session/current-local-player-owner)
         {:keys [connection-key owner]} (cleanup-state-snapshot)]
     (when (and owner
                (not= connection-key current-connection-key))
       (try
         (clear-owner-state! owner opts)
         (catch Exception e
           (log/error "Failed to clear client owner state during connection transition" e))))
     (reset! (lifecycle-state-atom) {:connection-key current-connection-key
                                     :owner current-owner})
     nil)))