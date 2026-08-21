(ns cn.li.mcmod.platform.teleportation
  "Minecraft-free relay for the neutral teleportation Host Port."
  (:require [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]))

(defn current []
  (get-in @(fw/fw-atom) [:platform :teleportation]))

(defn available? [] (boolean (current)))

(defn- named-position-store []
  (when-let [fw-atom (fw/fw-atom)]
    (platform/get-adapter fw-atom :named-position-store)))

(defn named-position-available? [] (boolean (named-position-store)))

(defn- named-position-call [operation & args]
  (let [ops (named-position-store)
        handler (get ops operation)]
    (when-not handler
      (throw (ex-info "Named position operation is not installed"
                      {:operation operation :installed (keys ops)})))
    (apply handler args)))

(defn get-saved-location [owner location-name]
  (named-position-call :get-location (str owner) (str location-name)))

(defn list-saved-locations [owner]
  (or (named-position-call :list-locations (str owner)) []))

(defn save-saved-location! [owner location-name world-id x y z]
  (boolean (named-position-call :save-location! (str owner) (str location-name)
                                (str world-id) (double x) (double y) (double z))))

(defn delete-saved-location! [owner location-name]
  (boolean (named-position-call :delete-location! (str owner) (str location-name))))

(defn- call [kind & args]
  (let [ops (current)
        f (get ops kind)]
    (when-not f
      (throw (ex-info "Required teleportation operation is not installed"
                      {:operation kind :installed (keys ops)})))
    (apply f args)))

;; Bridges a server-computed destination from a combat-core world-effect
;; case (AC layer, mints the token right after resolving/validating a
;; target) to the platform teleport-approved-target! implementation below
;; (which redeems it). Both sides already depend on this neutral namespace,
;; so it's the natural shared home -- no new module needed. Tokens are only
;; ever minted and redeemed within the same synchronous intent dispatch (no
;; real time passes between a combat-core query and its own world-effect
;; step), so a short in-memory TTL is a safety margin, not a real
;; requirement; it exists so a redemption bug can't leak entries forever
;; rather than to bridge an actual asynchronous gap.
(defonce ^:private approval-tokens* (atom {}))
(def ^:private token-ttl-ms 10000)

(defn- prune-expired [tokens now]
  (into {} (filter (fn [[_ v]] (> (long (:expires-at v)) now))) tokens))

(defn mint-approval-token!
  "Store `data` under a fresh opaque token, redeemable exactly once via
   redeem-approval-token! within token-ttl-ms."
  [data]
  (let [token (str (java.util.UUID/randomUUID))
        now (System/currentTimeMillis)]
    (swap! approval-tokens*
           (fn [tokens]
             (assoc (prune-expired tokens now) token
                    {:data data :expires-at (+ now token-ttl-ms)})))
    token))

(defn redeem-approval-token!
  "Consume and return the data behind `token`, or nil if unknown/expired/
   already redeemed."
  [token]
  (let [now (System/currentTimeMillis)
        [old _] (swap-vals! approval-tokens* dissoc token)
        entry (get old token)]
    (when (and entry (> (long (:expires-at entry)) now))
      (:data entry))))

(defn teleport-player!
  [owner world-id x y z]
  (boolean (call :teleport-player! owner world-id
                 (double x) (double y) (double z))))

(defn teleport-with-entities!
  [owner world-id x y z radius]
  (call :teleport-with-entities! owner world-id
        (double x) (double y) (double z) (double radius)))

(defn teleport-approved-target!
  "Teleport using a server-issued approval token from a target query.
   Loader code owns target, collision, dimension and passenger validation."
  [owner ability-id approval-token mode]
  (boolean (call :teleport-approved-target!
                 owner ability-id approval-token mode)))

(defn reset-fall-damage! [entity-id]
  (boolean (call :reset-fall-damage! (str entity-id))))
