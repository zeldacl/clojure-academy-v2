(ns cn.li.forge1201.client.ability-runtime
  "CLIENT-ONLY ability input runtime (minimal runnable loop)."
  (:require [cn.li.mcmod.platform.ability-lifecycle :as ability-runtime]
            [cn.li.mcmod.ability.catalog :as catalog]
            [cn.li.forge1201.ability.network :as ability-net]
            [cn.li.forge1201.client.ability-client-state :as client-state]
            [cn.li.mcmod.network.client :as net-client]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.common MinecraftForge]
           [net.minecraftforge.event TickEvent$ClientTickEvent TickEvent$Phase]
           [net.minecraftforge.eventbus.api EventPriority]))

(defonce ^:private local-contexts (atom {}))
(defonce ^:private sync-cache (atom {}))
(defonce ^:private slot-active-contexts (atom {}))
(defonce ^:private client-tick-counter (atom 0))
(defonce ^:private tick-listener-registered? (atom false))

(def ^:private keepalive-interval-ticks 10)

(defn active-contexts [] @local-contexts)

(defn latest-sync
  [player-uuid]
  (get @sync-cache player-uuid))

(defn- player-runtime-state
  [player-uuid]
  (or (ability-runtime/get-player-state player-uuid)
      (latest-sync player-uuid)))

(defn- resolve-slot-skill-id
  [player-uuid key-idx]
  (when-let [state (player-runtime-state player-uuid)]
    (let [preset (:preset-data state)
          active (or (:active-preset preset) 0)
          binding (get-in preset [:slots [active key-idx]])]
      (when (and (vector? binding) (= 2 (count binding)))
        (ability-runtime/client-get-skill-by-controllable (first binding) (second binding))))))

(defn- slot-key
  [player-uuid key-idx]
  [player-uuid (int key-idx)])

