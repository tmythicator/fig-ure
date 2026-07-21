(ns fig-ure.core
  (:require [integrant.core :as ig]))

;; System Configuration Blueprint
(def config
  {:fig-ure/sensors   {}
   :fig-ure/telemetry {:buffer-path "data/telemetry.db"}
   :fig-ure/stream    {:snapshot-sec 300}
   :fig-ure/api       {:port 3000}})

(defn -main
  [& _args]
  (println "Starting fig-ure edge node services...")
  (ig/init config))

(comment
  ;; REPL-driven experimentation block
  (def system (ig/init config))
  (ig/halt! system))
