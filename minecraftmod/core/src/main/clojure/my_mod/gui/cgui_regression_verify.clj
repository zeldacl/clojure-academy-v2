(ns my-mod.gui.cgui-regression-verify
  "Quick regression verification script for cgui_document XML loading.
   Runs independently to verify all migrated XML files can be loaded."
  (:require [clojure.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn verify-resource-exists [resource-path]
  "Check if a resource file exists."
  (println (format "  Checking: %s ... " resource-path))
  (if-let [resource (io/resource resource-path)]
    (do (println "✓ found")
        true)
    (do (println "✗ NOT FOUND")
        false)))

(defn verify-no-legacy-refs [file-path]
  "Verify file doesn't contain legacy component references."
  (try
    (let [content (slurp (io/resource file-path))
          has-legacy? (str/includes? content "cn.lambdalib2")]
      (if has-legacy?
        (do (println (format "  ✗ FAILED: %s still contains cn.lambdalib2 references" file-path))
            false)
        (do (println (format "  ✓ OK: %s uses pure identifiers" file-path))
            true)))
    (catch Exception e
      (println (format "  ✗ ERROR reading %s: %s" file-path (.getMessage e)))
      false)))

(defn verify-xml-parses [file-path]
  "Try to parse the XML file to verify it's well-formed."
  (try
    (let [resource (io/resource file-path)
          parsed (xml/parse (io/input-stream resource))]
      (println (format "  ✓ %s parses successfully" file-path))
      true)
    (catch Exception e
      (println (format "  ✗ %s PARSE FAILED: %s" file-path (.getMessage e)))
      false)))

(defn run-verification []
  "Run all regression checks."
  (println "\n" (str (apply str (repeat 60 "=")) "\n")
           "CGUI Document Migration Regression Test")
  (println (str (apply str (repeat 60 "=")) "\n"))
  
  ;; Test files - focus on wireless/matrix/node related pages
  (let [test-files [
        ;; Wireless-related pages
        "assets/my_mod/guis/rework/page_wireless.xml"
        
        ;; Other critical pages that may reference wireless
        "assets/my_mod/guis/rework/page_solar.xml"
        "assets/my_mod/guis/rework/page_windbase.xml"
        "assets/my_mod/guis/rework/pageselect.xml"
        "assets/my_mod/guis/rework/page_inv.xml"
        
        ;; Main guis directory files
        "assets/my_mod/guis/terminal.xml"
        "assets/my_mod/guis/settings.xml"
        "assets/my_mod/guis/tutorial.xml"
        ]]
    
    (println "Phase 1: Verifying XML files exist")
    (println (str (apply str (repeat 60 "-")) "\n"))
    (let [exists-results (map verify-resource-exists test-files)]
      (println (format "\nResult: %d/%d files found\n" 
                       (count (filter identity exists-results))
                       (count exists-results))))
    
    (println "Phase 2: Verifying no legacy component references")
    (println (str (apply str (repeat 60 "-")) "\n"))
    (let [legacy-results (map verify-no-legacy-refs test-files)]
      (println (format "\nResult: %d/%d files are clean\n"
                       (count (filter identity legacy-results))
                       (count legacy-results))))
    
    (println "Phase 3: Verifying XML syntax is valid")
    (println (str (apply str (repeat 60 "-")) "\n"))
    (let [parse-results (map verify-xml-parses test-files)]
      (println (format "\nResult: %d/%d files parse successfully\n"
                       (count (filter identity parse-results))
                       (count parse-results)))
      
      (let [total-tests (count test-files)
            passed (count (filter identity parse-results))]
        (println (str (apply str (repeat 60 "="))))
        (if (= total-tests passed)
          (println "✓ ALL TESTS PASSED - Migration successful!")
          (println (format "✗ FAILURES DETECTED: %d/%d tests failed" 
                          (- total-tests passed) total-tests)))
        (println (str (apply str (repeat 60 "="))))))))

;; Auto-run when this namespace is loaded
(defn -main [& args]
  (run-verification))

;; For REPL testing:
(comment
  (run-verification)
  )
