(ns cn.li.mcmod.platform.teleportation
  "Minecraft-free relay for the neutral teleportation Host Port."
  (:require [cn.li.mcmod.framework :as fw]))

(defn current []
  (get-in @(fw/fw-atom) [:platform :teleportation]))

(defn available? [] (boolean (current)))

(defn- call [kind & args]
  (let [ops (current)
        f (get ops kind)]
    (when-not f
      (throw (ex-info "Required teleportation operation is not installed"
                      {:operation kind :installed (keys ops)})))
    (apply f args)))

(defn teleport-player!
  [owner world-id x y z]
  (boolean (call :teleport-player! owner world-id
                (double x) (double y) (double z))))

(defn teleport-with-entities!
  [owner world-id x y z radius]
  (call :teleport-with-entities! owner world-id
        (double x) (double y) (double z) (double radius)))

(defn teleport-approved-location!
  "Teleport to a server-resolved saved location.

   The caller supplies an opaque location id; the loader port resolves the
   stored destination and performs collision/dimension/entity validation."
  [owner ability-id location-id radius]
  (boolean (call :teleport-approved-location!
                owner ability-id location-id (double radius))))

(defn teleport-approved-target!
  "Teleport using a server-issued approval token from a target query.
   Loader code owns target, collision, dimension and passenger validation."
  [owner ability-id approval-token mode]
  (boolean (call :teleport-approved-target!
                owner ability-id approval-token mode)))
