(ns cn.li.ac.ability.service.context-repository
  "State repository helpers for context-dispatcher runtime data.")

(defn dispatcher-state-atom
  [runtime]
  (:dispatcher-state* runtime))

(defn state-snapshot
  [runtime]
  @(dispatcher-state-atom runtime))

(defn swap-state!
  [runtime f & args]
  (apply swap! (dispatcher-state-atom runtime) f args))

(defn context-registry-snapshot
  [runtime]
  (:context-registry (state-snapshot runtime)))

(defn route-fns-snapshot
  [runtime]
  (:route-fns (state-snapshot runtime)))

(defn lifecycle-counters-map
  [runtime]
  (:lifecycle-counters (state-snapshot runtime)))

(defn assoc-context!
  [runtime key ctx]
  (swap-state! runtime assoc-in [:context-registry key] ctx)
  ctx)

(defn update-context-if-present!
  [runtime key f & args]
  (swap-state! runtime
               (fn [state]
                 (if (contains? (:context-registry state) key)
                   (apply update-in state [:context-registry key] f args)
                   state))))

(defn dissoc-context!
  [runtime key]
  (swap-state! runtime update :context-registry dissoc key)
  nil)

(defn clear-route-fns!
  [runtime]
  (swap-state! runtime assoc :route-fns {})
  nil)

(defn register-route-fns!
  [runtime owner-key routes]
  (swap-state! runtime assoc-in [:route-fns owner-key] routes)
  nil)

(defn reset-contexts!
  [runtime contexts]
  (swap-state! runtime
               (fn [state]
                 (-> state
                     (assoc :context-registry contexts)
                     (assoc :client-id-counter {})
                     (assoc :server-id-counter {}))))
  nil)
