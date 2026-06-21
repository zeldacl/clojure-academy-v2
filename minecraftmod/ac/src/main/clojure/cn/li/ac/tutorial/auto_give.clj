(ns cn.li.ac.tutorial.auto-give
  "Tutorial item auto-give on first player login.

  Mirrors upstream AcademyCraft TutorialData: reads/writes a boolean
  directly in player persistent NBT (like @SerializeIncluded), completely
  independent of the runtime store lifecycle.  This guarantees reliable
  persistence because Minecraft handles player NBT save/load natively."
  (:require [cn.li.ac.tutorial.config :as tut-config]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.item :as pitem]
            [cn.li.mcmod.platform.player-persistent-data :as player-pd]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.server.level ServerPlayer]
           [net.minecraft.nbt CompoundTag]))

(def tutorial-item-id "my_mod:tutorial")
(def ^:private nbt-key "academy_tutorial_acquired")

(defn- tutorial-acquired-in-nbt?
  [^CompoundTag tag]
  (and (.contains tag nbt-key) (.getBoolean tag nbt-key)))

(defn auto-give-on-login!
  "Check NBT flag directly (matching upstream @SerializeIncluded boolean).
  Called with just the ServerPlayer, resolved in the lifecycle hook."
  [^ServerPlayer player]
  (when (tut-config/give-cloud-terminal-enabled?)
    (let [tag (player-pd/get-persistent-data! player)]
      (when-not (tutorial-acquired-in-nbt? tag)
        (try
          (when-let [stack (pitem/create-item-stack-by-id tutorial-item-id 1)]
            (entity/player-give-item-stack! player stack)
            (.putBoolean tag nbt-key true)
            (log/info "Tutorial item auto-given to player" {:uuid (str (.getUUID player))}))
          (catch Exception e
            (log/warn "Failed to auto-give tutorial item:" (ex-message e))))))))
