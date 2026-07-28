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
  [sys-config sensors-component]
  (let [stop-chan (async/chan)
        camera-chan (create-camera-chan (:camera-buf-size sys-config))
        sensor-chan (create-sensor-chan (:sensor-buf-size sys-config))
        bus (or (:i2c-bus sensors-component) "1")
        calib (:calibration sensors-component)
        bme280-produce #(sensors/read-sensor-readings :bme280 bus calib)
        soil-m-produce #(sensors/read-sensor-readings :seesaw-soil-moisture bus)
        soil-t-produce #(sensors/read-sensor-readings :seesaw-soil-temperature bus)]

    (start-generic-producer! "BME280" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             bme280-produce
                             :readings)

    (start-generic-producer! "Soil moisture" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             soil-m-produce
                             :readings)

    (start-generic-producer! "Soil Temperature" sensor-chan stop-chan
                             (:sensor-interval-ms sys-config)
                             soil-t-produce
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
  (println "Initializing telemtry pipeline..." config)
  (start-telemetry-pipeline! config (:sensors config)))

(defmethod ig/halt-key! :fig-ure/telemetry [_ state]
  (println "Halting telemetry worker..." state)
  (when-let [stop-chan (:stop-chan state)]
    (async/close! stop-chan)))

(comment
  ;; Interactive REPL scratchpad
  )
