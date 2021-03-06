/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.api.scala

import org.apache.commons.lang3.Validate
import org.apache.flink.api.common.aggregators.Aggregator
import org.apache.flink.api.common.functions._
import org.apache.flink.api.common.io.{FileOutputFormat, OutputFormat}
import org.apache.flink.api.java.aggregation.Aggregations
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.api.java.io.{PrintingOutputFormat, TextOutputFormat}
import org.apache.flink.api.java.operators.JoinOperator.JoinHint
import org.apache.flink.api.java.operators.Keys.FieldPositionKeys
import org.apache.flink.api.java.operators._
import org.apache.flink.api.java.{DataSet => JavaDataSet}
import org.apache.flink.api.scala.operators.{ScalaCsvOutputFormat, ScalaAggregateOperator}
import org.apache.flink.core.fs.FileSystem.WriteMode
import org.apache.flink.core.fs.{FileSystem, Path}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.util.Collector

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * The DataSet, the basic abstraction of Flink. This represents a collection of elements of a
 * specific type `T`. The operations in this class can be used to create new DataSets and to combine
 * two DataSets. The methods of [[ExecutionEnvironment]] can be used to create a DataSet from an
 * external source, such as files in HDFS. The `write*` methods can be used to write the elements
 * to storage.
 *
 * All operations accept either a lambda function or an operation-specific function object for
 * specifying the operation. For example, using a lambda:
 * {{{
 *   val input: DataSet[String] = ...
 *   val mapped = input flatMap { _.split(" ") }
 * }}}
 * And using a `MapFunction`:
 * {{{
 *   val input: DataSet[String] = ...
 *   val mapped = input flatMap { new FlatMapFunction[String, String] {
 *     def flatMap(in: String, out: Collector[String]): Unit = {
 *       in.split(" ") foreach { out.collect(_) }
 *     }
 *   }
 * }}}
 *
 * A rich function can be used when more control is required, for example for accessing the
 * `RuntimeContext`. The rich function for `flatMap` is `RichFlatMapFunction`, all other functions
 * are named similarly. All functions are available in package
 * `org.apache.flink.api.common.functions`.
 *
 * The elements are partitioned depending on the degree of parallelism of the
 * [[ExecutionEnvironment]] or of one specific DataSet.
 *
 * Most of the operations have an implicit [[TypeInformation]] parameter. This is supplied by
 * an implicit conversion in the `flink.api.scala` Package. For this to work,
 * [[createTypeInformation]] needs to be imported. This is normally achieved with a
 * {{{
 *   import org.apache.flink.api.scala._
 * }}}
 *
 * @tparam T The type of the DataSet, i.e., the type of the elements of the DataSet.
 */
class DataSet[T: ClassTag](private[flink] val set: JavaDataSet[T]) {
  Validate.notNull(set, "Java DataSet must not be null.")

  // --------------------------------------------------------------------------------------------
  //  General methods
  // --------------------------------------------------------------------------------------------
  // These are actually implemented in subclasses of the Java DataSet but we perform checking
  // here and just pass through the calls to make everything much simpler.

  /**
   * Sets the name of the DataSet. This will appear in logs and graphical
   * representations of the execution graph.
   */
  def name(name: String) = {
    set match {
      case ds: DataSource[_] => ds.name(name)
      case op: Operator[_, _] => op.name(name)
      case di: DeltaIterationResultSet[_, _] => di.getIterationHead.name(name)
      case _ =>
        throw new UnsupportedOperationException("Operator " + set.toString + " cannot have a name.")
    }
    // return this for chaining methods calls
    this
  }

  /**
   * Sets the degree of parallelism of this operation. This must be greater than 1.
   */
  def setParallelism(dop: Int) = {
    set match {
      case ds: DataSource[_] => ds.setParallelism(dop)
      case op: Operator[_, _] => op.setParallelism(dop)
      case di: DeltaIterationResultSet[_, _] => di.getIterationHead.parallelism(dop)
      case _ =>
        throw new UnsupportedOperationException("Operator " + set.toString + " cannot have " +
          "parallelism.")
    }
    this
  }

  /**
   * Returns the degree of parallelism of this operation.
   */
  def getParallelism: Int = set match {
    case ds: DataSource[_] => ds.getParallelism
    case op: Operator[_, _] => op.getParallelism
    case _ =>
      throw new UnsupportedOperationException("Operator " + set.toString + " does not have " +
        "parallelism.")
  }

  /**
   * Registers an [[org.apache.flink.api.common.aggregators.Aggregator]]
   * for the iteration. Aggregators can be used to maintain simple statistics during the
   * iteration, such as number of elements processed. The aggregators compute global aggregates:
   * After each iteration step, the values are globally aggregated to produce one aggregate that
   * represents statistics across all parallel instances.
   * The value of an aggregator can be accessed in the next iteration.
   *
   * Aggregators can be accessed inside a function via
   * [[org.apache.flink.api.common.functions.AbstractRichFunction#getIterationRuntimeContext]].
   *
   * @param name The name under which the aggregator is registered.
   * @param aggregator The aggregator class.
   */
  def registerAggregator(name: String, aggregator: Aggregator[_]): DataSet[T] = {
    set match {
      case di: DeltaIterationResultSet[_, _] =>
        di.getIterationHead.registerAggregator(name, aggregator)
      case _ =>
        throw new UnsupportedOperationException("Operator " + set.toString + " cannot have " +
          "aggregators.")
    }
    this
  }

  /**
   * Adds a certain data set as a broadcast set to this operator. Broadcast data sets are
   * available at all
   * parallel instances of this operator. A broadcast data set is registered under a certain
   * name, and can be
   * retrieved under that name from the operators runtime context via
   * `org.apache.flink.api.common.functions.RuntimeContext.getBroadCastVariable(String)`
   *
   * The runtime context itself is available in all UDFs via
   * `org.apache.flink.api.common.functions.AbstractRichFunction#getRuntimeContext()`
   *
   * @param data The data set to be broadcasted.
   * @param name The name under which the broadcast data set retrieved.
   * @return The operator itself, to allow chaining function calls.
   */
  def withBroadcastSet(data: DataSet[_], name: String) = {
    set match {
      case udfOp: UdfOperator[_] => udfOp.withBroadcastSet(data.set, name)
      case _ =>
        throw new UnsupportedOperationException("Operator " + set.toString + " cannot have " +
          "broadcast variables.")
    }
    this
  }

  def withConstantSet(constantSets: String*) = {
    set match {
      case op: SingleInputUdfOperator[_, _, _] => op.withConstantSet(constantSets: _*)
      case _ =>
        throw new UnsupportedOperationException("Cannot specify constant sets on Operator " +
          set.toString + ".")
    }
    this
  }

  def withConstantSetFirst(constantSets: String*) = {
    set match {
      case op: TwoInputUdfOperator[_, _, _, _] => op.withConstantSetFirst(constantSets: _*)
      case _ =>
        throw new UnsupportedOperationException("Cannot specify constant sets on Operator " + set
          .toString + ".")
    }
    this
  }

  def withConstantSetSecond(constantSets: String*) = {
    set match {
      case op: TwoInputUdfOperator[_, _, _, _] => op.withConstantSetSecond(constantSets: _*)
      case _ =>
        throw new UnsupportedOperationException("Cannot specify constant sets on Operator " + set
          .toString + ".")
    }
    this
  }

  // --------------------------------------------------------------------------------------------
  //  Filter & Transformations
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet by applying the given function to every element of this DataSet.
   */
  def map[R: TypeInformation: ClassTag](mapper: MapFunction[T, R]): DataSet[R] = {
    if (mapper == null) {
      throw new NullPointerException("Map function must not be null.")
    }
    wrap(new MapOperator[T, R](set, implicitly[TypeInformation[R]], mapper))
  }

  /**
   * Creates a new DataSet by applying the given function to every element of this DataSet.
   */
  def map[R: TypeInformation: ClassTag](fun: (T) => R): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("Map function must not be null.")
    }
    val mapper = new MapFunction[T, R] {
      def map(in: T): R = fun(in)
    }
    wrap(new MapOperator[T, R](set, implicitly[TypeInformation[R]], mapper))
  }

  /**
   * Creates a new DataSet by applying the given function to each parallel partition of the
   * DataSet.
   *
   * This function is intended for operations that cannot transform individual elements and
   * requires no grouping of elements. To transform individual elements,
   * the use of [[map]] and [[flatMap]] is preferable.
   */
  def mapPartition[R: TypeInformation: ClassTag](
      partitionMapper: MapPartitionFunction[T, R]): DataSet[R] = {
    if (partitionMapper == null) {
      throw new NullPointerException("MapPartition function must not be null.")
    }
    wrap(new MapPartitionOperator[T, R](set, implicitly[TypeInformation[R]], partitionMapper))
  }

  /**
   * Creates a new DataSet by applying the given function to each parallel partition of the
   * DataSet.
   *
   * This function is intended for operations that cannot transform individual elements and
   * requires no grouping of elements. To transform individual elements,
   * the use of [[map]] and [[flatMap]] is preferable.
   */
  def mapPartition[R: TypeInformation: ClassTag](
      fun: (TraversableOnce[T], Collector[R]) => Unit): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("MapPartition function must not be null.")
    }
    val partitionMapper = new MapPartitionFunction[T, R] {
      def mapPartition(in: java.lang.Iterable[T], out: Collector[R]) {
        fun(in.iterator().asScala, out)
      }
    }
    wrap(new MapPartitionOperator[T, R](set, implicitly[TypeInformation[R]], partitionMapper))
  }

  /**
   * Creates a new DataSet by applying the given function to each parallel partition of the
   * DataSet.
   *
   * This function is intended for operations that cannot transform individual elements and
   * requires no grouping of elements. To transform individual elements,
   * the use of [[map]] and [[flatMap]] is preferable.
   */
  def mapPartition[R: TypeInformation: ClassTag](
      fun: (TraversableOnce[T]) => TraversableOnce[R]): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("MapPartition function must not be null.")
    }
    val partitionMapper = new MapPartitionFunction[T, R] {
      def mapPartition(in: java.lang.Iterable[T], out: Collector[R]) {
        fun(in.iterator().asScala) foreach out.collect
      }
    }
    wrap(new MapPartitionOperator[T, R](set, implicitly[TypeInformation[R]], partitionMapper))
  }

  /**
   * Creates a new DataSet by applying the given function to every element and flattening
   * the results.
   */
  def flatMap[R: TypeInformation: ClassTag](flatMapper: FlatMapFunction[T, R]): DataSet[R] = {
    if (flatMapper == null) {
      throw new NullPointerException("FlatMap function must not be null.")
    }
    wrap(new FlatMapOperator[T, R](set, implicitly[TypeInformation[R]], flatMapper))
  }

  /**
   * Creates a new DataSet by applying the given function to every element and flattening
   * the results.
   */
  def flatMap[R: TypeInformation: ClassTag](fun: (T, Collector[R]) => Unit): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("FlatMap function must not be null.")
    }
    val flatMapper = new FlatMapFunction[T, R] {
      def flatMap(in: T, out: Collector[R]) { fun(in, out) }
    }
    wrap(new FlatMapOperator[T, R](set, implicitly[TypeInformation[R]], flatMapper))
  }

  /**
   * Creates a new DataSet by applying the given function to every element and flattening
   * the results.
   */
  def flatMap[R: TypeInformation: ClassTag](fun: (T) => TraversableOnce[R]): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("FlatMap function must not be null.")
    }
    val flatMapper = new FlatMapFunction[T, R] {
      def flatMap(in: T, out: Collector[R]) { fun(in) foreach out.collect }
    }
    wrap(new FlatMapOperator[T, R](set, implicitly[TypeInformation[R]], flatMapper))
  }

  /**
   * Creates a new DataSet that contains only the elements satisfying the given filter predicate.
   */
  def filter(filter: FilterFunction[T]): DataSet[T] = {
    if (filter == null) {
      throw new NullPointerException("Filter function must not be null.")
    }
    wrap(new FilterOperator[T](set, filter))
  }

  /**
   * Creates a new DataSet that contains only the elements satisfying the given filter predicate.
   */
  def filter(fun: (T) => Boolean): DataSet[T] = {
    if (fun == null) {
      throw new NullPointerException("Filter function must not be null.")
    }
    val filter = new FilterFunction[T] {
      def filter(in: T) = fun(in)
    }
    wrap(new FilterOperator[T](set, filter))
  }

  // --------------------------------------------------------------------------------------------
  //  Non-grouped aggregations
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new [[DataSet]] by aggregating the specified tuple field using the given aggregation
   * function. Since this is not a keyed DataSet the aggregation will be performed on the whole
   * collection of elements.
   *
   * This only works on Tuple DataSets.
   */
  def aggregate(agg: Aggregations, field: Int): AggregateDataSet[T] = {
    new AggregateDataSet(new ScalaAggregateOperator[T](set, agg, field))
  }

  /**
   * Creates a new [[DataSet]] by aggregating the specified field using the given aggregation
   * function. Since this is not a keyed DataSet the aggregation will be performed on the whole
   * collection of elements.
   *
   * This only works on CaseClass DataSets.
   */
  def aggregate(agg: Aggregations, field: String): AggregateDataSet[T] = {
    val fieldIndex = fieldNames2Indices(set.getType, Array(field))(0)

    new AggregateDataSet(new ScalaAggregateOperator[T](set, agg, fieldIndex))
  }

  /**
   * Syntactic sugar for [[aggregate]] with `SUM`
   */
  def sum(field: Int) = {
    aggregate(Aggregations.SUM, field)
  }

  /**
   * Syntactic sugar for [[aggregate]] with `MAX`
   */
  def max(field: Int) = {
    aggregate(Aggregations.MAX, field)
  }

  /**
   * Syntactic sugar for [[aggregate]] with `MIN`
   */
  def min(field: Int) = {
    aggregate(Aggregations.MIN, field)
  }

  /**
   * Syntactic sugar for [[aggregate]] with `SUM`
   */
  def sum(field: String) = {
    aggregate(Aggregations.SUM, field)
  }

  /**
   * Syntactic sugar for [[aggregate]] with `MAX`
   */
  def max(field: String) = {
    aggregate(Aggregations.MAX, field)
  }

  /**
   * Syntactic sugar for [[aggregate]] with `MIN`
   */
  def min(field: String) = {
    aggregate(Aggregations.MIN, field)
  }

  /**
   * Creates a new [[DataSet]] by merging the elements of this DataSet using an associative reduce
   * function.
   */
  def reduce(reducer: ReduceFunction[T]): DataSet[T] = {
    if (reducer == null) {
      throw new NullPointerException("Reduce function must not be null.")
    }
    wrap(new ReduceOperator[T](set, reducer))
  }

  /**
   * Creates a new [[DataSet]] by merging the elements of this DataSet using an associative reduce
   * function.
   */
  def reduce(fun: (T, T) => T): DataSet[T] = {
    if (fun == null) {
      throw new NullPointerException("Reduce function must not be null.")
    }
    val reducer = new ReduceFunction[T] {
      def reduce(v1: T, v2: T) = { fun(v1, v2) }
    }
    wrap(new ReduceOperator[T](set, reducer))
  }

  /**
   * Creates a new [[DataSet]] by passing all elements in this DataSet to the group reduce function.
   * The function can output zero or more elements using the [[Collector]]. The concatenation of the
   * emitted values will form the resulting [[DataSet]].
   */
  def reduceGroup[R: TypeInformation: ClassTag](reducer: GroupReduceFunction[T, R]): DataSet[R] = {
    if (reducer == null) {
      throw new NullPointerException("GroupReduce function must not be null.")
    }
    wrap(new GroupReduceOperator[T, R](set, implicitly[TypeInformation[R]], reducer))
  }

  /**
   * Creates a new [[DataSet]] by passing all elements in this DataSet to the group reduce function.
   * The function can output zero or more elements using the [[Collector]]. The concatenation of the
   * emitted values will form the resulting [[DataSet]].
   */
  def reduceGroup[R: TypeInformation: ClassTag](
      fun: (TraversableOnce[T], Collector[R]) => Unit): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("GroupReduce function must not be null.")
    }
    val reducer = new GroupReduceFunction[T, R] {
      def reduce(in: java.lang.Iterable[T], out: Collector[R]) { fun(in.iterator().asScala, out) }
    }
    wrap(new GroupReduceOperator[T, R](set, implicitly[TypeInformation[R]], reducer))
  }

  /**
   * Creates a new [[DataSet]] by passing all elements in this DataSet to the group reduce function.
   */
  def reduceGroup[R: TypeInformation: ClassTag](fun: (TraversableOnce[T]) => R): DataSet[R] = {
    if (fun == null) {
      throw new NullPointerException("GroupReduce function must not be null.")
    }
    val reducer = new GroupReduceFunction[T, R] {
      def reduce(in: java.lang.Iterable[T], out: Collector[R]) {
        out.collect(fun(in.iterator().asScala))
      }
    }
    wrap(new GroupReduceOperator[T, R](set, implicitly[TypeInformation[R]], reducer))
  }

  // --------------------------------------------------------------------------------------------
  //  distinct
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet containing the distinct elements of this DataSet. The decision whether
   * two elements are distinct or not is made using the return value of the given function.
   */
  def distinct[K: TypeInformation](fun: (T) => K): DataSet[T] = {
    val keyExtractor = new KeySelector[T, K] {
      def getKey(in: T) = fun(in)
    }
    wrap(new DistinctOperator[T](
      set,
      new Keys.SelectorFunctionKeys[T, K](
        keyExtractor, set.getType, implicitly[TypeInformation[K]])))
  }

  /**
   * Creates a new DataSet containing the distinct elements of this DataSet. The decision whether
   * two elements are distinct or not is made based on only the specified tuple fields.
   *
   * This only works on tuple DataSets.
   */
  def distinct(fields: Int*): DataSet[T] = {
    wrap(new DistinctOperator[T](
      set,
      new Keys.FieldPositionKeys[T](fields.toArray, set.getType, true)))
  }

  /**
   * Creates a new DataSet containing the distinct elements of this DataSet. The decision whether
   * two elements are distinct or not is made based on only the specified fields.
   *
   * This only works on CaseClass DataSets
   */
  def distinct(firstField: String, otherFields: String*): DataSet[T] = {
    val fieldIndices = fieldNames2Indices(set.getType, firstField +: otherFields.toArray)
    wrap(new DistinctOperator[T](
      set,
      new Keys.FieldPositionKeys[T](fieldIndices, set.getType, true)))
  }

  /**
   * Creates a new DataSet containing the distinct elements of this DataSet. The decision whether
   * two elements are distinct or not is made based on all tuple fields.
   *
   * This only works if this DataSet contains Tuples.
   */
  def distinct: DataSet[T] = {
    wrap(new DistinctOperator[T](set, null))
  }

  // --------------------------------------------------------------------------------------------
  //  Keyed DataSet
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a [[GroupedDataSet]] which provides operations on groups of elements. Elements are
   * grouped based on the value returned by the given function.
   *
   * This will not create a new DataSet, it will just attach the key function which will be used
   * for grouping when executing a grouped operation.
   */
  def groupBy[K: TypeInformation](fun: (T) => K): GroupedDataSet[T] = {
    val keyType = implicitly[TypeInformation[K]]
    val keyExtractor = new KeySelector[T, K] {
      def getKey(in: T) = fun(in)
    }
    new GroupedDataSetImpl[T](set,
      new Keys.SelectorFunctionKeys[T, K](keyExtractor, set.getType, keyType))
  }

  /**
   * Creates a [[GroupedDataSet]] which provides operations on groups of elements. Elements are
   * grouped based on the given tuple fields.
   *
   * This will not create a new DataSet, it will just attach the tuple field positions which will be
   * used for grouping when executing a grouped operation.
   *
   * This only works on Tuple DataSets.
   */
  def groupBy(fields: Int*): GroupedDataSet[T] = {
    new GroupedDataSetImpl[T](
      set,
      new Keys.FieldPositionKeys[T](fields.toArray, set.getType,false))
  }

  /**
   * Creates a [[GroupedDataSet]] which provides operations on groups of elements. Elements are
   * grouped based on the given fields.
   *
   * This will not create a new DataSet, it will just attach the field names which will be
   * used for grouping when executing a grouped operation.
   *
   * This only works on CaseClass DataSets.
   */
  def groupBy(firstField: String, otherFields: String*): GroupedDataSet[T] = {
    val fieldIndices = fieldNames2Indices(set.getType, firstField +: otherFields.toArray)

    new GroupedDataSetImpl[T](
      set,
      new Keys.FieldPositionKeys[T](fieldIndices, set.getType,false))
  }

  //  public UnsortedGrouping<T> groupBy(String... fields) {
  //    new UnsortedGrouping<T>(this, new Keys.ExpressionKeys<T>(fields, getType()));
  //  }

  // --------------------------------------------------------------------------------------------
  //  Joining
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet by joining `this` DataSet with the `other` DataSet. To specify the join
   * keys the `where` and `isEqualTo` methods must be used. For example:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val joined = left.join(right).where(0).isEqualTo(1)
   * }}}
   *
   * The default join result is a DataSet with 2-Tuples of the joined values. In the above example
   * that would be `((String, Int, Int), (Int, String, Int))`. A custom join function can be used
   * if more control over the result is required. This can either be given as a lambda or a
   * custom [[JoinFunction]]. For example:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val joined = left.join(right).where(0).isEqualTo(1) { (l, r) =>
   *     (l._1, r._2)
   *   }
   * }}}
   * A join function with a [[Collector]] can be used to implement a filter directly in the join
   * or to output more than one values. This type of join function does not return a value, instead
   * values are emitted using the collector:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val joined = left.join(right).where(0).isEqualTo(1) {
   *     (l, r, out: Collector[(String, Int)]) =>
   *       if (l._2 > 4) {
   *         out.collect((l._1, r._3))
   *         out.collect((l._1, r._1))
   *       } else {
   *         None
   *       }
   *     }
   * }}}
   */
  def join[O](other: DataSet[O]): UnfinishedJoinOperation[T, O] =
    new UnfinishedJoinOperationImpl(this, other, JoinHint.OPTIMIZER_CHOOSES)

  /**
   * Special [[join]] operation for explicitly telling the system that the right side is assumed
   * to be a lot smaller than the left side of the join.
   */
  def joinWithTiny[O](other: DataSet[O]): UnfinishedJoinOperation[T, O] =
    new UnfinishedJoinOperationImpl(this, other, JoinHint.BROADCAST_HASH_SECOND)

  /**
   * Special [[join]] operation for explicitly telling the system that the left side is assumed
   * to be a lot smaller than the right side of the join.
   */
  def joinWithHuge[O](other: DataSet[O]): UnfinishedJoinOperation[T, O] =
    new UnfinishedJoinOperationImpl(this, other, JoinHint.BROADCAST_HASH_FIRST)

  // --------------------------------------------------------------------------------------------
  //  Co-Group
  // --------------------------------------------------------------------------------------------

  /**
   * For each key in `this` DataSet and the `other` DataSet, create a tuple containing a list
   * of elements for that key from both DataSets. To specify the join keys the `where` and
   * `isEqualTo` methods must be used. For example:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val coGrouped = left.coGroup(right).where(0).isEqualTo(1)
   * }}}
   *
   * A custom coGroup function can be used
   * if more control over the result is required. This can either be given as a lambda or a
   * custom [[CoGroupFunction]]. For example:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val coGrouped = left.coGroup(right).where(0).isEqualTo(1) { (l, r) =>
   *     // l and r are of type TraversableOnce
   *     (l.min, r.max)
   *   }
   * }}}
   * A coGroup function with a [[Collector]] can be used to implement a filter directly in the
   * coGroup or to output more than one values. This type of coGroup function does not return a
   * value, instead values are emitted using the collector
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val coGrouped = left.coGroup(right).where(0).isEqualTo(1) {
   *     (l, r, out: Collector[(String, Int)]) =>
   *       out.collect((l.min, r.max))
   *       out.collect(l.max, r.min))
   *     }
   * }}}
   */
  def coGroup[O: ClassTag](other: DataSet[O]): UnfinishedCoGroupOperation[T, O] =
    new UnfinishedCoGroupOperationImpl(this, other)

  // --------------------------------------------------------------------------------------------
  //  Cross
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet by forming the cartesian product of `this` DataSet and the `other`
   * DataSet.
   *
   * The default cross result is a DataSet with 2-Tuples of the combined values. A custom cross
   * function can be used if more control over the result is required. This can either be given as
   * a lambda or a custom [[CrossFunction]]. For example:
   * {{{
   *   val left: DataSet[(String, Int, Int)] = ...
   *   val right: DataSet[(Int, String, Int)] = ...
   *   val product = left.cross(right) { (l, r) => (l._2, r._3) }
   *   }
   * }}}
   */
  def cross[O](other: DataSet[O]): CrossDataSet[T, O] =
    CrossDataSetImpl.createCrossOperator(this.set, other.set)

  /**
   * Special [[cross]] operation for explicitly telling the system that the right side is assumed
   * to be a lot smaller than the left side of the cartesian product.
   */
  def crossWithTiny[O](other: DataSet[O]): CrossDataSet[T, O] =
    CrossDataSetImpl.createCrossOperator(this.set, other.set)

  /**
   * Special [[cross]] operation for explicitly telling the system that the left side is assumed
   * to be a lot smaller than the right side of the cartesian product.
   */
  def crossWithHuge[O](other: DataSet[O]): CrossDataSet[T, O] =
    CrossDataSetImpl.createCrossOperator(this.set, other.set)

  // --------------------------------------------------------------------------------------------
  //  Iterations
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet by performing bulk iterations using the given step function. The
   * iterations terminate when `maxIterations` iterations have been performed.
   *
   * For example:
   * {{{
   *   val input: DataSet[(String, Int)] = ...
   *   val iterated = input.iterate(5) { previous =>
   *     val next = previous.map { x => (x._1, x._2 + 1) }
   *     next
   *   }
   * }}}
   *
   * This example will simply increase the second field of the tuple by 5.
   */
  def iterate(maxIterations: Int)(stepFunction: (DataSet[T]) => DataSet[T]): DataSet[T] = {
    val iterativeSet =
      new IterativeDataSet[T](set.getExecutionEnvironment, set.getType, set,maxIterations)

    val resultSet = stepFunction(wrap(iterativeSet))
    val result = iterativeSet.closeWith(resultSet.set)
    wrap(result)
  }

  /**
   * Creates a new DataSet by performing bulk iterations using the given step function. The first
   * DataSet the step function returns is the input for the next iteration, the second DataSet is
   * the termination criterion. The iterations terminate when either the termination criterion
   * DataSet contains no elements or when `maxIterations` iterations have been performed.
   *
   *  For example:
   * {{{
   *   val input: DataSet[(String, Int)] = ...
   *   val iterated = input.iterateWithTermination(5) { previous =>
   *     val next = previous.map { x => (x._1, x._2 + 1) }
   *     val term = next.filter { _._2 <  3 }
   *     (next, term)
   *   }
   * }}}
   *
   * This example will simply increase the second field of the Tuples until they are no longer
   * smaller than 3.
   */
  def iterateWithTermination(maxIterations: Int)(
    stepFunction: (DataSet[T]) => (DataSet[T], DataSet[_])): DataSet[T] = {
    val iterativeSet =
      new IterativeDataSet[T](set.getExecutionEnvironment, set.getType, set,maxIterations)

    val (resultSet, terminationCriterion) = stepFunction(wrap(iterativeSet))
    val result = iterativeSet.closeWith(resultSet.set, terminationCriterion.set)
    wrap(result)
  }

  /**
   * Creates a new DataSet by performing delta (or workset) iterations using the given step
   * function. At the beginning `this` DataSet is the solution set and `workset` is the Workset.
   * The iteration step function gets the current solution set and workset and must output the
   * delta for the solution set and the workset for the next iteration.
   *
   * Note: The syntax of delta iterations are very likely going to change soon.
   */
  def iterateDelta[R: ClassTag](workset: DataSet[R], maxIterations: Int, keyFields: Array[Int])(
      stepFunction: (DataSet[T], DataSet[R]) => (DataSet[T], DataSet[R])) = {
    val key = new FieldPositionKeys[T](keyFields, set.getType, false)
    val iterativeSet = new DeltaIteration[T, R](
      set.getExecutionEnvironment, set.getType, set, workset.set, key, maxIterations)
    val (newSolution, newWorkset) = stepFunction(
      wrap(iterativeSet.getSolutionSet),
      wrap(iterativeSet.getWorkset))
    val result = iterativeSet.closeWith(newSolution.set, newWorkset.set)
    wrap(result)
  }

  /**
   * Creates a new DataSet by performing delta (or workset) iterations using the given step
   * function. At the beginning `this` DataSet is the solution set and `workset` is the Workset.
   * The iteration step function gets the current solution set and workset and must output the
   * delta for the solution set and the workset for the next iteration.
   *
   * Note: The syntax of delta iterations are very likely going to change soon.
   */
  def iterateDelta[R: ClassTag](workset: DataSet[R], maxIterations: Int, keyFields: Array[String])(
    stepFunction: (DataSet[T], DataSet[R]) => (DataSet[T], DataSet[R])) = {
    val fieldIndices = fieldNames2Indices(set.getType, keyFields)

    val key = new FieldPositionKeys[T](fieldIndices, set.getType, false)
    val iterativeSet = new DeltaIteration[T, R](
      set.getExecutionEnvironment, set.getType, set, workset.set, key, maxIterations)
    val (newSolution, newWorkset) = stepFunction(
      wrap(iterativeSet.getSolutionSet),
      wrap(iterativeSet.getWorkset))
    val result = iterativeSet.closeWith(newSolution.set, newWorkset.set)
    wrap(result)
  }

  // -------------------------------------------------------------------------------------------
  //  Custom Operators
  // -------------------------------------------------------------------------------------------

  // Keep it out until we have an actual use case for this.
