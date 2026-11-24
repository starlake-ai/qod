package ai.starlake.quack.edge.cls

import ai.starlake.quack.ondemand.state.RoleColumnPolicy

/** One definition contract for REST/SQL policy writes and atomic manifest validation. */
object ColumnPolicyDefinition:
  def validate(
      catalog: String,
      schema: String,
      table: String,
      column: String,
      action: String,
      transform: Option[String]
  ): Either[String, Option[String]] =
    val blank = List("catalogName" -> catalog, "schemaName" -> schema, "tableName" -> table)
      .collect { case (name, value) if value.trim.isEmpty => name }
    val sql = transform.map(_.trim).filter(_.nonEmpty)
    if blank.nonEmpty then
      Left(s"${blank.mkString(", ")} must not be blank (use '*' to match every one)")
    else if !RoleColumnPolicy.ValidActions.contains(action) then
      Left(s"action must be one of ${RoleColumnPolicy.ValidActions.mkString(", ")}")
    else if column == "*" || column.trim.isEmpty then
      Left("columnName is required and must not be '*'")
    else if action == RoleColumnPolicy.ActionDeny then
      if sql.isDefined then Left("transformSql must be empty when action='deny'") else Right(None)
    else
      sql.toRight("transformSql is required when action='mask'").flatMap { raw =>
        TransformSqlValidator.validate(raw, column) match
          case TransformSqlValidator.Valid(canonical) => Right(Some(canonical))
          case TransformSqlValidator.Invalid(reason)  => Left(s"invalid transformSql: $reason")
      }
