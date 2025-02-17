(ns clojask.dataframe
  (:require [clojure.set :as set]
            [clojask.ColInfo :refer [->ColInfo]]
            [clojask.RowInfo :refer [->RowInfo]]
            [clojure.data.csv :as csv]
            [clojure.java.io :as io]
            [clojask.utils :as u]
            [clojask.onyx-comps :refer [start-onyx start-onyx-aggre-only start-onyx-groupby start-onyx-join]]
            [clojask.sort :as sort]
            [aggregate.aggre-onyx-comps :refer [start-onyx-aggre]]
            [clojure.string :as str]
            [clojask.preview :as preview]
            [clojure.pprint :as pprint])
  (:import [clojask.ColInfo ColInfo]
           [clojask.RowInfo RowInfo]
           [com.clojask.exception Clojask_TypeException]
           [com.clojask.exception Clojask_OperationException])
  (:refer-clojure :exclude [filter group-by sort]))
"The clojask lazy dataframe"


(definterface DFIntf
  (compute [^int num-worker ^String output-dir ^boolean exception ^boolean order select] "final evaluatation")
  (checkOutputPath [output-path] "check if output path is of string type")
  (operate [operation colName] "operate an operation to column and replace in place")
  (operate [operation colName newCol] "operate an operation to column and add the result as new column")
  (setType [type colName] "types supported: int double string date")
  (setParser [parser col] "add the parser for a col which acts like setType")
  (colDesc [] "get column description in ColInfo")
  (colTypes [] "get column type in ColInfo")
  (getColIndex [] "get column indices, excluding deleted columns")
  (getColNames [] "get column names")
  (printCol [output-path selected-col] "print column names to output file")
  (printAggreCol [output-path] "print column names to output file for aggregate")
  (printJoinCol [b-df a-keys b-keys output-path col-prefix] "print column names to output file for join")
  (delCol [col-to-del] "delete one or more columns in the dataframe")
  (reorderCol [new-col-order] "reorder columns in the dataframe")
  (renameCol [new-col-names] "rename columns in the dataframe")
  (groupby [a] "group the dataframe by the key(s)")
  (aggregate [a c b] "aggregate the group-by result by the function")
  (head [n] "return first n lines in dataframe")
  (filter [cols predicate])
  (computeTypeCheck [num-worker output-dir])
  (computeGroupAggre [^int num-worker ^String output-dir ^boolean exception select])
  (computeAggre [^int num-worker ^String output-dir ^boolean exception select])
  (sort [a b] "sort the dataframe based on columns")
  (addFormatter [a b] "format the column as the last step of the computation")
  (preview [sample-size output-size format] "quickly return a vector of maps about the resultant dataframe")
  (final [] "prepare the dataframe for computation")
  (previewDF [] "preview function used for error predetection")
  (errorPredetect [msg] "prints exception with msg if error is detected in preview")
  )

