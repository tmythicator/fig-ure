(ns user
  "Development REPL namespace for interactive REPL-Driven Development (RDD)."
  (:require [fig-ure.core :as core]
            [fig-ure.sensors]
            [fig-ure.telemetry]
            [fig-ure.stream]
            [fig-ure.api]
            [integrant.repl :refer [go halt  reset]]))

(integrant.repl/set-prep! (constantly core/config))

(comment
  (go)       ;; Start the system component graph
  (halt)     ;; Stop the running system
  (reset)    ;; Reload code changes and restart the system cleanly!
  )
