package ca.uwaterloo.flix.lang.ast

import ca.uwaterloo.flix.lang.Compiler

// TODO: if there is going to be an optimized IR then all these helper methods such be moved to that IR.
// TODO: Look at every occurrence  of RName.
// TODO: e -> exp

/**
 * A common super-type for typed AST nodes.
 */
sealed trait TypedAst

object TypedAst {

  /**
   * A typed AST node representing the root of the entire AST.
   *
   * @param defns a map from resolved names to definitions.
   * @param facts a list of facts.
   * @param rules a list of rules.
   */
  case class Root(defns: Map[ResolvedAst.RName, TypedAst.Definition],
                  facts: List[TypedAst.Constraint.Fact],
                  rules: List[TypedAst.Constraint.Rule]) extends TypedAst {
  }

  /**
   * A common super-type for typed definitions.
   */
  sealed trait Definition

  object Definition {

    // TODO: Which of these to inline?
    //
    //    case class TypeAlias(ident: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst.Definition
    //
    //    case class Value(ident: ParsedAst.Ident, tpe: TypedAst.Type, e: TypedAst.Expression) extends TypedAst.Definition
    //
    //    case class Function(ident: ParsedAst.Ident, formals: List[(ParsedAst.Ident, TypedAst.Type)], tpe: TypedAst.Type, body: TypedAst.Expression) extends TypedAst.Definition
    //
    //    case class Enum(ident: ParsedAst.Ident, cases: Map[String, ParsedAst.Type.Tag]) extends TypedAst.Definition
    //
    //    case class Lattice(ident: ParsedAst.Ident, elms: List[ParsedAst.QName], traits: List[ParsedAst.Trait]) extends TypedAst.Definition
    //
    //    case class JoinSemiLattice(ident: ParsedAst.Ident,
    //                               bot: ParsedAst.QName,
    //                               leq: ParsedAst.QName,
    //                               lub: ParsedAst.QName,
    //                               norm: Option[ParsedAst.QName],
    //                               widen: Option[ParsedAst.QName]) extends TypedAst.Definition
    //

    /**
     * A typed AST node representing a relation definition.
     *
     * @param ident the name of the relation.
     * @param attributes the attributes (columns) of the relation.
     */
    case class Relation(ident: ParsedAst.Ident, attributes: List[TypedAst.Attribute]) extends TypedAst.Definition {
      /**
       * Returns the attribute with the given `name`.
       */
      def attribute(name: String): TypedAst.Attribute = attributes find {
        case TypedAst.Attribute(attributeIdent) => attributeIdent.name == name
      } getOrElse {
        throw Compiler.InternalCompilerError(s"Attribute '$name' does not exist.", ident.location)
      }
    }

  }

  /**
   * A common super-type for typed facts and rules.
   */
  sealed trait Constraint extends TypedAst

  object Constraint {

    /**
     * A typed AST node representing a fact declaration.
     *
     * @param head the head predicate.
     */
    case class Fact(head: TypedAst.Predicate.WithApply) extends TypedAst.Constraint

    /**
     * A typed AST node representing a rule declaration.
     *
     * @param head the head predicate.
     * @param body the body predicates.
     */
    case class Rule(head: TypedAst.Predicate.WithApply, body: List[TypedAst.Predicate.NoApply]) extends TypedAst.Constraint

  }

  /**
   * A common super-type for typed literals.
   */
  sealed trait Literal extends TypedAst {
    /**
     * The type of the literal.
     */
    def tpe: TypedAst.Type
  }

  object Literal {

    /**
     * A typed AST node representing the unit literal.
     */
    case object Unit extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Unit
    }

