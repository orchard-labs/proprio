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

(defmacro with-stream
  [[stream-sym stream-val config-sym config-val] & body]
  (let [stream-sym (symbol (name stream-sym))
        config-sym (symbol (name config-sym))]
    `(let [~stream-sym  ~stream-val
           ~config-sym  ~config-val
           exists-pred# #(contains? (set (proprio/list-streams ~config-sym))
                                    ~stream-sym)]
       (when (exists-pred#)
         (proprio/delete-stream ~config-sym ~stream-sym)
         ;; deletion appears to be asynchronous
         (wait-for #(not (exists-pred#))))
       (proprio/create-stream ~config-sym ~stream-sym 1)
       (do ~@body)
       (proprio/delete-stream ~config-sym ~stream-sym)
       (wait-for #(not (exists-pred#))))))

(deftest round-trip-test
  (with-stream [stream (test-stream)
                config (test-config)]
    (let [counter (atom 0)
          acc (atom [])
          handler (fn [m]
                    (swap! counter inc)
                    (swap! acc conj (:data m)))

          test-records (mapv #(hash-map :number % :uuid (str (UUID/randomUUID)))
                             (range 20))

          stop-fn (proprio/stream-worker config stream handler)]

      ;; kick off a worker
      (doseq [rs (partition-all 3 test-records)]
        (proprio/put-records config stream "partition-key" rs))

      ;; wait for it to finish
      (wait-for #(= 20 @counter))
      (is (= @acc test-records))

      ;; kill the loop
      (stop-fn))))

(deftest checkpointing-test
  (testing "checkpoint is called on startup"
    (with-stream [stream (test-stream)
                  config (test-config)]
      (bond/with-spy [proprio/maybe-checkpoint]
        (let [stop-fn (proprio/stream-worker config stream (fn [_] nil))
              test-records (mapv #(hash-map :number % :uuid (str (UUID/randomUUID)))
                                 (range 20))]
          (is (= 0 (-> proprio/maybe-checkpoint bond/calls count)))

          (doseq [rs (partition-all 3 test-records)]
            (proprio/put-records config stream "partition-key" rs))

          (Thread/sleep 10000)

          ;; should get called 7 times, returning the same value each time
          (wait-for #(<= 1 (-> proprio/maybe-checkpoint bond/calls count)))
          (is (->> proprio/maybe-checkpoint
                   (bond/calls)
                   (map :returns)
                   (apply =))))))))
