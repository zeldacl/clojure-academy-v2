(ns cn.li.mc1201.registry.dispatch
  "Shared registry dispatch helpers for platform adapters."
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.registry.platform :as registry]))

(defn with-platform
  "Execute thunk under a specific platform dispatch context."
  [platform-version thunk]
  (binding [platform-dispatch/*platform-version* platform-version]
    (thunk)))

(defn register-item!
  [platform-version item-id item-instance]
  (with-platform platform-version #(registry/register-item item-id item-instance)))

(defn register-block!
  [platform-version block-id block-instance]
  (with-platform platform-version #(registry/register-block block-id block-instance)))

(defn get-registered-item
  [platform-version item-id]
  (with-platform platform-version #(registry/get-registered-item item-id)))

(defn get-registered-block
  [platform-version block-id]
  (with-platform platform-version #(registry/get-registered-block block-id)))
