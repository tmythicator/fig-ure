(ns fig-ure.schema
  "Domain data contracts and Malli schemas for fig-ure telemetry and sensors."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]))

(def SoilMoistureValue
  "Calibrated soil moisture percentage."
  [:double {:min 0.0 :max 100.0}])

(def TemperatureValue
  "Defined by official BME280 + Seesaw Spec."
  [:double {:min -40.0 :max 85.0}])

(def PressureValue
  "Defined by official BME280 Spec."
  [:double {:min 300.0 :max 1100.0}])

(def HumidityValue
  "Defined by official BME280 Spec."
  [:double {:min 0.0 :max 100.0}])

(def UnitID
  [:enum
   :celsius
   :percent
   :capacitive
   :file
   :hpa])

(def SensorID
  [:enum
   :bme280/humidity
   :bme280/pressure
   :bme280/temperature
   :seesaw/moisture
   :seesaw/temperature
   :cpu/temperature
   :camera/snapshot])

(def SensorReading
  [:or
   [:map {:closed true}
    [:sensor/id [:= :seesaw/moisture]]
    [:sensor/value SoilMoistureValue]
    [:sensor/raw-value {:optional true} number?]
    [:sensor/unit [:= :percent]]
    [:sensor/timestamp int?]]
   [:map {:closed true}
    [:sensor/id [:enum :bme280/temperature :seesaw/temperature :cpu/temperature]]
    [:sensor/value TemperatureValue]
    [:sensor/raw-value {:optional true} number?]
    [:sensor/unit [:= :celsius]]
    [:sensor/timestamp int?]]
   [:map {:closed true}
    [:sensor/id [:= :bme280/pressure]]
    [:sensor/value PressureValue]
    [:sensor/raw-value {:optional true} number?]
    [:sensor/unit [:= :hpa]]
    [:sensor/timestamp int?]]
   [:map {:closed true}
    [:sensor/id [:= :bme280/humidity]]
    [:sensor/value HumidityValue]
    [:sensor/raw-value {:optional true} number?]
    [:sensor/unit [:= :percent]]
    [:sensor/timestamp int?]]
   [:map {:closed true}
    [:sensor/id [:= :camera/snapshot]]
    [:sensor/value string?]
    [:sensor/raw-value {:optional true} number?]
    [:sensor/unit [:= :file]]
    [:sensor/timestamp int?]]])

(def SensorResponse
  [:or
   [:map {:closed true}
    [:status [:enum :error]]
    [:error/reason keyword?]
    [:error/message {:optional true} string?]
    [:error/data {:optional true} any?]]
   [:map {:closed true}
    [:status [:enum :ok]]
    [:readings [:vector SensorReading]]]])

(defn valid?
  "Returns true if data conforms to schema."
  [schema data]
  (m/validate schema data))

(defn validate!
  "Validates data against schema. Throws ex-info with humanized explanation if invalid."
  [schema data]
  (if (m/validate schema data)
    data
    (let [explain (m/explain schema data)
          human-err (me/humanize explain)]
      (throw (ex-info (str "Schema validation failed: " (pr-str human-err))
                      {:type :schema/validation-error
                       :explanation explain
                       :humanized human-err
                       :error data})))))

(defn safe-validate!
  "Validates data against schema SAFELY.
  If hardware produced bad value, turns it into {:status :error} return map instead of throwing!"
  [schema data]
  (if (valid? schema data)
    data
    (let [explain (m/explain schema data)
          human-err (me/humanize explain)]
      {:status        :error
       :error/reason  :schema/validation-error
       :error/message (str "Schema validation failed: " (pr-str human-err))
       :error/data data})))

(comment
  (m/schema? (m/schema UnitID))
  (m/schema? UnitID)
  (m/schema? SensorID)
  (m/schema? SensorReading)
  (-> (m/explain SensorReading {:status :ok, :readings []})
      me/humanize)
  (-> (m/explain SensorResponse {:status :ok, :readings [#:sensor{:id :bme280/temperature, :value 26.2, :unit :celsius, :timestamp 1785658576925} #:sensor{:id :bme280/pressure, :value 1004.37, :unit :hpa, :timestamp 1785658576926} #:sensor{:id :bme280/humidity, :value 46.67, :unit :percent, :timestamp 1785658576926}]})
      (me/humanize))
  (m/validate number? 42)
  (m/validate SensorID :soil-moisture)

  (-> (m/schema? SensorReading)
      (me/humanize))

  (safe-validate! SensorResponse
                  {:status :ok
                   :readings [{:sensor/id :bme280/temperature
                               :sensor/value -333.2
                               :sensor/unit :celsius
                               :sensor/timestamp 1785658576925}]})

  (validate! SensorResponse
             {:status :ok
              :readings [{:sensor/id :bme280/temperature
                          :sensor/value -126.2
                          :sensor/unit :celsius
                          :sensor/timestamp 1785658576925}]})

  (mg/generate SensorReading))