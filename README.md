

===============================
除了视图改写 还有血缘分析功能 给定一个SQL，会分析用到的每个表以及对应用到的字段，另外还增加了每个输出列由那些表的那些字段生成的。
===============================
# sql-booster

This is a library for SQL optimizing/rewriting. 
Current version (0.2.0) we have already supports:
 
1. Materialized View rewrite.
2. Data Lineage analysis

This project is under active development and *** NOT READY FOR PRODUCTION ***.


# Liking
You can link against this library in your program at the following coordinates:

## Scala 2.11

```
groupId: tech.mlsql
artifactId: sql-booster_2.11
version: 0.3.0
```
## Deployment 

We recommend people wrap sql-booster with springboot(or other web framework) as http service. 

## View-based query rewriting usage

In order to do view-based query rewriting, you should register schema of  your concrete tables and  views manually.
Notice that we only need following information to make sql-booster work: 

1. table create statement
2. view  create statement 
3. view  schema (infer from view create statement automatically)


sql-booster supports three kinds of create statement:

1. MySQL/Oracle 
2. Hive
3. [SimpleSchema](https://github.com/allwefantasy/simple-schema)  


Steps:

1. Initial sql-booster, do only one time. 

```scala
ViewCatalyst.createViewCatalyst()
val schemaReg = new SchemaRegistry(spark)
```

2. register tables:

```scala
schemaReg.createRDTable(
      """
        |CREATE TABLE depts(
        |  deptno INT NOT NULL,
        |  deptname VARCHAR(20),
        |  PRIMARY KEY (deptno)
        |);
      """.stripMargin)

schemaReg.createRDTable(
  """
    |CREATE TABLE locations(
    |  locationid INT NOT NULL,
    |  state CHAR(2),
    |  PRIMARY KEY (locationid)
    |);
  """.stripMargin)

schemaReg.createRDTable(
  """
    |CREATE TABLE emps(
    |  empid INT NOT NULL,
    |  deptno INT NOT NULL,
    |  locationid INT NOT NULL,
    |  empname VARCHAR(20) NOT NULL,
    |  salary DECIMAL (18, 2),
    |  PRIMARY KEY (empid),
    |  FOREIGN KEY (deptno) REFERENCES depts(deptno),
    |  FOREIGN KEY (locationid) REFERENCES locations(locationid)
    |);
  """.stripMargin)

schemaReg.createHiveTable("src",
  """
    |CREATE TABLE IF NOT EXISTS src (key INT, value STRING) USING hive
  """.stripMargin)
```


3. register MV:

```scala
schemaReg.createMV("emps_mv",
      """
        |SELECT empid
        |FROM emps
        |JOIN depts ON depts.deptno = emps.deptno
      """.stripMargin)

```

4. Using MaterializedViewOptimizeRewrite to execute rewrite:


```scala
val rewrite3 = MaterializedViewOptimizeRewrite.execute(schemaReg.toLogicalPlan(
      """
        |select * from (SELECT e.empid
        |FROM emps e
        |JOIN depts d
        |ON e.deptno = d.deptno
        |where e.empid=1) as a where a.empid=2
      """.stripMargin))
```

5. Generate rewrite SQL

```scala
assert(schemaReg.genSQL(rewrite3)
      == "SELECT a.`empid` FROM (SELECT `empid` FROM emps_mv WHERE `empid` = CAST(1 AS BIGINT)) a WHERE a.`empid` = CAST(2 AS BIGINT)")
```




## Data Lineage Usage

Given a SQL, sql-booster can help you analysis:

1. tables and their corresponding columns which this sql dependents includes the columns used in where,select,join condition.
2. every output column of this sql is composed by which columns in the original tables

NOTICE: sql-booster needs you to register table schema firstly like described in **View-based query rewriting usage**.

Here is the example code:

```scala
val result = DataLineageExtractor.execute(schemaReg.toLogicalPlan(
      """
        |select * from (SELECT e.empid
        |FROM emps e
        |JOIN depts d
        |ON e.deptno = d.deptno
        |where e.empid=1) as a where a.empid=2
      """.stripMargin))
    println(JSONTool.pretty(result))
```

then output is like this:   

```json
{
  "outputMapToSourceTable":[{
    "name":"empid",
    "sources":[{
      "tableName":"emps",
      "columns":["empid"],
      "locates":[["PROJECT","FILTER"]]
    },{
      "tableName":"depts",
      "columns":[],
      "locates":[]
    }]
  }],
  "dependences":[{
    "tableName":"emps",
    "columns":["empid","deptno"],
    "locates":[["PROJECT","FILTER"],["JOIN"]]
  },{
    "tableName":"depts",
    "columns":["deptno"],
    "locates":[["JOIN"]]
  }]
}
```

this means the new table only have one column named empid, and it depends empid in table emps.
the new table depends emps and depts, and empid,deptno are required.

Also, sql-booster tell you the column appears in which part of the sql. There are 3 parts:

```
FILTER
GROUP_BY
JOIN
PROJECT
``` 

As to the example, depts.depno exists in `Join`, and emps.empid exists in PROJECT and FILTER(where condition). 
 


