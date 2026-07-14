(ns cn.li.mcmod.spi.vanilla-input-control
  "Vanilla input suppression SPI - abstracts LMB/RMB suppression across platforms.

  Uses a plain function map instead of `defprotocol` — see key-scheme-provider
  for the full rationale (cross-module AOT ClassLoader isolation).

  Contract: {:suppress! (fn [mc]) :restore! (fn [mc])}"
  (:require [cn.li.mcmod.runtime.install :as install]
            [cn.li.mcmod.util.log :as log]))

(def ^:private suppressor nil)

(defn- valid-suppressor?
  [suppressor-impl]
  (and (map? suppressor-impl)
       (fn? (:suppress! suppressor-impl))
       (fn? (:restore! suppressor-impl))))

(defn install-suppressor!
  "Install the SPI implementation (called by Forge/Fabric platform).
  suppressor-impl must be a map with :suppress! and :restore! fns."
  [suppressor-impl]
  (assert (valid-suppressor? suppressor-impl)
          "suppressor must be a map with :suppress! and :restore! fns")
  (install/install-root! #'suppressor suppressor-impl)
  (log/info "VanillaInputSuppressor installed")
  nil)

(defn require-suppressor
  "Get the installed suppressor, fail if not available."
  []
  (or suppressor
      (throw (ex-info "VanillaInputSuppressor not installed"
                     {:error :missing-spi}))))

(defn suppress-vanilla-input!
  "Suppress Vanilla attack/use through the installed suppressor (facade)."
  [minecraft-client]
  (let [s (require-suppressor)]
    ((:suppress! s) minecraft-client)))

(defn restore-vanilla-inputs!
  "Restore Vanilla input through the installed suppressor (facade)."
  [minecraft-client]
  (let [s (require-suppressor)]
    ((:restore! s) minecraft-client)))
