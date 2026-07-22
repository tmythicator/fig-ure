(ns fig-ure.telemetry
  "Background worker for batching and pushing telemetry metrics to InfluxDB Cloud."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :fig-ure/telemetry [_ config]
  (println "Initializing telemetry worker..." config)
  {:status :ready})

(defmethod ig/halt-key! :fig-ure/telemetry [_ state]
  (println "Halting telemetry worker..." state))

(comment
  ;; Interactive REPL scratchpad
  )
