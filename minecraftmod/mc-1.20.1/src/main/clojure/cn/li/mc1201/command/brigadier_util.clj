(ns cn.li.mc1201.command.brigadier-util
  "Shared Brigadier helpers used by both Forge and Fabric command registration.

  These helpers only depend on vanilla Brigadier / Minecraft command APIs, so
  they belong in mc1201 rather than platform loader modules."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [com.mojang.brigadier.arguments StringArgumentType IntegerArgumentType FloatArgumentType BoolArgumentType]
           [com.mojang.brigadier.context CommandContext]
           [net.minecraft.commands.arguments EntityArgument]))

(defn entity-arg-player-type []
  (EntityArgument/player))

(defn entity-arg-get-player [^CommandContext brigadier-ctx arg-name]
  (EntityArgument/getPlayer brigadier-ctx arg-name))

(defn map-argument-type
  "Map DSL argument type to a Brigadier ArgumentType instance."
  [arg-spec]
  (case (:type arg-spec)
    :player (entity-arg-player-type)
    :string (StringArgumentType/string)
    :word (StringArgumentType/word)
    :greedy-string (StringArgumentType/greedyString)
    :integer (IntegerArgumentType/integer)
    :float (FloatArgumentType/floatArg)
    :boolean (BoolArgumentType/bool)
    :enum (StringArgumentType/word)
    (StringArgumentType/string)))

(defn extract-argument-value
  "Extract a typed argument value from a Brigadier CommandContext."
  [^CommandContext brigadier-ctx arg-name arg-type]
  (try
    (case arg-type
      :player (entity-arg-get-player brigadier-ctx arg-name)
      :string (StringArgumentType/getString brigadier-ctx arg-name)
      :word (StringArgumentType/getString brigadier-ctx arg-name)
      :greedy-string (StringArgumentType/getString brigadier-ctx arg-name)
      :integer (IntegerArgumentType/getInteger brigadier-ctx arg-name)
      :float (FloatArgumentType/getFloat brigadier-ctx arg-name)
      :boolean (BoolArgumentType/getBool brigadier-ctx arg-name)
      :enum (StringArgumentType/getString brigadier-ctx arg-name)
      (StringArgumentType/getString brigadier-ctx arg-name))
    (catch Exception e
      (log/warn "Failed to extract argument" arg-name ":" (ex-message e))
      nil)))

(defn extract-all-arguments
  "Extract all DSL-declared arguments from a Brigadier CommandContext."
  [^CommandContext brigadier-ctx arg-specs]
  (into {}
        (keep (fn [arg-spec]
                (let [arg-name (name (:name arg-spec))
                      arg-type (:type arg-spec)
                      value (extract-argument-value brigadier-ctx arg-name arg-type)]
                  (when value
                    [(keyword arg-name) value]))))
        arg-specs))
