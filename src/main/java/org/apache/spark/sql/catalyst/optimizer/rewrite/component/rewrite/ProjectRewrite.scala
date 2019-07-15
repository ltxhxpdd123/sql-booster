package org.apache.spark.sql.catalyst.optimizer.rewrite.component.rewrite

import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.optimizer.rewrite.rule.{LogicalPlanRewrite, ViewLogicalPlan}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Project}

/**
  * 2019-07-15 WilliamZhu(allwefantasy@gmail.com)
  */
class ProjectRewrite(viewLogicalPlan: ViewLogicalPlan) extends LogicalPlanRewrite {

  override def rewrite(plan: LogicalPlan): LogicalPlan = {

    def locate(expr: Expression): Int = {
      viewLogicalPlan.viewCreateLogicalPlan match {
        case Project(projectList, _) => {
          projectList.zipWithIndex.map { case (item, index) =>
            item.semanticEquals(expr)
            return index
          }
        }
      }
      return -1
    }

    val targetProjectList = viewLogicalPlan.tableLogicalPlan match {
      case Project(projectList, child) =>
        projectList
    }

    val newPlan = plan transformDown {
      case Project(projectList, child) =>
        val newProjectList = projectList.map(locate).map(targetProjectList(_))
        Project(newProjectList, child)
    }
    _back(newPlan)
  }
}
