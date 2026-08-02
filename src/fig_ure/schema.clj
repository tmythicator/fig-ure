(ns fig-ure.schema
  "Domain data contracts and Malli schemas for fig-ure telemetry and sensors."
  (:require [malli.core :as m]
            [malli.error :as me]))

(set! *warn-on-reflection* true)

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
   :camera/snapshot])

(def SensorReading
  [:or
   [:map {:closed true}
    [:sensor/id [:and SensorID [:not [:= :camera/snapshot]]]]
    [:sensor/value number?]
    [:sensor/unit [:and UnitID [:not [:= :file]]]]
    [:sensor/timestamp int?]]
   [:map {:closed true}
    [:sensor/id [:and SensorID [:= :camera/snapshot]]]
    [:sensor/value string?]
    [:sensor/unit [:and UnitID [:= :file]]]
    [:sensor/timestamp int?]]])

(def SensorResponse
  [:or
   [:map {:closed true}
    [:status [:enum :error]]
    [:error/reason keyword?]
    [:error/message {:optional true} string?]]
   [:map {:closed true}
    [:status [:enum :ok]]
    [:readings [:vector SensorReading]]]])

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

(defn valid?
  "Returns true if data conforms to schema."
  [schema data]
  (m/validate schema data))

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

  (validate! SensorResponse
             {:status :ok
              :readings [{:sensor/id :bme280/temperature
                          :sensor/value 26.2
                          :sensor/unit :celsius
                          :sensor/timestamp 1785658576925}]}))