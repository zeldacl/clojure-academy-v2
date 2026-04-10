(ns cn.li.forge1201.client.effects.sound-bridge
  "CLIENT-ONLY sound effect bridge (Forge layer)."
  (:require [cn.li.ac.ability.client.effects.sounds :as ac-sounds]
            [cn.li.mcmod.util.log :as log])
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.core Holder]
           [net.minecraft.sounds SoundSource]
           [net.minecraft.resources ResourceLocation]))


(defn- play-sound-effect
  "Play a sound effect."
  [sound-cmd]
  (try
    (when-let [^Minecraft mc (Minecraft/getInstance)]
      (when-let [player (.player mc)]
        (when-let [level (.level player)]
          (let [{:keys [sound-id volume pitch x y z]} sound-cmd
                sound-loc (ResourceLocation. ^String sound-id)
                pos-x (or x (.getX player))
                pos-y (or y (.getY player))
              pos-z (or z (.getZ player))
              sound-event (try
                      (let [regs-cls (Class/forName "net.minecraft.core.registries.BuiltInRegistries")
                        field-sound (.getField regs-cls "SOUND_EVENT")
                        sound-registry (.get field-sound nil)
                        get-method (.getMethod (class sound-registry) "get" (into-array Class [Object]))
                        holder (.invoke get-method sound-registry (object-array [sound-loc]))]
                      (when holder
                        (.value ^Holder holder)))
                      (catch Exception _ nil))]
            (when sound-event
            (.playLocalSound level pos-x pos-y pos-z
                     sound-event
                           SoundSource/PLAYERS
                           (float volume)
                           (float pitch)
                     false))))))
    (catch Exception e
      (log/error "Error playing sound effect" e))))

(defn tick-sounds!
  "Poll and play queued sound effects. Called every client tick."
  []
  (try
    (doseq [sound-cmd (ac-sounds/poll-sound-effects!)]
      (play-sound-effect sound-cmd))
    (catch Exception e
      (log/error "Error in sound tick" e))))

(defn init!
  "Initialize sound bridge."
  []
  (log/info "Sound effect bridge initialized"))
