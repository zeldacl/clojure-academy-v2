(ns cn.li.mcmod.events.world-save-cache
  "Runtime component for carrying world-save payloads across
  world save/unload and the next matching world load."
  (:require [cn.li.mcmod.platform.world-owner-key :as world-owner-key]))

(defn world-key
  [world]
  (world-owner-key/world-key world))

(defn create-world-save-cache-runtime
  []
  {::runtime ::world-save-cache-runtime
   :pending-save-data* (atom {})})

(defonce ^:private installed-world-save-cache-runtime
  (create-world-save-cache-runtime))

(def ^:dynamic *world-save-cache-runtime*
  installed-world-save-cache-runtime)

(defn- world-save-cache-runtime?
  [runtime]
  (and (map? runtime)
       (= ::world-save-cache-runtime (::runtime runtime))
       (some? (:pending-save-data* runtime))))

(defn call-with-world-save-cache-runtime
  [runtime f]
  (when-not (world-save-cache-runtime? runtime)
    (throw (ex-info "Expected world save cache runtime"
                    {:runtime runtime})))
  (binding [*world-save-cache-runtime* runtime]
    (f)))

(defmacro with-world-save-cache-runtime
  [runtime & body]
  `(call-with-world-save-cache-runtime ~runtime (fn [] ~@body)))

(defn- current-runtime
  []
  *world-save-cache-runtime*)

(defn- pending-save-data-atom
  []
  (:pending-save-data* (current-runtime)))

(defn- pending-save-data-snapshot
  []
  @(pending-save-data-atom))

(defn- update-pending-save-data!
  [f & args]
  (apply swap! (pending-save-data-atom) f args))

(defn consume-saved-data!
  [world]
  (let [wid (world-key world)
        saved* (volatile! nil)]
    (update-pending-save-data!
      (fn [pending]
        (vreset! saved* (get pending wid))
        (dissoc pending wid)))
    @saved*))

(defn remember-saved-data!
  [world saved-data]
  (if (seq saved-data)
    (update-pending-save-data! assoc (world-key world) saved-data)
    (update-pending-save-data! dissoc (world-key world)))
  nil)

(defn clear-world-saved-data!
  [world]
  (update-pending-save-data! dissoc (world-key world))
  nil)

(defn clear-session-saved-data!
  [owner-or-session-id]
  (let [session-id (if (map? owner-or-session-id)
                     (first (world-key owner-or-session-id))
                     owner-or-session-id)]
    (update-pending-save-data!
      (fn [pending]
        (into {}
              (remove (fn [[[entry-session-id _world-id] _saved-data]]
                        (= session-id entry-session-id)))
              pending)))
    nil))

(defn world-save-cache-snapshot
  []
  (pending-save-data-snapshot))

(defn reset-world-save-cache-for-test!
  ([]
   (reset-world-save-cache-for-test! {}))
  ([pending-save-data]
   (reset! (pending-save-data-atom) (or pending-save-data {}))
   nil))
