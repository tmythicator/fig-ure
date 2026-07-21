(ns fig-ure.api
  "Edge API Gateway exposed via Cloudflare Tunnel for health checks and telemetry retrieval."
  (:require [integrant.core :as ig]))

(defmethod ig/init-key :fig-ure/api [_ config]
  (println "Initializing Edge API Gateway..." config)
  {:status :ready})

(defmethod ig/halt-key! :fig-ure/api [_ state]
  (println "Halting Edge API Gateway..." state))

(comment
  ;; Interactive REPL scratchpad
  )
