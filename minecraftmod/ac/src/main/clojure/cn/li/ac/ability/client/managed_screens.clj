(ns cn.li.ac.ability.client.managed-screens
  "Shared runtime store for client-managed screens.

  Screen modules no longer own private runtime singletons; they store owner-keyed
  screen state in this shared runtime and require the caller to pass the owner
  explicitly for all reads/writes.

  State stored in Framework [:service :managed-screens]."
  (:require [cn.li.mcmod.framework :as fw]))

(def ^:private default-managed-screen-runtime-state
  {:active-owners {} :states {}})

(def ^:private ms-path [:service :managed-screens])

(defn- managed-screen-state-atom []
  (if-let [fw-atom (fw/fw-atom)]
    (or (get-in @fw-atom ms-path)
        (let [a (atom default-managed-screen-runtime-state)]
          (swap! fw-atom assoc-in ms-path a) a))
    (atom default-managed-screen-runtime-state)))

;; Backward-compatible factory (used by client_ui_hooks.clj)
(defn create-managed-screen-runtime []
  {::runtime ::managed-screen-runtime
   :state* (managed-screen-state-atom)})

(defn managed-screen-state-snapshot [] @(managed-screen-state-atom))

(defn reset-managed-screen-state-for-test! []
  (reset! (managed-screen-state-atom) default-managed-screen-runtime-state) nil)

(defn set-active-owner! [screen-id owner-key]
  (swap! (managed-screen-state-atom) assoc-in [:active-owners screen-id] owner-key) owner-key)

(defn active-owner [screen-id]
  (get-in (managed-screen-state-snapshot) [:active-owners screen-id]))

(defn screen-state [screen-id owner-key default-state]
  (get-in (managed-screen-state-snapshot) [:states screen-id owner-key] default-state))

(defn update-screen-state! [screen-id owner-key default-state f & args]
  (swap! (managed-screen-state-atom)
         (fn [store] (apply update-in store [:states screen-id owner-key] (fnil f default-state) args))))

(defn clear-screen-state! [screen-id owner-key]
  (swap! (managed-screen-state-atom)
         (fn [store]
           (let [store (update-in store [:states screen-id] dissoc owner-key)]
             (if (= owner-key (get-in store [:active-owners screen-id]))
               (update store :active-owners dissoc screen-id) store)))) nil)
