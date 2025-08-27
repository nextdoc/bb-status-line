(ns braille-color
  "Colored braille sparkline charts inspired by btop/bashtop system monitors.
  
  Provides truecolor braille-based data visualization with green→yellow→red
  gradient coloring based on normalized values. Supports multi-block charts
  with per-block normalization for independent color scaling."
  (:require [clojure.string]))

(def ^:private braille-base 0x2800)

;; Map a sample (normalized 0..1) to a row index 0..3 (0=bottom)
(defn- row4 [t]
  (int (Math/round (* 3.0 (max 0.0 (min 1.0 (double t)))))))

;; Build bitmask for a single column (left? true -> dots 7,3,2,1 ; right? -> 8,6,5,4)
(defn- colmask [row left?]
  (let [levels (inc row)]
    (reduce
      (fn [m i]
        (bit-or m
          (case [left? i]
            [true  0] 0x40  ; dot7
            [true  1] 0x04  ; dot3
            [true  2] 0x02  ; dot2
            [true  3] 0x01  ; dot1
            [false 0] 0x80  ; dot8
            [false 1] 0x20  ; dot6
            [false 2] 0x10  ; dot5
            [false 3] 0x08  ; dot4
            0)))
      0 (range levels))))

;; Simple green→yellow→red truecolor gradient
(defn- g2y2r [t]
  (let [t (max 0.0 (min 1.0 (double t)))]
    (cond
      (<= t 0.5) ;; green -> yellow
      (let [u (* 2.0 t)]
        [ (int (* 255 u))        ; R: 0 → 255
          255                     ; G: 255 stays
          0 ])
      :else      ;; yellow -> red
      (let [u (* 2.0 (- t 0.5))]
        [ 255
          (int (* 255 (- 1.0 u))) ; G: 255 → 0
          0 ]))))

(def ^:private ansi-reset "\u001b[0m")
(defn- ansi-truecolor [[r g b]]
  (format "\u001b[38;2;%d;%d;%dm" r g b))

(defn- segment-into-5hour-blocks
  "Segments hourly data into 5-hour blocks starting from first-prompt-hour"
  [hourly-costs first-prompt-hour]
  (let [hours-sorted (sort (keys hourly-costs))
        start-hour first-prompt-hour]
    (if (empty? hours-sorted)
      []
      (loop [current-hour start-hour
             blocks []]
        (let [block-hours (range current-hour (+ current-hour 5))
              block-data (map #(get hourly-costs % 0.0) block-hours)
              next-start (+ current-hour 5)]
          (if (some #(<= % (apply max hours-sorted)) block-hours)
            (recur next-start (conj blocks block-data))
            (if (some pos? block-data)
              (conj blocks block-data)
              blocks)))))))

(defn multi-block-chart
  "Creates multiple 5-hour block charts with space separators.
   Uses per-block normalization for independent color scaling."
  [hourly-costs first-prompt-hour]
  (let [blocks (segment-into-5hour-blocks hourly-costs first-prompt-hour)]
    (if (empty? blocks)
      ""
      (let [;; Render each block with per-block normalization and trailing zero truncation
            blocks-rendered (map (fn [block-data]
                                   (if (every? zero? block-data)
                                     "⠀⠀⠀" ; empty braille for zero blocks
                                     (let [;; Truncate trailing zeros - only render up to last meaningful data
                                           last-nonzero-idx (reduce (fn [acc i] 
                                                                      (if (pos? (nth block-data i)) i acc))
                                                                    -1 (range (count block-data)))
                                           truncated-data (if (>= last-nonzero-idx 0)
                                                            (take (inc last-nonzero-idx) block-data)
                                                            block-data)
                                           ;; Per-block normalization
                                           block-min (if (seq truncated-data) (apply min truncated-data) 0.0)
                                           block-max (if (seq truncated-data) (apply max truncated-data) 0.0)
                                           block-range (max 1e-12 (- block-max block-min))
                                           normalize-block (fn [x] (/ (- x block-min) block-range))
                                           normalized (map normalize-block truncated-data)
                                           rows-fn (fn [t] (row4 t))]
                                       (apply str
                                         (for [i (range 0 (count normalized) 2)]
                                           (let [xL (nth normalized i)
                                                 xR (nth normalized (min (dec (count normalized)) (inc i)))
                                                 rL (rows-fn xL)
                                                 rR (rows-fn xR)
                                                 mask (bit-or (colmask rL true) (colmask rR false))
                                                 ch   (char (+ braille-base mask))
                                                 clr  (g2y2r (max xL xR))] ; color by higher of the two values
                                             (str (ansi-truecolor clr) ch ansi-reset)))))))
                                 blocks)]
        (clojure.string/join " " blocks-rendered)))))

;; Demo/test data generation
(defn generate-sample-hourly-costs
  "Generate sample hourly cost data for testing"
  [start-hour num-hours]
  (into {}
        (map (fn [h]
               [h (+ (* 0.5 (Math/sin (* 0.3 h)))
                     (* 0.3 (Math/cos (* 0.7 h)))
                     (* 0.2 (rand))
                     1.0)]) ; base cost of $1.00
             (range start-hour (+ start-hour num-hours)))))