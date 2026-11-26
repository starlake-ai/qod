package ai.starlake.quack.ondemand.fleet

import scala.util.matching.Regex

/** Kubernetes-style label rules for fleet servers. Matching is case-sensitive. */
object FleetLabels:

  val MaxLabels: Int = 64

  private val MaxNameLength   = 63
  private val MaxValueLength  = 63
  private val MaxPrefixLength = 253

  private val NamePattern: Regex   = "[A-Za-z0-9]([-A-Za-z0-9_.]*[A-Za-z0-9])?".r
  private val PrefixPattern: Regex =
    "[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*".r

  /** The first rule the pair breaks, as a sentence naming the key (and value), or None. */
  def problem(key: String, value: String): Option[String] =
    val (prefix, name) = key.indexOf('/') match
      case -1 => (None, key)
      case i  => (Some(key.substring(0, i)), key.substring(i + 1))
    prefix
      .flatMap(prefixProblem(key, _))
      .orElse(nameProblem(key, name))
      .orElse(valueProblem(key, value))

  private def prefixProblem(key: String, prefix: String): Option[String] =
    if prefix.isEmpty || prefix.length > MaxPrefixLength
      || !PrefixPattern.matches(prefix)
    then
      Some(
        s"label key '$key' has an invalid prefix: a DNS subdomain of at most $MaxPrefixLength characters is required"
      )
    else None

  private def nameProblem(key: String, name: String): Option[String] =
    if name.isEmpty || name.length > MaxNameLength || !NamePattern.matches(name) then
      Some(
        s"label key '$key' has an invalid name: at most $MaxNameLength alphanumeric characters, '-', '_' or '.', starting and ending with an alphanumeric"
      )
    else None

  private def valueProblem(key: String, value: String): Option[String] =
    if value.nonEmpty && (value.length > MaxValueLength || !NamePattern.matches(value)) then
      Some(
        s"label '$key' has an invalid value '$value': empty, or at most $MaxValueLength alphanumeric characters, '-', '_' or '.', starting and ending with an alphanumeric"
      )
    else None

  /** Left with the count message, else the first problem (sorted by key). */
  def validate(labels: Map[String, String]): Either[String, Map[String, String]] =
    if labels.size > MaxLabels then
      Left(s"at most $MaxLabels labels per server, got ${labels.size}")
    else
      labels.toSeq.sortBy(_._1).iterator.flatMap((k, v) => problem(k, v)).nextOption() match
        case Some(msg) => Left(msg)
        case None      => Right(labels)

  /** "a=b, c=d" sorted by key, "no labels" when empty. */
  def render(labels: Map[String, String]): String =
    if labels.isEmpty then "no labels"
    else labels.toSeq.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")
