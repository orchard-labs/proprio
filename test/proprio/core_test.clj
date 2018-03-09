(ns proprio.core-test
  (:require [proprio.core :as proprio]
            [proprio.bootstrap :refer [test-config test-stream]]
            [clojure.test :refer :all]
            [bond.james :as bond])
  (:import [java.util UUID]))

(defn wait-for
  "Invoke predicate every interval (default 3) seconds until it returns true,
  or timeout (default 120) seconds have elapsed. E.g.:

      (wait-for #(< (rand) 0.2) :interval 1 :timeout 10)

  Returns nil if the timeout elapses before the predicate becomes true,
  otherwise the value of the predicate on its last evaluation."
  [predicate & {:keys [interval timeout timeout-fn]
                :or   {interval 3
                       timeout  120
                       timeout-fn
                       #(throw (ex-info "Timed out waiting for condition"
                                        {:predicate predicate
                                         :timeout   timeout}))}}]
  (let [end-time (+ (System/currentTimeMillis) (* timeout 1000))]
    (loop []
      (if-let [result (predicate)]
        result
        (do (Thread/sleep (* interval 1000))
            (if (< (System/currentTimeMillis) end-time)
              (recur)
              (timeout-fn)))))))

(deftest round-trip-test
  (let [counter (atom 0)
        acc (atom [])
        handler (fn [m]
                  (swap! counter inc)
                  (swap! acc conj (:data m)))

        test-records (mapv #(hash-map :number % :uuid (str (UUID/randomUUID)))
                           (range 20))]

    ;; start over
    (when (contains? (set (proprio/list-streams test-config)) test-stream)
      (proprio/delete-stream test-config test-stream))
    ;; deletion appears to be asynchronous
    (wait-for #(not (contains? (set (proprio/list-streams test-config)) test-stream)))

    ;; create the stream
    (proprio/create-stream test-config test-stream 1)

    ;; kick off a worker
    (let [stop-fn (proprio/stream-worker test-config test-stream handler)]
      ;; send some data
      (doseq [rs (partition-all 3 test-records)]
        (proprio/put-records test-config test-stream "partition-key" rs))

      ;; wait for it to finish
      (wait-for #(= 20 @counter))
      (is (= @acc test-records))

      ;; kill the loop
      (stop-fn))

    ;; delete the stream
    (proprio/delete-stream test-config test-stream)))
