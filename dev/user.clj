(ns user
  "Development REPL namespace for interactive REPL-Driven Development (RDD)."
  (:require [fig-ure.core :as core]
            [fig-ure.sensors]
            [fig-ure.telemetry]
            [fig-ure.stream]
            [fig-ure.api]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]))

(integrant.repl/set-prep! (constantly core/config))

(comment
  ;; REPL-driven development commands:
  ;; Type or evaluate these forms in your REPL connected to this namespace:
  (go)       ;; Start the system component graph
  (halt)     ;; Stop the running system
  (reset)    ;; Reload code changes and restart the system cleanly!
  )
