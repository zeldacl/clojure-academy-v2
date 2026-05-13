(ns cn.li.mc1201.runtime.entity-lookup
  "Shared Minecraft-side entity/player lookup helpers (no loader API imports)."
  (:import [java.util UUID]
           [net.minecraft.core.registries Registries]
           [net.minecraft.resources ResourceKey ResourceLocation]
           [net.minecraft.server MinecraftServer]
           [net.minecraft.server.level ServerLevel]))

(defn get-player-by-uuid
  [^MinecraftServer server player-uuid]
  (when server
    (try
      (let [uuid (UUID/fromString (str player-uuid))]
        (.getPlayer (.getPlayerList server) uuid))
      (catch Exception _
        nil))))

(defn resolve-level
  [^MinecraftServer server world-id]
  (when server
    (if (or (nil? world-id) (= "" (str world-id)))
      (.overworld server)
      (try
        (let [rid (ResourceLocation. (str world-id))
              key (ResourceKey/create Registries/DIMENSION rid)]
          (or (.getLevel server key) (.overworld server)))
        (catch Exception _
          (.overworld server))))))

(defn resolve-level-strict
  [^MinecraftServer server world-id]
  (when (and server world-id (not= "" (str world-id)))
    (try
      (let [rid (ResourceLocation. (str world-id))
            key (ResourceKey/create Registries/DIMENSION rid)]
        (.getLevel server key))
      (catch Exception _
        nil))))

(defn get-entity-by-uuid
  [^ServerLevel level entity-uuid]
  (when level
    (try
      (.getEntity level (UUID/fromString (str entity-uuid)))
      (catch Exception _
        nil))))
