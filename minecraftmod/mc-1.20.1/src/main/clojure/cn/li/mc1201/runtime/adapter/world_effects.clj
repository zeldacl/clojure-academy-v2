(ns cn.li.mc1201.runtime.adapter.world-effects
  "Shared IWorldEffects adapter factory.

  Platform namespaces provide the server lookup plus platform-specific
  entity/lightning/explosion callbacks; this namespace owns the shared
  world-query orchestration and protocol-var installation."
  (:require [cn.li.mc1201.runtime.adapter-support :as adapter-support]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mc1201.runtime.world-effects-core :as core]
            [cn.li.mcmod.platform.world-effects :as pwe]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.level.block Block]))

(defn- resolve-level [server resolve-level-fn world-id]
  (when server
    (resolve-level-fn server world-id)))

(defn create-world-effects
  [server-fn {:keys [resolve-level-fn spawn-lightning-fn create-explosion-fn spawn-projectile-fn get-entities-in-aabb-fn resolve-entity-id-fn block-id-fn get-entity-by-uuid-fn]
              :or {resolve-level-fn query-core/resolve-level-strict
                   get-entity-by-uuid-fn query-core/get-entity-by-uuid
                   resolve-entity-id-fn (fn [^Entity entity] (str (.getDescriptionId (.getType entity))))
                   block-id-fn (fn [^Block block _block-state] (str (.getDescriptionId block)))}}]
  (let [spawn-lightning! (or spawn-lightning-fn (fn [_level _x _y _z] false))
        create-explosion! (or create-explosion-fn (fn [_level _x _y _z _radius _fire?] false))
        spawn-projectile! (or spawn-projectile-fn
                              (fn [level projectile-spec]
                                (core/spawn-projectile-in-level!
                                  level projectile-spec resolve-entity-id-fn get-entity-by-uuid-fn)))
        get-entities-in-aabb (or get-entities-in-aabb-fn (fn [_level _aabb] []))]
    (reify pwe/IWorldEffects
      (spawn-lightning! [_ world-id x y z]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (spawn-lightning! level x y z)))
          (catch Exception e
            (log/warn "Failed to spawn lightning:" (ex-message e))
            false)))
      (create-explosion! [_ world-id x y z radius fire?]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (boolean (create-explosion! level x y z radius fire?))))
          (catch Exception e
            (log/warn "Failed to create explosion:" (ex-message e))
            false)))
      (spawn-projectile! [_ world-id projectile-spec]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (spawn-projectile! level projectile-spec)))
          (catch Exception e
            (log/warn "Failed to spawn projectile:" (ex-message e))
            {:success? false})))
      (find-entities-in-radius [_ world-id x y z radius]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (core/entities-in-radius
                level
                x y z radius
                get-entities-in-aabb
                resolve-entity-id-fn)))
          (catch Exception e
            (log/warn "Failed to find entities:" (ex-message e))
            [])))
      (find-blocks-in-radius [_ world-id x y z radius block-predicate]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (core/find-blocks-in-radius-in-level
                level x y z radius block-predicate block-id-fn)))
          (catch Exception e
            (log/warn "Failed to find blocks:" (ex-message e))
            [])))
      (play-sound! [_ world-id x y z sound-id source volume pitch]
        (try
          (when-let [^MinecraftServer server (server-fn)]
            (when-let [^ServerLevel level (resolve-level server resolve-level-fn world-id)]
              (boolean (core/play-sound-in-level! level x y z sound-id source volume pitch))))
          (catch Exception e
            (log/warn "Failed to play world sound:" (ex-message e))
            false))))))

(defn install-world-effects!
  [world-effects label]
  (adapter-support/install-adapter! #'pwe/*world-effects* world-effects label))