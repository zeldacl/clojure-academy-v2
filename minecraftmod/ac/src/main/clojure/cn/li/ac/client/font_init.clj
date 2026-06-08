(ns cn.li.ac.client.font-init
  "Initialize AcademyCraft custom fonts (AC_Normal, AC_Bold, AC_Italic).

  Ported from cn.academy.client.ClientResources/checkFontInit.

  Loads a system TrueType font (Microsoft YaHei at 24pt) and registers
  three variants via cn.li.mcmod.gui.font:

  - :ac-normal  — PLAIN
  - :ac-bold   — BOLD (derived)
  - :ac-italic — ITALIC (derived)

  Fallback chain: Microsoft YaHei → SimHei → Arial → Java default"
  (:require [cn.li.mc1201.gui.cgui.font :as font]
            [cn.li.mcmod.util.log :as log])
  (:import [java.awt Font]))

(defonce ^:private fonts-initialized? (atom false))

(def ^:private fallback-font-names
  ["Microsoft YaHei"
   "微软雅黑"
   "SimHei"
   "Adobe Heiti Std R"
   "Arial"
   "Monospace"])

(def base-font-size 24)

(defn- load-and-register!
  "Load the system font and register AC_Normal, AC_Bold, AC_Italic."
  []
  (try
    (let [normal-font (font/load-system-font fallback-font-names Font/PLAIN base-font-size)
          bold-font   (font/load-system-font fallback-font-names Font/BOLD base-font-size)
          italic-font (font/load-system-font fallback-font-names Font/ITALIC base-font-size)]
      (font/register-font! :ac-normal normal-font)
      (font/register-font! :ac-bold bold-font)
      (font/register-font! :ac-italic italic-font)
      (log/info "AC fonts initialized: ac-normal, ac-bold, ac-italic (base size:" base-font-size "pt)"))
    (catch Exception e
      (log/error "Failed to initialize AC fonts:" (ex-message e)))))

(defn init-fonts!
  "Initialize AcademyCraft custom fonts. Idempotent."
  []
  (when (compare-and-set! fonts-initialized? false true)
    (load-and-register!)))
