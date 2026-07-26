(ns fig-ure.telemetry
  "Background worker for batching and pushing telemetry metrics to InfluxDB Cloud or local storage."
  (:require
   [clojure.core :as c]
   [clojure.core.async :as async]
   [integrant.core :as ig]
   [fig-ure.sensors :as sensors]
   [fig-ure.stream :as stream]))

(def config
  {:camera-interval-ms 600000 ;; 10 min
   :sensor-interval-ms 5000
   :camera-buf-size 10
   :sensor-buf-size 200})

(defn- create-camera-chan
  ([] (create-camera-chan (:camera-buf-size config)))
  ([buf-size]
   (async/chan (async/sliding-buffer buf-size))))

(defn- create-sensor-chan
  ([] (create-sensor-chan (:sensor-buf-size config)))
  ([buf-size]
   (async/chan (async/sliding-buffer buf-size))))

(defn- start-generic-producer!
  [name out-chan stop-chan interval-ms produce-fn trans-fn]
  (async/go-loop []
    (let [[_val port] (async/alts! [stop-chan (async/timeout interval-ms)])]
      (if (= port stop-chan)
        (println "[" name " Producer] Received stop-signal. Exiting loop.")
        (let [res (produce-fn)]
          (when (= (:status res) :ok)
            (async/>! out-chan (trans-fn res)))
          (recur))))))

(defn- start-telemetry-pipeline!
  [sys-config]
  (let [stop-chan (async/chan)
        camera-chan (create-camera-chan (:camera-buf-size sys-config))
        sensor-chan (create-sensor-chan (:sensor-buf-size sys-config))]

    (start-generic-producer! "Sensor" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             sensors/read-bme280-readings
                             :readings)

    (start-generic-producer! "Camera" camera-chan stop-chan
                             (:camera-interval-ms sys-config)
                             stream/take-snapshot!
                             (fn [res]      {:event/type :camera-snapshot
                                             :file-path (:file-path res)
                                             :timestamp (:timestamp res)}))
    {:status :ready
     :stop-chan stop-chan
     :camera-chan camera-chan
     :sensor-chan sensor-chan}))

(defmethod ig/init-key :fig-ure/telemetry [_ config]
  (println "Initializing telemtry pipeline...")
  (start-telemetry-pipeline! config))

(defmethod ig/halt-key! :fig-ure/telemetry [_ state]
  (println "Halting telemetry worker..." state)
  (async/close! (:stop-chan state)))

(comment
  ;; Interactive REPL scratchpad
  )
