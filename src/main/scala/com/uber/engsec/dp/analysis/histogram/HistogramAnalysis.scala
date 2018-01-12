/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.engsec.dp.analysis.histogram

import com.uber.engsec.dp.dataflow.AggFunctions._
import com.uber.engsec.dp.dataflow.column.{NodeColumnFacts, RelNodeColumnAnalysis}
import com.uber.engsec.dp.dataflow.domain._
import com.uber.engsec.dp.dataflow.domain.lattice.FlatLatticeDomain
import org.apache.calcite.rel.core.{Aggregate, TableScan}

/** Returns the aggregation status of each output column of a query. The results of this analysis are used to classify
  * queries as statistical or raw data and to determine which columns contain aggregations.
  */
class HistogramAnalysis extends RelNodeColumnAnalysis(UnitDomain, AggregationDomain) {

  override def transferAggregate(node: Aggregate, aggFunctions: IndexedSeq[Option[AggFunction]], state: NodeColumnFacts[Unit, AggregationInfo]) = {
    val newColFacts = state.colFacts.zipWithIndex.map { case (state, idx) =>
      val aggFunction = aggFunctions(idx)

      if (aggFunction.isEmpty) // grouped column
        state.copy(isGroupBy = true)
      else {
        val newReferences: Set[QualifiedColumnName] = aggFunction.get match {
          case COUNT => state.references.map{ _.table }.toList.distinct.map{ QualifiedColumnName(_, "*") }.toSet
          case _ => state.references
        }

        AggregationInfo(
          isAggregation = true,
          outermostAggregation = aggFunction,
          references = newReferences,
          isGroupBy = false
        )
      }
    }

    NodeColumnFacts(UnitDomain.bottom, newColFacts)
  }

  override def transferTableScan(node: TableScan, state: NodeColumnFacts[Unit, AggregationInfo]) = {
    import scala.collection.JavaConverters._

    val tableName = node.getTable.getQualifiedName.asScala.mkString(".")
    val colNames = node.getRowType.getFieldNames.asScala

    val newColFacts = state.colFacts.zip(colNames).map { case (state, colName) =>
      val qualifiedColName = QualifiedColumnName(tableName, colName)
      state.copy(references = Set(qualifiedColName))
    }

    NodeColumnFacts(UnitDomain.bottom, newColFacts)
  }
}

/** Information about the aggregation status of a column
  *
  * @param isAggregation Is this column any type of aggregation?
  * @param outermostAggregation Outermost aggregation function applied to references
  * @param references Fully qualified table and column names
  * @param isGroupBy Is this column grouped?
  */
case class AggregationInfo(isAggregation: Boolean,
                           outermostAggregation: DomainElem[AggFunction],
                           references: Set[QualifiedColumnName],
                           isGroupBy: Boolean)

object AggregationDomain extends AbstractDomain[AggregationInfo] {
  override val bottom: AggregationInfo = AggregationInfo(false, FlatLatticeDomain.bottom, Set.empty, false)

  override def leastUpperBound(first: AggregationInfo, second: AggregationInfo): AggregationInfo = {
    AggregationInfo(
      isAggregation=first.isAggregation || second.isAggregation,
      outermostAggregation=FlatLatticeDomain.leastUpperBound(first.outermostAggregation, second.outermostAggregation),
      references=first.references ++ second.references,
      isGroupBy=first.isGroupBy || second.isGroupBy
    )
  }
}

case class QualifiedColumnName(table: String, column: String)