;; each dataframe can have a delayed object
(defrecord DataFrame
           [^String path
            ^Integer batch-size
            ^ColInfo col-info
            ^RowInfo row-info
            ^Boolean have-col]
  DFIntf

  (checkOutputPath
    [this output-path]
    (cond (not (= java.lang.String (type output-path)))
          (throw (Clojask_TypeException. "Output path should be a string."))))

  (operate ;; has assert
    [this operation colName]
    (if (nil? (.operate col-info operation colName))
      this ; "success"
      (throw (Clojask_OperationException. "operate"))))

  (operate
    [this operation colNames newCol]
    (cond (not (= java.lang.String (type newCol)))
          (throw (Clojask_TypeException.  "New column should be a string.")))
    (if (nil? (.operate col-info operation colNames newCol))
      this
      (throw (Clojask_OperationException. "Error in running operate."))))

  (groupby
    [this key]
    (let [input (u/proc-groupby-key key)
          keys (map #(nth % 1) input)]
      (cond (not (not= input nil))
            (throw (Clojask_TypeException. "The group-by keys format is not correct.")))
      (cond (not (= 0 (count (u/are-in keys this))))
            (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
      (let [keys (mapv (fn [_] [(first _) (get (.getKeyIndex col-info) (nth _ 1))]) input)]
        (if (nil? (.groupby row-info keys))
          this
          (throw (Clojask_OperationException. "groupby"))))))

  (aggregate
    [this func old-key new-key]
    (cond (not (= 0 (count (u/are-in old-key this))))
          (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (cond (not (= 0 (count (u/are-out new-key this))))
          (throw (Clojask_TypeException. "New keys should not be existing column names.")))
    (let [old-key (mapv (fn [_] (get (.getKeyIndex col-info) _)) old-key)]
      (if (nil? (.aggregate row-info func old-key new-key))
        this
        (throw (Clojask_OperationException. "aggregate")))))

  (filter
    [this cols predicate]
    (let [cols (if (coll? cols)
                 cols
                 (vector cols))
          indices (map (fn [_] (get (.getKeyIndex (:col-info this)) _)) cols)]
      (cond (not (u/are-in cols this))
            (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
      (if (nil? (.filter row-info indices predicate))
        this
        (throw (Clojask_OperationException. "filter")))))

  (colDesc
    [this]
    (.getDesc col-info))

  (colTypes
    [this]
    (.getType col-info))

  (getColIndex
    [this]
    (let [index-key (.getIndexKey (:col-info this))
          indices-deleted (set (.getDeletedCol (:col-info this)))
          indices-wo-del (vec (take (count index-key) (iterate inc 0)))
          index (if (empty? indices-deleted) 
                    indices-wo-del ;; no columns deleted
                    (filterv (fn [i] (= false (contains? indices-deleted i))) indices-wo-del))]
    index))

  (getColNames
    [this]
    (if (and (= 0 (count (.getGroupbyKeys (:row-info this)))) (= 0 (count (.getAggreNewKeys (:row-info this)))))
        ;; not aggregate
        (let [index-key (.getIndexKey (:col-info this))
              index (.getColIndex this)]
              ;(mapv (fn [i] (get {0 "Employee", 1 "EmployeeName", 2 "Department", 3 "Salary"} i)) [0 2 2 2])
              (mapv (fn [i] (get index-key i)) index))
        ;; if aggregate
        (let [groupby-key-index (.getGroupbyKeys (:row-info this))
              groupby-keys (vec (map (.getIndexKey (.col-info this)) (vec (map #(last %) groupby-key-index))))
              aggre-new-keys (.getAggreNewKeys (:row-info this))]
              (concat groupby-keys aggre-new-keys))))

  (printCol
  ;; print column names, called by compute and computeAggre
    [this output-path selected-col]
    (.checkOutputPath this output-path)
    (let [col-set (if (= selected-col [nil]) (.getColNames this) selected-col)]
      (with-open [wrtr (io/writer output-path)]
        (.write wrtr (str (str/join "," col-set) "\n")))))

  ;; !! deprecated
  (printAggreCol
  ;; print column names, called by computeAggre
    [this output-path]
    (.checkOutputPath this output-path)
    (let [groupby-key-index (.getGroupbyKeys (:row-info this))
          groupby-keys (vec (map (.getIndexKey (.col-info this)) (vec (map #(last %) groupby-key-index))))
          aggre-new-keys (.getAggreNewKeys (:row-info this))]
      (with-open [wrtr (io/writer output-path)]
        (.write wrtr (str (str/join "," (concat groupby-keys aggre-new-keys)) "\n")))))

  ;; !! deprecated
  (printJoinCol
  ;; print column names, called by join APIs
    [this b-df this-keys b-keys output-path col-prefix]
    (.checkOutputPath this output-path)
    (let [a-col-prefix (first col-prefix)
          b-col-prefix (last col-prefix)
          a-col-set (.getColNames this)
          b-col-set (.getColNames b-df)
          a-col-header (map #(str a-col-prefix "_" %) a-col-set)
          b-col-header (map #(str b-col-prefix "_" %) b-col-set)]
        (with-open [wrtr (io/writer output-path)]
          (.write wrtr (str (str/join "," (concat a-col-header b-col-header)) "\n")))))
  
  (delCol
    [this col-to-del]
    (cond (not (= 0 (count (u/are-in col-to-del this))))
          (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (.delCol (.col-info this) col-to-del)
    ;; "success"
    this)

  (reorderCol
    [this new-col-order]
    (cond (not (= (set (.getKeys (.col-info this))) (set new-col-order)))
          (throw (Clojask_TypeException. "Set of input in reorder-col contains column(s) that do not exist in dataframe.")))
    (.setColInfo (.col-info this) new-col-order)
    (.setRowInfo (.row-info this) (.getDesc (.col-info this)) new-col-order)
    ;; "success"  
    this)

  (renameCol
    [this new-col-names]
    (cond (not (= (count (.getKeys (.col-info this))) (count new-col-names)))
          (throw (Clojask_TypeException. "Number of new column names not equal to number of existing columns.")))
    (.renameColInfo (.col-info this) new-col-names)
    ;; "success"
    this)

  (head
    [this n]
    (cond (not (integer? n))
          (throw (Clojask_TypeException. "Argument passed to head should be an integer.")))
    (with-open [reader (io/reader path)]
      (doall (take n (csv/read-csv reader)))))

  (setType
    [this type colName]
    (u/set-format-string type)
    (let [type (subs type 0 (if-let [tmp (str/index-of type ":")] tmp (count type)))
          oprs (get u/type-operation-map type)
          parser (deref (nth oprs 0))
          format (deref (nth oprs 1))]
      (if (= oprs nil)
        "No such type. You could instead write your parsing function as the first operation to this column."
        (do
          (.setType col-info parser colName)
          (.addFormatter this format colName)
          ;; "success"
          this))))

  (setParser
    [this parser colName]
    (cond (not (u/is-in colName this))
          (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (if (nil? (.setType col-info parser colName))
      this
      (throw (Clojask_OperationException. "Error in running setParser."))))

  (addFormatter
    [this format col]
    (cond (not (u/is-in col this))
          (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (.setFormatter col-info format col))

  (final
    [this]
    (doseq [tmp (.getFormatter (:col-info this))]
      (.operate this (nth tmp 1) (get (.getIndexKey col-info) (nth tmp 0)))))

  (preview
    [this sample-size return-size format]
    (cond (not (and (integer? sample-size) (integer? return-size)))
          (throw (Clojask_TypeException. "Arguments passed to preview must be integers.")))
    (preview/preview this sample-size return-size format))

  (computeTypeCheck
    [this num-worker output-dir]
    (cond (not (= java.lang.String (type output-dir)))
      (throw (Clojask_TypeException. "Output directory should be a string.")))
    (cond (not (integer? num-worker))
      (throw (Clojask_TypeException. "Number of workers should be an integer.")))
    (if (> num-worker 8)
      (throw (Clojask_OperationException. "Max number of worker nodes is 8."))))

  (compute
    [this ^int num-worker ^String output-dir ^boolean exception ^boolean order select]
    ;(assert (= java.lang.String (type output-dir)) "output path should be a string")
    (let [key-index (.getKeyIndex (:col-info this))
          select (if (coll? select) select [select])
          index (if (= select [nil]) (take (count key-index) (iterate inc 0)) (vals (select-keys key-index select)))]
      (assert (or (= (count select) (count index)) (= select [nil]))(Clojask_OperationException. "Must select existing columns. You may check it using"))
      (if (<= num-worker 8)
        (try
          (.final this)
          (.printCol this output-dir select) ;; to-do: based on the index => Done
          (let [res (start-onyx num-worker batch-size this output-dir exception order index)]
            (if (= res "success")
              "success"
              "failed"))
          (catch Exception e e))
        (throw (Clojask_OperationException. "Max number of worker nodes is 8.")))))
  
  (computeAggre
   [this ^int num-worker ^String output-dir ^boolean exception select]
   (.computeTypeCheck this num-worker output-dir)
   ;(.printAggreCol this output-dir) ;; print column names to output-dir
   (let [aggre-keys (.getAggreFunc row-info)
         select (if (coll? select) select [select])
         select (if (= select [nil])
                  (vec (take (count aggre-keys) (iterate inc 0)))
                  (mapv (fn [key] (.indexOf (.getColNames this) key)) select))
         aggre-func (u/gets aggre-keys (vec (apply sorted-set select)))
         select (mapv (fn [num] (count (remove #(>= % num) select))) select)
         index (vec (apply sorted-set (mapv #(nth % 1) aggre-func)))
         shift-func (fn [pair]
                      [(first pair) (let [num (nth pair 1)]
                                      (.indexOf index num))])
         aggre-func (mapv shift-func aggre-func)
        ;;  test (println [select index aggre-func])
         tmp (.printCol this output-dir select) ;; todo: based on "select"
         res (start-onyx-aggre-only num-worker batch-size this output-dir exception aggre-func index select)]
     (if (= res "success")
       "success"
       "failed")))
  
  (computeGroupAggre
    [this ^int num-worker ^String output-dir ^boolean exception select]
    (.computeTypeCheck this num-worker output-dir)
    (if (<= num-worker 8)
      (try
        (let [groupby-keys (.getGroupbyKeys row-info)
              aggre-keys (.getAggreFunc row-info)
              select (if (coll? select) select [select])
              select (if (= select [nil])
                       (vec (take (+ (count groupby-keys) (count aggre-keys)) (iterate inc 0)))
                       (mapv (fn [key] (.indexOf (.getColNames this) key)) select))
              ;; pre-index (remove #(>= % (count groupby-keys)) select)
              data-index (mapv #(- % (count groupby-keys)) (remove #(< % (count groupby-keys)) select))
              groupby-index (vec (apply sorted-set (mapv #(nth % 1) (concat groupby-keys (u/gets aggre-keys data-index)))))
              ;; test (println [groupby-keys aggre-keys select pre-index data-index])
              res (start-onyx-groupby num-worker batch-size this "_clojask/grouped/" groupby-keys groupby-index exception)]
          ;(.printAggreCol this output-dir) ;; print column names to output-dir
          (.printCol this output-dir select) ;; todo: based on "select"
          (if (= res "success")
          ;;  (if (= "success" (start-onyx-aggre num-worker batch-size this output-dir (.getGroupbyKeys (:row-info this)) exception))
            (let [shift-func (fn [pair]
                               [(first pair) (let [index (nth pair 1)]
                                               (.indexOf groupby-index index))])
                  aggre-func (mapv shift-func (u/gets aggre-keys data-index))
                  formatter (.getFormatter (.col-info this))
                  formatter (set/rename-keys formatter (zipmap groupby-index (iterate inc 0)))]
             (if
            ;;  (internal-aggregate (.getAggreFunc (:row-info this)) output-dir (.getKeyIndex col-info) (.getGroupbyKeys (:row-info this)) (.getAggreOldKeys (:row-info this)) (.getAggreNewKeys (:row-info this)))
             (start-onyx-aggre num-worker batch-size this output-dir exception aggre-func select formatter)
              "success"
              (throw (Clojask_OperationException. "Error when aggregating."))))
            (throw (Clojask_OperationException. "Error when grouping by."))))
        (catch Exception e e))
      (throw (Clojask_OperationException. "Max number of worker nodes is 8."))))

  (sort
    [this list output-dir]
    (cond (not (and (not (empty? list)) (loop [list list key false]
                                          (if (and (not key) (= list '()))
                                            true
                                            (let [_ (first list)
                                                  res (rest list)]
                                              (if key
                                                (if (and (contains? (.getKeyIndex col-info) _) (= (type _) java.lang.String))
                                                  (recur res false)
                                                  false)
                                                (if (or (= _ "+") (= _ "-"))
                                                  (recur res true)
                                                  false)))))))
          (throw (Clojask_TypeException. "The order list is not in the correct format.")))
    (defn clojask-compare
      "a and b are two rows"
      [a b]
      (loop [list list key false sign +]
        (if (= list '())
          0
          (let [_ (first list)
                res (rest list)]
            (if key
              (let [tmp (compare (u/get-key a (.getType col-info) (.getKeyIndex col-info) _) (u/get-key b (.getType col-info) (.getKeyIndex col-info) _))]
                (if (not= tmp 0)
                  (sign tmp)
                  (recur res false nil)))
              (if (= _ "+")
                (recur res true +)
                (recur res true -)))))))
    (sort/use-external-sort path output-dir clojask-compare))

    (previewDF
      [this]
      (let [data (.preview this 10 10 false)
            tmp (first data)
            types (zipmap (keys tmp) (map u/get-type-string (vals tmp)))]
            (conj (apply list data) types)))

    (errorPredetect
      [this msg]
      (try 
        (.previewDF this)
        (catch Exception e
          (do
            (throw (Clojask_OperationException. (format  (str msg " (original error: %s)") (str (.getMessage e)))))))))

    Object
    )

(defn preview
  [dataframe sample-size return-size & {:keys [format] :or {format false}}]
  (.preview dataframe sample-size return-size format))

(defn print-df
  [dataframe & [sample-size return-size]]
  (let [data (.preview dataframe (or sample-size 1000) (or return-size 10) false)
        tmp (first data)
        types (zipmap (keys tmp) (map u/get-type-string (vals tmp)))
        data (conj (apply list data) types)]
    (pprint/print-table data)))

(defn generate-col
  "Generate column names if there are none"
  [col-count]
  (vec (map #(str "Col_" %) (range 1 (+ col-count 1)))))

(defn dataframe
  [path & {:keys [have-col] :or {have-col true}}]
  (try
    (let [reader (io/reader path)
          file (csv/read-csv reader)
          colNames (u/check-duplicate-col (if have-col (doall (first file)) (generate-col (count (first file)))))
          col-info (ColInfo. (doall (map keyword colNames)) {} {} {} {} {} {})
          row-info (RowInfo. [] [] [] [])]
      ;; (type-detection file)
      (.close reader)
      (.init col-info colNames)
      ;; 
      ;; type detection
      ;; 
      (DataFrame. path 300 col-info row-info have-col))
    (catch Exception e
      (do
        (throw (Clojask_OperationException. "no such file or directory"))
        nil))))

(defn filter
  [this cols predicate]
  (let [result (.filter this cols predicate)]
    (.errorPredetect this "invalid arguments passed to filter function")
    result))

(defn operate
  ([this operation colName]
  (let [result (.operate this operation colName)]
    (.errorPredetect this "this function cannot be appended into the current pipeline")
    result))
  ([this operation colName newCol]
  (let [result (.operate this operation colName newCol)]
    (.errorPredetect this "this function cannot be appended into the current pipeline")
    result)))

(defn group-by
  [this key]
  (let [result (.groupby this key)]
    (.errorPredetect this "invalid arguments passed to groupby function")
    result))

(defn aggregate
  [this func old-key & [new-key]]
  (let [old-key (if (coll? old-key)
                  old-key
                  [old-key])
        new-key (if (coll? new-key)   ;; to do
                  new-key
                  (if (not= new-key nil)
                    [new-key]
                    (mapv (fn [_] (str func "(" _ ")")) old-key)))]
    (let [result (.aggregate this func old-key new-key)]
      (.errorPredetect this "invalid arguments passed to aggregate function")
      result)))

(defn sort
  [this list output-dir]
  (u/init-file output-dir)
  (.sort this list output-dir))

(defn set-type
  [this col type]
  (let [result (.setType this type col)]
    (.errorPredetect this "invalid arguments passed to set-type function")
  result))

(defn set-parser
  [this col parser]
  (let [result (.setParser this parser col)]
    (.errorPredetect this "invalid arguments passed to set-parser function")
  result))

(defn col-names
  [this]
  (let [result (.getColNames this)]
    (.errorPredetect this "invalid arguments passed to col-names function")
  result))

(defn select-col
  [this col-to-keep]
  (let [col-to-del (set/difference (set (.getColNames this)) (set col-to-keep))] 
    (.delCol this (vec col-to-del))
    ))

(defn delete-col
  [this col-to-del]
  (let [result (.delCol this col-to-del)]
    (.errorPredetect this "invalid arguments passed to delete-col function")
  result))

(defn reorder-col
  [this new-col-order]
  (let [result (.reorderCol this new-col-order)]
    (.errorPredetect this "invalid arguments passed to reorder-col function")
  result))

(defn rename-col
  [this new-col-names]
  (let [result (.renameCol this new-col-names)]
    (.errorPredetect this "invalid arguments passed to rename-col function")
  result))

;; ============= Below is the definition for the joineddataframe ================
(definterface JDFIntf
  (checkOutputPath [output-path] "check if output path is of string type")
  (getColNames [] "get the names of all the columns")
  (printCol [output-path selected-col] "print column names to output file")
  (compute [^int num-worker ^String output-dir ^boolean exception ^boolean order select]))

(defrecord JoinedDataFrame
           [^DataFrame a
            ^DataFrame b
            a-keys
            b-keys
            a-roll
            b-roll
            type
            limit
            prefix]
  JDFIntf

  (getColNames
    [this]
    (let [a-col-prefix (first prefix)
          b-col-prefix (last prefix)
          a-col-set (.getColNames a)
          b-col-set (.getColNames b)
          a-col-header (mapv #(str a-col-prefix "_" %) a-col-set)
          b-col-header (mapv #(str b-col-prefix "_" %) b-col-set)]
      (concat a-col-header b-col-header)))

  (printCol
    ;; print column names, called by compute
      [this output-path selected-col]
      (let [col-set (if (= selected-col [nil]) (.getColNames this) (mapv (vec (.getColNames this)) selected-col))]
        (with-open [wrtr (io/writer output-path)]
          (.write wrtr (str (str/join "," col-set) "\n")))))
        
  (compute
    [this ^int num-worker ^String output-dir ^boolean exception ^boolean order select]
    (let [select (if (coll? select) select [select])
          select (if (= select [nil])
                   (vec (take (+ (count (.getKeyIndex (.col-info a))) (count (.getKeyIndex (.col-info b)))) (iterate inc 0)))
                   (mapv (fn [key] (.indexOf (.getColNames this) key)) select)) 
          a-index (vec (apply sorted-set (remove (fn [num] (>= num (count (.getKeyIndex (.col-info a))))) select)))
          ;; a-write 
          b-index (mapv #(- % (count (.getKeyIndex (.col-info a)))) (apply sorted-set (remove (fn [num] (< num (count (.getKeyIndex (.col-info a))))) select)))
          b-index (if b-roll (vec (apply sorted-set (conj b-index b-roll))) b-index)
          b-roll (if b-roll (count (remove #(>= % b-roll) b-index)) nil)
          ;; b-write
          ;; a-format
          b-format (set/rename-keys (.getFormatter (.col-info b)) (zipmap b-index (iterate inc 0)))
          write-index (mapv (fn [num] (count (remove #(>= % num) (concat a-index (mapv #(+ % (count (.getKeyIndex (.col-info a)))) b-index))))) select)
          ;; test (println a-index b-index b-format write-index b-roll)
          ]
      (u/init-file output-dir)
      ;; print column names
      ;;  (.printJoinCol a b a-keys b-keys output-dir) to-do: make use of getColNames => Done
      (.printCol this output-dir select) ;; todo: based on "select"
      (start-onyx-groupby num-worker 10 b "./_clojask/join/b/" b-keys b-index exception) ;; todo
      (start-onyx-join num-worker 10 a b output-dir exception a-keys b-keys a-roll b-roll type limit a-index (vec (take (count b-index) (iterate inc 0))) b-format write-index))))

(defn inner-join
  [a b a-keys b-keys & {:keys [col-prefix] :or {col-prefix ["1" "2"]}}]
  (let [a-keys (u/proc-groupby-key a-keys)
        b-keys (u/proc-groupby-key b-keys)
        a-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info a)) (nth _ 1))]) a-keys)
        b-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info b)) (nth _ 1))]) b-keys)]
    (cond (not (and (= (type a) clojask.dataframe.DataFrame) (= (type b) clojask.dataframe.DataFrame))) 
      (throw (Clojask_TypeException. "First two arguments should be Clojask dataframes.")))
    (cond (not (= (count a-keys) (count b-keys))) 
      (throw (Clojask_TypeException. "The length of left keys and right keys should be equal.")))
    (cond (not (and (u/are-in a-keys a) (u/are-in b-keys b))) 
      (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (let [a-file (io/file (:path a))
          b-file (io/file (:path b))]
      (if (<= (.length a-file) (.length b-file))
        (JoinedDataFrame. a b a-keys b-keys nil nil 1 nil col-prefix)
        (JoinedDataFrame. b a b-keys a-keys nil nil 1 nil [(nth col-prefix 1) (nth col-prefix 0)])))
    ))


(defn left-join
  [a b a-keys b-keys & {:keys [col-prefix] :or {col-prefix ["1" "2"]}}]
  (let [a-keys (u/proc-groupby-key a-keys)
        b-keys (u/proc-groupby-key b-keys)
        a-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info a)) (nth _ 1))]) a-keys)
        b-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info b)) (nth _ 1))]) b-keys)]
    (cond (not (and (= (type a) clojask.dataframe.DataFrame) (= (type b) clojask.dataframe.DataFrame))) 
      (throw (Clojask_TypeException. "First two arguments should be Clojask dataframes.")))
    (cond (not (= (count a-keys) (count b-keys))) 
      (throw (Clojask_TypeException. "The length of left keys and right keys should be equal.")))
    (cond (not (= (count col-prefix) 2)) 
      (throw (Clojask_TypeException. "The length of col-prefix should be equal to 2.")))
    (cond (not (and (u/are-in a-keys a) (u/are-in b-keys b))) 
      (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (JoinedDataFrame. a b a-keys b-keys nil nil 2 nil col-prefix)))

(defn right-join
  [a b a-keys b-keys num-worker dist & {:keys [col-prefix] :or {col-prefix ["1" "2"]}}]
  (let [a-keys (u/proc-groupby-key a-keys)
        b-keys (u/proc-groupby-key b-keys)
        a-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info a)) (nth _ 1))]) a-keys)
        b-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info b)) (nth _ 1))]) b-keys)]
    (cond (not (and (= (type a) clojask.dataframe.DataFrame) (= (type b) clojask.dataframe.DataFrame))) 
      (throw (Clojask_TypeException. "First two arguments should be Clojask dataframes.")))
    (cond (not (= (count a-keys) (count b-keys))) 
      (throw (Clojask_TypeException. "The length of left keys and right keys should be equal.")))
    (cond (not (and (u/are-in a-keys a) (u/are-in b-keys b))) 
      (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (JoinedDataFrame. b a b-keys a-keys nil nil 2 nil [(nth col-prefix 1) (nth col-prefix 0)])))


(defn rolling-join-forward
  [a b a-keys b-keys a-roll b-roll & {:keys [col-prefix limit] :or {col-prefix ["1" "2"] limit nil}}]
  (let [a-keys (u/proc-groupby-key a-keys)
        b-keys (u/proc-groupby-key b-keys)
        a-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info a)) (nth _ 1))]) a-keys)
        b-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info b)) (nth _ 1))]) b-keys)]
    (cond (not (and (= (type a-roll) java.lang.String) (= (type b-roll) java.lang.String)))
      (throw (Clojask_TypeException. "Rolling keys should be strings")))
    (cond (not (and (= (type a) clojask.dataframe.DataFrame) (= (type b) clojask.dataframe.DataFrame))) 
      (throw (Clojask_TypeException. "First two arguments should be Clojask dataframes.")))
    (cond (not (= (count a-keys) (count b-keys))) 
      (throw (Clojask_TypeException. "The length of left keys and right keys should be equal.")))
    (cond (not (and (u/are-in a-keys a) (u/are-in b-keys b))) 
      (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (let [[a-roll b-roll] [(get (.getKeyIndex (:col-info a)) a-roll) (get (.getKeyIndex (:col-info b)) b-roll)]]
      (do
        (cond (not (and (not= a-roll nil) (not= b-roll nil)))
          (throw (Clojask_TypeException. "Rolling keys include non-existent column name(s).")))
        (JoinedDataFrame. a b a-keys b-keys a-roll b-roll 4 limit col-prefix)))))


;; all of the code is the same as above except for the last line
(defn rolling-join-backward
  [a b a-keys b-keys a-roll b-roll & {:keys [col-prefix limit] :or {col-prefix ["1" "2"] limit nil}}]
  (let [a-keys (u/proc-groupby-key a-keys)
        b-keys (u/proc-groupby-key b-keys)
        a-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info a)) (nth _ 1))]) a-keys)
        b-keys (mapv (fn [_] [(nth _ 0) (get (.getKeyIndex (.col-info b)) (nth _ 1))]) b-keys)]
    (cond (not (and (= (type a-roll) java.lang.String) (= (type b-roll) java.lang.String)))
          (throw (Clojask_TypeException. "Rolling keys should be strings")))
    (cond (not (and (= (type a) clojask.dataframe.DataFrame) (= (type b) clojask.dataframe.DataFrame)))
          (throw (Clojask_TypeException. "First two arguments should be Clojask dataframes.")))
    (cond (not (= (count a-keys) (count b-keys)))
          (throw (Clojask_TypeException. "The length of left keys and right keys should be equal.")))
    (cond (not (and (u/are-in a-keys a) (u/are-in b-keys b)))
          (throw (Clojask_TypeException. "Input includes non-existent column name(s).")))
    (let [[a-roll b-roll] [(get (.getKeyIndex (:col-info a)) a-roll) (get (.getKeyIndex (:col-info b)) b-roll)]]
      (do
        (cond (not (and (not= a-roll nil) (not= b-roll nil)))
              (throw (Clojask_TypeException. "Rolling keys include non-existent column name(s).")))
        ;; (.printJoinCol a b a-keys b-keys dist col-prefix)
        (JoinedDataFrame. a b a-keys b-keys a-roll b-roll 5 limit col-prefix)))))

(defn compute
  [this num-worker output-dir & {:keys [exception order select exclude] :or {exception false order true select nil exclude nil}}]
  (assert (or (nil? select) (nil? exclude)) "can only specify either of them")
  (u/init-file output-dir)
  ;; check which type of dataframe this is
  (let [exclude (if (coll? exclude) exclude [exclude])
        select (if select select (if (not= [nil] exclude) (doall (remove (fn [item] (.contains exclude item)) (.getColNames this))) nil))]
    (assert (not= select []) "must select at least 1 column")
    (if (= (type this) clojask.dataframe.DataFrame)
      (if (= (.getAggreFunc (:row-info this)) [])
        (.compute this num-worker output-dir exception order select)
        (if (not= (.getGroupbyKeys (:row-info this)) [])
          (.computeGroupAggre this num-worker output-dir exception select)
          (.computeAggre this num-worker output-dir exception select)))
      (if (= (type this) clojask.dataframe.JoinedDataFrame)
        (.compute this num-worker output-dir exception order select)
        (throw (Clojask_TypeException. "Must compute on a clojask dataframe or joined dataframe")))))
)
(defn get-col-names
  "Get the names for the columns in sequence"
  [this]
  ;; to-do: should implement both for the DataFrame and JoinedDataFrame => Done
  (.getColNames this)
  )