(defn- abort-slot-contexts-for-player!
  [player-uuid]
  (doseq [[[uuid key-idx] ctx-id] @slot-active-contexts]
    (when (= uuid player-uuid)
      (when (get @local-contexts ctx-id)
        (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
        (ability-runtime/client-terminate-context! ctx-id nil)
        (swap! local-contexts dissoc ctx-id))
      (swap! slot-active-contexts dissoc [uuid key-idx]))))

(defn- refresh-local-context! [ctx-id]
  (if-let [ctx-map (ability-runtime/client-get-context ctx-id)]
    (swap! local-contexts assoc ctx-id ctx-map)
    (swap! local-contexts dissoc ctx-id)))

(defn- send-keepalive! [ctx-id]
  (ability-net/send-to-server! catalog/MSG-CTX-KEEPALIVE {:ctx-id ctx-id}))

(defn tick-client!
  []
  (let [tick (swap! client-tick-counter inc)]
    (when-let [tick-input-fn (resolve 'cn.li.forge1201.client.ability-input/tick-input!)]
      (@tick-input-fn))
    (when-let [tick-particles-fn (resolve 'cn.li.forge1201.client.effects.particle-bridge/tick-particles!)]
      (@tick-particles-fn))
    (when-let [tick-sounds-fn (resolve 'cn.li.forge1201.client.effects.sound-bridge/tick-sounds!)]
      (@tick-sounds-fn))
    (when (zero? (mod tick keepalive-interval-ticks))
      (doseq [ctx-id (keys @local-contexts)]
        (send-keepalive! ctx-id)))))

(defn- on-client-tick [^TickEvent$ClientTickEvent evt]
  (when (= TickEvent$Phase/END (.phase evt))
    (tick-client!)))

(defn- on-ctx-establish!
  [{:keys [ctx-id server-id]}]
  (ability-runtime/client-transition-to-alive! ctx-id server-id nil)
  (refresh-local-context! ctx-id))

(defn- on-ctx-terminate!
  [{:keys [ctx-id]}]
  (ability-runtime/client-terminate-context! ctx-id nil)
  (swap! local-contexts dissoc ctx-id))

(defn- on-ctx-channel!
  [{:keys [ctx-id channel payload]}]
  (ability-runtime/client-send-context-local! ctx-id channel payload))

(defn- on-sync-ability!
  [{:keys [uuid ability-data]}]
  (when uuid
    (ability-runtime/client-update-ability-data! uuid ability-data)
    (swap! sync-cache update uuid (fnil assoc {}) :ability-data ability-data)))

(defn- on-sync-resource!
  [{:keys [uuid resource-data]}]
  (when uuid
    (ability-runtime/client-update-resource-data! uuid resource-data)
    (client-state/clear-client-activated!)
    (swap! sync-cache update uuid (fnil assoc {}) :resource-data resource-data)))

(defn- on-sync-cooldown!
  [{:keys [uuid cooldown-data]}]
  (when uuid
    (ability-runtime/client-update-cooldown-data! uuid cooldown-data)
    (swap! sync-cache update uuid (fnil assoc {}) :cooldown-data cooldown-data)))

(defn- on-sync-preset!
  [{:keys [uuid preset-data]}]
  (when uuid
    (let [old-active (get-in (latest-sync uuid) [:preset-data :active-preset])
          new-active (:active-preset preset-data)]
      (when (and (some? old-active)
                 (some? new-active)
                 (not= old-active new-active))
        (abort-slot-contexts-for-player! uuid)))
    (ability-runtime/client-update-preset-data! uuid preset-data)
    (swap! sync-cache update uuid (fnil assoc {}) :preset-data preset-data)))

(defn register-push-handlers!
  []
  (net-client/register-push-handler! catalog/MSG-CTX-ESTABLISH on-ctx-establish!)
  (net-client/register-push-handler! catalog/MSG-CTX-TERMINATE on-ctx-terminate!)
  (net-client/register-push-handler! catalog/MSG-CTX-CHANNEL on-ctx-channel!)
  (net-client/register-push-handler! catalog/MSG-SYNC-ABILITY on-sync-ability!)
  (net-client/register-push-handler! catalog/MSG-SYNC-RESOURCE on-sync-resource!)
  (net-client/register-push-handler! catalog/MSG-SYNC-COOLDOWN on-sync-cooldown!)
  (net-client/register-push-handler! catalog/MSG-SYNC-PRESET on-sync-preset!))

(defn on-key-down!
  [player-uuid skill-id]
  (let [ctx-map (ability-runtime/client-new-context player-uuid skill-id)]
    (ability-runtime/client-register-context! ctx-map)
    (swap! local-contexts assoc (:id ctx-map) ctx-map)
    (ability-net/send-to-server! catalog/MSG-CTX-BEGIN-LINK
                                 {:ctx-id (:id ctx-map) :skill-id skill-id})
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-DOWN
                                 {:ctx-id (:id ctx-map) :skill-id skill-id})
    ctx-map))

(defn on-key-tick!
  [ctx-id]
  (when-let [ctx-map (get @local-contexts ctx-id)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-TICK
                                 {:ctx-id ctx-id :skill-id (:skill-id ctx-map)})))

(defn on-key-up!
  [ctx-id]
  (when (get @local-contexts ctx-id)
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-UP {:ctx-id ctx-id})
    (ability-runtime/client-terminate-context! ctx-id nil)
    (swap! local-contexts dissoc ctx-id)))

(defn on-slot-key-down!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-not (get @slot-active-contexts k)
      (when-let [skill-id (resolve-slot-skill-id player-uuid key-idx)]
        (let [ctx-map (on-key-down! player-uuid skill-id)]
          (swap! slot-active-contexts assoc k (:id ctx-map))
          ctx-map)))))

(defn on-slot-key-tick!
  [player-uuid key-idx]
  (when-let [ctx-id (get @slot-active-contexts (slot-key player-uuid key-idx))]
    (on-key-tick! ctx-id)))

(defn on-slot-key-up!
  [player-uuid key-idx]
  (let [k (slot-key player-uuid key-idx)]
    (when-let [ctx-id (get @slot-active-contexts k)]
      (on-key-up! ctx-id)
      (swap! slot-active-contexts dissoc k))))

(defn abort-all!
  []
  (doseq [ctx-id (keys @local-contexts)]
    (ability-net/send-to-server! catalog/MSG-SKILL-KEY-ABORT {:ctx-id ctx-id})
    (ability-runtime/client-terminate-context! ctx-id nil))
  (reset! slot-active-contexts {})
  (reset! local-contexts {}))

(defn init! []
  (register-push-handlers!)
  (when (compare-and-set! tick-listener-registered? false true)
    (.addListener (MinecraftForge/EVENT_BUS)
                  EventPriority/NORMAL false TickEvent$ClientTickEvent
                  (reify java.util.function.Consumer
                    (accept [_ evt] (on-client-tick evt)))))
  (log/info "Ability client runtime initialized"))