    /**
     * A typed AST node representing a boolean literal.
     */
    case class Bool(literal: scala.Boolean) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Bool
    }

    /**
     * A typed AST node representing an integer literal.
     */
    case class Int(literal: scala.Int) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Int
    }

    /**
     * A typed AST node representing a string literal.
     */
    case class Str(literal: java.lang.String) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Str
    }

    /**
     * A typed AST node representing a tagged literal.
     *
     * @param ident the tag name.
     * @param literal the nested literal.
     * @param tpe the type of the tag.
     */
    case class Tag(ident: ParsedAst.Ident, literal: TypedAst.Literal, tpe: TypedAst.Type.Enum) extends TypedAst.Literal

    /**
     * A typed AST node representing a tuple literal.
     * @param elms the elements of the tuple.
     * @param tpe the typed of the tuple.
     */
    case class Tuple(elms: List[TypedAst.Literal], tpe: TypedAst.Type.Tuple) extends TypedAst.Literal

  }

  sealed trait Expression extends TypedAst {
    /**
     * The type of the expression.
     */
    def tpe: Type
  }

  object Expression {

    /**
     * A typed AST node representing a literal expression.
     *
     * @param literal the literal.
     * @param tpe the type of the literal.
     */
    case class Lit(literal: TypedAst.Literal, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a local variable expression (i.e. a parameter or let-bound variable).
     *
     * @param name the name of the variable.
     * @param tpe the type of the variable.
     */
    case class Var(name: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a reference to a definition (i.e. a value or function).
     *
     * @param name the name of the definition.
     * @param tpe the type of the definition.
     */
    case class Ref(name: ResolvedAst.RName, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a lambda abstraction.
     *
     * @param formals the formal arguments.
     * @param returnTpe the declared return type.
     * @param body the body expression of the lambda.
     * @param tpe the type of the entire function.
     */
    case class Lambda(formals: List[TypedAst.FormalArg], returnTpe: TypedAst.Type, body: TypedAst.Expression, tpe: TypedAst.Type.Function) extends TypedAst.Expression

    /**
     * A typed AST node representing a function call.
     *
     * @param exp the lambda/function expression.
     * @param args the function arguments.
     * @param tpe the return type of the function.
     */
    case class Apply(exp: TypedAst.Expression, args: List[TypedAst.Expression], tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a unary expression.
     *
     * @param op the unary operator.
     * @param exp the expression.
     * @param tpe the type
     */
    case class Unary(op: UnaryOperator, exp: TypedAst.Expression, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a binary expression.
     *
     * @param op the binary operator.
     * @param e1 the lhs expression.
     * @param e2 the rhs expression.
     * @param tpe the type of the expression.
     */
    case class Binary(op: BinaryOperator, e1: TypedAst.Expression, e2: TypedAst.Expression, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing an if-then-else expression.
     *
     * @param e1 the conditional expression.
     * @param e2 the consequent expression.
     * @param e3 the alternative expression.
     * @param tpe the type of the consequent and alternative expressions.
     */
    case class IfThenElse(e1: TypedAst.Expression, e2: TypedAst.Expression, e3: TypedAst.Expression, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a let expression.
     *
     * @param ident the name of the bound variable.
     * @param value the value of the bound variable.
     * @param body the body expression in which the bound variable is visible.
     * @param tpe the type of the expression (which is equivalent to the type of the body expression).
     */
    case class Let(ident: ParsedAst.Ident, value: TypedAst.Expression, body: TypedAst.Expression, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a match expression.
     *
     * @param e the match expression.
     * @param rules the match rules.
     * @param tpe the type of the match expression (which is equivalent to the type of each rule).
     */
    case class Match(e: TypedAst.Expression, rules: List[(TypedAst.Pattern, TypedAst.Expression)], tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing a tagged expression.
     *
     * @param name the namespace of the enum.
     * @param ident the name of the tag.
     * @param e the expression.
     * @param tpe the type of the expression.
     */
    case class Tag(name: ResolvedAst.RName, ident: ParsedAst.Ident, e: TypedAst.Expression, tpe: TypedAst.Type.Enum) extends TypedAst.Expression

    /**
     * A typed AST node representing a tuple expression.
     *
     * @param elms the elements of the tuple.
     * @param tpe the type of the tuple.
     */
    case class Tuple(elms: List[TypedAst.Expression], tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing an ascribed expression.
     *
     * @param e the expression.
     * @param tpe the ascribed type.
     */
    case class Ascribe(e: TypedAst.Expression, tpe: TypedAst.Type) extends TypedAst.Expression

    /**
     * A typed AST node representing an error expression.
     *
     * @param location the location of the error expression.
     * @param tpe the type of the error expression.
     */
    case class Error(location: SourceLocation, tpe: TypedAst.Type) extends TypedAst.Expression

  }

  /**
   * A common-super type for typed patterns.
   */
  sealed trait Pattern extends TypedAst {
    /**
     * The type of the pattern.
     */
    def tpe: TypedAst.Type
  }

  object Pattern {

    /**
     * A typed AST node representing a wildcard pattern.
     *
     * @param tpe the type of the wildcard variable.
     */
    case class Wildcard(tpe: TypedAst.Type) extends TypedAst.Pattern

    /**
     * A typed AST node representing a variable pattern.
     *
     * @param ident the name of the variable.
     * @param tpe the type of the variable.
     */
    case class Var(ident: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst.Pattern

    /**
     * A typed AST node representing a literal pattern.
     *
     * @param lit the literal.
     * @param tpe the type of the literal.
     */
    case class Lit(lit: ResolvedAst.Literal, tpe: TypedAst.Type) extends TypedAst.Pattern

    /**
     * A typed AST node representing a tagged pattern.
     *
     * @param name the namespace of the tag.
     * @param ident the tag name.
     * @param pat the nested pattern.
     * @param tpe the type of the tag.
     */
    case class Tag(name: ResolvedAst.RName, ident: ParsedAst.Ident, pat: TypedAst.Pattern, tpe: TypedAst.Type.Tag) extends TypedAst.Pattern

    /**
     * A typed AST node representing a tuple pattern.
     *
     * @param elms the elements of the tuple.
     * @param tpe the type of the tuple.
     */
    case class Tuple(elms: List[TypedAst.Pattern], tpe: TypedAst.Type.Tuple) extends TypedAst.Pattern

  }

  /**
   * A common super-type for typed predicates.
   */
  sealed trait Predicate extends TypedAst

  object Predicate {

    case class NoApply(name: ResolvedAst.RName, terms: List[TypedAst.Term], tpe: TypedAst.Type) extends TypedAst.Predicate

    case class WithApply(name: ResolvedAst.RName, terms: List[TypedAst.Term], tpe: TypedAst.Type) extends TypedAst.Predicate

  }

  sealed trait Term

  object Term {

  }


  /**
   * A common super-type for types.
   */
  sealed trait Type extends TypedAst

  object Type {

    /**
     * An AST node representing the Unit type.
     */
    case object Unit extends TypedAst.Type

    /**
     * An AST node representing the Boolean type.
     */
    case object Bool extends TypedAst.Type

    /**
     * An AST node representing the Integer type.
     */
    case object Int extends TypedAst.Type

    /**
     * An AST node representing the String type.
     */
    case object Str extends TypedAst.Type

    /**
     * An AST node representing the type of a tag.
     *
     * @param name the namespace of the tag.
     * @param ident the name of the tag.
     * @param tpe the type of the nested value.
     */
    case class Tag(name: ResolvedAst.RName, ident: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst.Type

    /**
     * An AST node representing an enum type (a set of tags).
     *
     * @param variants a map from tag names to tag types.
     */
    case class Enum(variants: Map[String, TypedAst.Type.Tag]) extends TypedAst.Type

    /**
     * An AST node representing a tuple type.
     *
     * @param elms the types of the elements.
     */
    case class Tuple(elms: List[TypedAst.Type]) extends TypedAst.Type


    // TODO: ...
    case class Function() extends TypedAst.Type

  }

  /**
   * A typed AST node representing an attribute in a relation.
   *
   * @param ident the name of the attribute.
   * @param tpe  the type of the attribute.
   */
  case class Attribute(ident: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst

  /**
   * A typed AST node representing a formal argument in a function.
   *
   * @param ident the name of the argument.
   * @param tpe the type of the argument.
   */
  case class FormalArg(ident: ParsedAst.Ident, tpe: TypedAst.Type) extends TypedAst

}
