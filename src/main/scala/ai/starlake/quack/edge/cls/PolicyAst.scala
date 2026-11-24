package ai.starlake.quack.edge.cls

import ai.starlake.acl.parser.AstChildren

/** Shared AST traversal for stored-expression validation and historical-query guards. */
private[cls] object PolicyAst:
  /** Every AST node of `root`, at any depth, children JSqlParser keeps in collections included. */
  private[cls] def nodes(root: AnyRef): List[AnyRef] =
    val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    )
    val out                    = scala.collection.mutable.ListBuffer.empty[AnyRef]
    def walk(value: Any): Unit =
      value match
        case null                         => ()
        case it: java.lang.Iterable[?]    => it.forEach(walk(_))
        case arr: Array[?]                => arr.foreach(walk)
        case e: java.util.Map.Entry[?, ?] => walk(e.getKey); walk(e.getValue)
        case m: java.util.Map[?, ?]       => m.entrySet.forEach(walk(_))
        case n: AnyRef if AstChildren.isAstNode(n) && seen.add(n) =>
          out += n
          AstChildren.accessors(n.getClass).foreach(m => walk(AstChildren.read(m, n)))
        case _ => ()
    walk(root)
    out.toList
