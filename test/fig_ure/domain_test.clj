(ns fig-ure.domain-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [fig-ure.domain :as domain]))

(deftest reading->line-protocol-test
  (testing "Formats floating-point telemetry value without suffix"
    (let [reading {:sensor/id        :bme280/temperature
                   :sensor/value     24.5
                   :sensor/unit      :celsius
                   :sensor/timestamp 1785658576925}
          expected "telemetry,sensor_id=bme280/temperature,unit=celsius value=24.5 1785658576925"]
      (is (= expected (domain/reading->line-protocol reading)))))

  (testing "Formats telemetry with optional raw-value field in Line Protocol"
    (let [reading {:sensor/id        :seesaw/moisture
                   :sensor/value     35.56
                   :sensor/raw-value 580
                   :sensor/unit      :percent
                   :sensor/timestamp 1785658576926}
          expected "telemetry,sensor_id=seesaw/moisture,unit=percent value=35.56,raw_value=580.0 1785658576926"]
      (is (= expected (domain/reading->line-protocol reading)))))

  (testing "Ignores non-numeric telemetry values (e.g. camera snapshot string paths) and returns nil"
    (let [reading {:sensor/id        :camera/snapshot
                   :sensor/value     "data/snapshots/snapshot-1.jpg"
                   :sensor/unit      :file
                   :sensor/timestamp 1785658576927}]
      (is (nil? (domain/reading->line-protocol reading)))))

  (testing "Returns nil when reading map is invalid or missing fields"
    (is (nil? (domain/reading->line-protocol {:sensor/id :bme280/temperature :sensor/value nil})))
    (is (nil? (domain/reading->line-protocol nil)))))

(deftest readings->payload-test
  (testing "Joins multiple valid readings into newline-separated Line Protocol payload"
    (let [readings [{:sensor/id :bme280/temperature :sensor/value 24.5 :sensor/unit :celsius :sensor/timestamp 100}
                    {:sensor/id :seesaw/moisture :sensor/value 40.0 :sensor/unit :percent :sensor/timestamp 101}]
          expected "telemetry,sensor_id=bme280/temperature,unit=celsius value=24.5 100\ntelemetry,sensor_id=seesaw/moisture,unit=percent value=40.0 101"]
      (is (= expected (domain/readings->payload readings))))))