//  /**
//   * Runs a [[CustomUnaryOperation]] on the data set. Custom operations are typically complex
//   * operators that are composed of multiple steps.
//   */
//  def runOperation[R: ClassTag](operation: CustomUnaryOperation[T, R]): DataSet[R] = {
//    Validate.notNull(operation, "The custom operator must not be null.")
//    operation.setInput(this.set)
//    wrap(operation.createResult)
//  }

  // --------------------------------------------------------------------------------------------
  //  Union
  // --------------------------------------------------------------------------------------------

  /**
   * Creates a new DataSet containing the elements from both `this` DataSet and the `other`
   * DataSet.
   */
  def union(other: DataSet[T]): DataSet[T] = wrap(new UnionOperator[T](set, other.set))

  // --------------------------------------------------------------------------------------------
  //  Result writing
  // --------------------------------------------------------------------------------------------

  /**
   * Writes `this` DataSet to the specified location. This uses [[AnyRef.toString]] on
   * each element.
   */
  def writeAsText(
      filePath: String,
      writeMode: FileSystem.WriteMode = WriteMode.NO_OVERWRITE): DataSink[T] = {
    val tof: TextOutputFormat[T] = new TextOutputFormat[T](new Path(filePath))
    tof.setWriteMode(writeMode)
    output(tof)
  }

  /**
   * Writes `this` DataSet to the specified location as a CSV file.
   *
   * This only works on Tuple DataSets. For individual tuple fields [[AnyRef.toString]] is used.
   */
  def writeAsCsv(
      filePath: String,
      rowDelimiter: String = ScalaCsvOutputFormat.DEFAULT_LINE_DELIMITER,
      fieldDelimiter: String = ScalaCsvOutputFormat.DEFAULT_FIELD_DELIMITER,
      writeMode: FileSystem.WriteMode = WriteMode.NO_OVERWRITE): DataSink[T] = {
    Validate.isTrue(set.getType.isTupleType, "CSV output can only be used with Tuple DataSets.")
    val of = new ScalaCsvOutputFormat[Product](new Path(filePath), rowDelimiter, fieldDelimiter)
    of.setWriteMode(writeMode)
    output(of.asInstanceOf[OutputFormat[T]])
  }

  /**
   * Writes `this` DataSet to the specified location using a custom
   * [[org.apache.flink.api.common.io.FileOutputFormat]].
   */
  def write(
      outputFormat: FileOutputFormat[T],
      filePath: String,
      writeMode: FileSystem.WriteMode = WriteMode.NO_OVERWRITE): DataSink[T] = {
    Validate.notNull(filePath, "File path must not be null.")
    Validate.notNull(outputFormat, "Output format must not be null.")
    outputFormat.setOutputFilePath(new Path(filePath))
    outputFormat.setWriteMode(writeMode)
    output(outputFormat)
  }

  /**
   * Emits `this` DataSet using a custom [[org.apache.flink.api.common.io.OutputFormat]].
   */
  def output(outputFormat: OutputFormat[T]): DataSink[T] = {
    set.output(outputFormat)
  }

  /**
   * Writes a DataSet to the standard output stream (stdout). This uses [[AnyRef.toString]] on
   * each element.
   */
  def print(): DataSink[T] = {
    output(new PrintingOutputFormat[T](false))
  }

  /**
   * Writes a DataSet to the standard error stream (stderr).This uses [[AnyRef.toString]] on
   * each element.
   */
  def printToErr(): DataSink[T] = {
    output(new PrintingOutputFormat[T](true))
  }
}
