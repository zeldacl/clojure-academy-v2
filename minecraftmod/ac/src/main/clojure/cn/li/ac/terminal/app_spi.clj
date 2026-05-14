(ns cn.li.ac.terminal.app-spi
  "Terminal app SPI and adapters.
   
   Terminal apps can still be registered as plain maps, but this namespace
   also allows richer protocol-based implementations for future expansion."
  (:require [clojure.string :as str]))

(defprotocol TerminalApp
  "Protocol for terminal applications. Implementations can expose app metadata
   and a GUI launcher without forcing callers to depend on map keys."
  (app-id [this] "Keyword identifier for the app")
  (app-name [this] "Display name")
  (app-icon [this] "Texture path or icon id")
  (app-description [this] "Short description")
  (app-gui-fn [this] "GUI launcher symbol or function")
  (app-available? [this player] "Predicate: is the app available to this player?")
  (app-category [this] "Optional category keyword"))

(defn terminal-app?
  "Return true if x implements TerminalApp."
  [x]
  (satisfies? TerminalApp x))

(defn normalize-app-spec
  "Convert a TerminalApp implementation or map into a canonical app spec map.
   
   Supported fields:
   - :id
   - :name
   - :icon
   - :description
   - :gui-fn
   - :available-fn
   - :category"
  [app]
  (cond
    (map? app)
    app

    (terminal-app? app)
    {:id (app-id app)
     :name (app-name app)
     :icon (app-icon app)
     :description (app-description app)
     :gui-fn (app-gui-fn app)
     :available-fn (fn [player] (app-available? app player))
     :category (app-category app)}

    :else
    (throw (ex-info "Unsupported terminal app value"
                    {:value app :type (type app)}))))

(defn app-launcher
  "Resolve the app GUI launcher into a function if possible.
   
   Accepts either a function or a symbol pointing to a function. Returns nil
   if the launcher cannot be resolved."
  [gui-fn]
  (cond
    (fn? gui-fn) gui-fn
    (symbol? gui-fn) (try (requiring-resolve gui-fn)
                          (catch Throwable _ nil))
    :else nil))

(defrecord StaticTerminalApp
  [id name icon description gui-fn available-fn category]
  TerminalApp
  (app-id [_] id)
  (app-name [_] name)
  (app-icon [_] icon)
  (app-description [_] description)
  (app-gui-fn [_] gui-fn)
  (app-available? [_ player]
    (if available-fn
      (try
        (boolean (available-fn player))
        (catch Throwable _ false))
      true))
  (app-category [_] category))

(defn ->static-app
  "Create a protocol-based terminal app value from a plain spec map.
   
   Useful for tests or future migrations that want stable records rather than
   ad-hoc maps."
  [app-spec]
  (let [{:keys [id name icon description gui-fn available-fn category]} (normalize-app-spec app-spec)]
    (->StaticTerminalApp id name icon description gui-fn available-fn category)))
