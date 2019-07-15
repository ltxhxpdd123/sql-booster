package org.apache.spark.sql.catalyst.optimizer.rewrite.rule

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.optimizer.RewriteHelper
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan


/**
  * This is entry point of Plan rewrite.
  * Every Rewrite Rule contains a LogicalPlanRewritePipeline which is composed by a bunch of PipelineItemExecutor.
  *
  * PipelineItemExecutor contains:
  *
  * 1. A ExpressionMatcher, check where we can rewrite some part of SQL, and if we can, how to compensate expressions.
  * 2. A LogicalPlanRewrite, do the logical plan rewrite and return a new plan.
  *
  * For example:
  *
  * [[WithoutJoinGroupRule]] is a RewriteMatchRule, it is designed for the SQL like `select * from a where m='yes'` which
  * without agg,groupby and join.
  *
  * WithoutJoinGroupRule have three items in PipelineItemExecutor:
  *
  * 1. Project Matcher/Rewriter
  * 2. Predicate Matcher/Rewriter
  * 3. Table(View) Matcher/Rewriter
  */
trait RewriteMatchRule extends RewriteHelper {
  def fetchView(plan: LogicalPlan): Option[ViewLogicalPlan]

  def rewrite(plan: LogicalPlan): LogicalPlan
}

trait MatchOrRewrite {}

trait LogicalPlanRewrite extends MatchOrRewrite with RewriteHelper {
  protected var _compensationExpressions: CompensationExpressions = null

  def compensationExpressions(ce: CompensationExpressions) = {
    _compensationExpressions = ce
    this
  }

  def _back(newPlan: LogicalPlan) = {
    newPlan transformDown {
      case RewritedLeafLogicalPlan(inner) => inner
    }
  }

  def rewrite(plan: LogicalPlan): LogicalPlan
}

trait ExpressionMatcher extends MatchOrRewrite with ExpressionMatcherHelper {
  val DEFAULT = CompensationExpressions(false, Seq())

  def compare: CompensationExpressions
}


trait ExpressionMatcherHelper extends MatchOrRewrite with RewriteHelper {
  def isSubSetOf(e1: Seq[Expression], e2: Seq[Expression]) = {
    e1.map { item1 =>
      e2.map { item2 =>
        if (item2.semanticEquals(item1)) 1 else 0
      }.sum
    }.sum == e1.size
  }

  def isSubSetOfWithOrder(e1: Seq[Expression], e2: Seq[Expression]) = {
    val zipCount = Math.min(e1.size, e2.size)
    (0 until zipCount).map { index =>
      if (e1(index).semanticEquals(e2(index)))
        0
      else 1
    }.sum == 0
  }

  def subset[T](e1: Seq[T], e2: Seq[T]) = {
    assert(e1.size >= e2.size)
    if (e1.size == 0) Seq[Expression]()
    e1.slice(e2.size, e1.size)
  }
}

case class CompensationExpressions(isRewriteSuccess: Boolean, compensation: Seq[Expression])

class LogicalPlanRewritePipeline(pipeline: Seq[PipelineItemExecutor]) extends Logging {
  def rewrite(plan: LogicalPlan): LogicalPlan = {

    var planRewrite: RewritedLogicalPlan = RewritedLogicalPlan(plan, false)

    (0 until pipeline.size).foreach { index =>

      if (!planRewrite.stopPipeline) {
        pipeline(index).execute(planRewrite) match {
          case a@RewritedLogicalPlan(_, true) =>
            logInfo(s"Pipeline item [${pipeline(index)}] fails. ")
            planRewrite = a
          case a@RewritedLogicalPlan(_, false) =>
            planRewrite = a
        }
      }
    }
    planRewrite
  }
}

object LogicalPlanRewritePipeline {
  def apply(pipeline: Seq[PipelineItemExecutor]): LogicalPlanRewritePipeline = new LogicalPlanRewritePipeline(pipeline)
}

case class PipelineItemExecutor(matcher: ExpressionMatcher, reWriter: LogicalPlanRewrite) {
  def execute(plan: LogicalPlan) = {
    val compsation = matcher.compare
    compsation match {
      case CompensationExpressions(true, _) =>
        reWriter.compensationExpressions(compsation)
        reWriter.rewrite(plan)
      case CompensationExpressions(false, _) => RewritedLogicalPlan(plan, stopPipeline = true)
    }
  }
}

case class RewritedLogicalPlan(inner: LogicalPlan, val stopPipeline: Boolean = false) extends LogicalPlan {
  override def output: Seq[Attribute] = inner.output

  override def children: Seq[LogicalPlan] = Seq(inner)


}

case class RewritedLeafLogicalPlan(inner: LogicalPlan) extends LogicalPlan {
  override def output: Seq[Attribute] = Seq()

  override def children: Seq[LogicalPlan] = Seq()
}

case class ViewLogicalPlan(tableLogicalPlan: LogicalPlan, viewCreateLogicalPlan: LogicalPlan)

