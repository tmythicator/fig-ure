(ns fig-ure.sensors-test
  (:require [clojure.test :refer [deftest is testing are]]
            [fig-ure.sensors :as sensors]
            [matcher-combinators.test :refer [match?]]))

(deftest format-reading-test
  (testing "formats sensor reading into telemetry map structure"
    (is (match? {:sensor/id    :soil-moisture
                 :sensor/value 42.5
                 :sensor/unit  :percent}
                (sensors/format-reading :soil-moisture 42.5 :percent)))))

(deftest valid-percent-reading-test
  (testing "validates sensor readings (percent-unit)"
    (let [mock-reading {:sensor/id :soil-moisture
                        :sensor/unit :percent
                        :sensor/timestamp (System/currentTimeMillis)}]

      (are [expected value]
                        (= expected (sensors/valid-percent-reading?
                                     (assoc mock-reading :sensor/value value)))
        true 33.1
        true 0.0
        true 100.0
        true 3
        false -1.0
        false 101.0
        false ""
        false :test)))
  (testing "validates sensor readings (non-percent-unit)"
    (let [mock-reading {:sensor/id :soil-moisture
                        :sensor/unit :temperature
                        :sensor/value 28.3
                        :sensor/timestamp (System/currentTimeMillis)}]
      (is (not (sensors/valid-percent-reading? mock-reading))))))

(deftest calculate-average-percent-value
  (testing "calculates average over valid percent readings, ignoring invalid ones"
    (let [mock-readings [{:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 100}
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 120} ;; ignore
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 50}
                         {:sensor/id :soil-moisture :sensor/unit :celcius :sensor/value 30} ;; ignore
                         {:sensor/id :soil-moisture :sensor/unit :percent :sensor/value 75}]]
      (is (= 75 (sensors/calculate-average-percent-value mock-readings))))))