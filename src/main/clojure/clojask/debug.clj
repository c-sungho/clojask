(ns clojask.debug
  (:require [clojask.dataframe :refer :all]
            [clojask.utils :as u]
            [clojask.groupby :refer :all]
            [clojask.sort :as sort]
            [clojask.api.aggregate :as aggre]
            [clojask.api.gb-aggregate :as gb-aggre])
  (:refer-clojure :exclude [group-by filter]))
"For debugging purposes only, will not be used in production."

(defn -main
  []
  ;(def x "Hello world")
  ;(-> (clojure.core/format "Expression '%s' not defined." x)(MyOwnException.)(throw))

  (def x (dataframe "resources/Employees.csv" :have-col true))
  (def y (dataframe "resources/Employees-info.csv" :have-col true))
  (def z (left-join x y ["Employee"] ["Employee"]))
  (time (compute x 8 "resources/test.csv" :exception true))

  ;(time (rolling-join-forward x y ["EmployeeName"] ["EmployeeName"] "Employee" "Employee" 8 "resources/test.csv" :exception false))

  ;(select-col y ["Salary" "EmployeeName"])
  ;(delete-col y ["Salary" "EmployeeName"])
  ;(print-df y)

  ;(println (.getKeys (.col-info y)))
  ;(set-type y "Salary" "double")
  ;(set-type y "EmployeeName" "double") ;; gives exception

  ;(operate y "Salary" (fn [x] (+ 10 x)))
  ;(operate y "Salary" (fn [] 2)) ;; gives exception

  ;(operate y str ["Employee" "Salary"] "new-col")
  ;(operate y ["Employee" "Salary"] "new-col" (fn [] 2)) ;; gives exception

  ;(print-df y)
  ;(filter y "Salary" (fn [salary] (<= salary 800)))
  ;(set-parser y "Department" #(Double/parseDouble %))

  ;(delete-col y ["Salary" "Department"])
  ;(println (col-names y))

  ;; (group-by y ["Department" "Employee"])
  ;; (aggregate y min ["Employee"] ["new-employee"])
  ;; (rename-col y ["Employee" "Department-A" "EmployeeName" "Salary"])

  ;; (set-type y "Department" "double")
  ;; (set-parser y "Salary" #(Double/parseDouble %))
  ;; (operate y - "Department")
  ;; (operate y str ["Employee" "Salary"] "new-col")

  ;(time (compute y 8 "resources/test.csv" :exception true :order true))

  ;; (-> (dataframe "resources/Employees-large.csv" :have-col true)
  ;;     (set-type "Salary" "double")
  ;;     (filter "Salary" (fn [salary] (<= salary 800)))
  ;;     (set-type "Department" "double")
  ;;     (operate - "Department")
  ;;     (operate str ["Employee" "Salary"] "new-col")
  ;;     (group-by ["Department"])
  ;;     (aggregate min ["Employee"] ["new-employee"])
  ;;     (compute 4 "resources/test.csv" :exception true :order true)
  ;;     time)
  
  ;; (println (.getKeys (.col-info y)))
  ;; ;(println "Renaming columns...")
  ;; (filter y ["Salary" "Department"] (fn [salary] (<= salary 800)))
  ;; (filter y ["Salary" "Department"] (fn [salary] (<= salary 800)))
  ;; (group-by y "Department")
  ;; (aggregate y aggre-avg ["Department" "Salary"] ["dept-avg" "salary-avg"])
  ;; (reorder-col y ["Employee" "Department-x" "EmployeeName" "Salary"])
  ;; (.reorderCol (.row-info y) (.getDesc (.col-info y)) ["Employee" "Department" "EmployeeName" "Salary"])
  ;; (println (.getKeys (.col-info y)))
  
  ;; Benchmarking

  ;(def y (dataframe "../clojure-datasets/data-CRSP.csv"))
  ;(def y (dataframe "../clojure-datasets/data-Compustat-lohi.csv"))
  ;(set-type y "datacqtr" "double")
  ;(operate y - "prccq")
  ;(operate y str ["PERMCO" "PERMNO"] "new-col")
  ;(group-by y "TICKER")
  ;(aggregate y min ["datacqtr"] ["datacqtr-min"])
  ;(group-by y "TICKER")
  ;(aggregate y min ["PRC"] ["PRC-min"])
  ;(time (compute y 4 "resources/test.csv" :exception false))

  ;; CRSP Benchmarking

  ;(def x (dataframe "../clojure-datasets/data-CRSP.csv"))
  ;(def x (dataframe "resources/CRSP-extract.csv"))
  ;(def y (dataframe "../clojure-datasets/data-Compustat-lohi.csv"))

  ; join on (TIC, DATE)
  ;(time (rolling-join-forward x y ["TICKER"] ["tic"] "date" "datadate" 4 "resources/test.csv" :exception false))
  ;(time (inner-join x y ["date" "TICKER"] ["datadate" "TICKER"] 4 "resources/test.csv" :exception false))
  )