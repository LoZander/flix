package ca.uwaterloo.flix.language.ast

/**
 * A common super-type for typed AST nodes.
 */
sealed trait TypedAst

object TypedAst {

  /**
   * A typed AST node representing the root of the entire AST.
   *
   * @param constants a map from names to constant definitions.
   * @param lattices a map from types to lattice definitions.
   * @param relations a map from names to relation definitions.
   * @param facts a list of facts.
   * @param rules a list of rules.
   */
  case class Root(constants: Map[Name.Resolved, TypedAst.Definition.Constant],
                  lattices: Map[TypedAst.Type, TypedAst.Definition.Lattice],
                  relations: Map[Name.Resolved, TypedAst.Definition.Relation],
                  facts: List[TypedAst.Constraint.Fact],
                  rules: List[TypedAst.Constraint.Rule]) extends TypedAst

  /**
   * A common super-type for typed definitions.
   */
  sealed trait Definition

  object Definition {

    /**
     * A typed AST node representing a constant definition.
     *
     * @param name the name of the constant.
     * @param exp the constant expression.
     * @param tpe the type of the constant.
     * @param loc the source location.
     */
    case class Constant(name: Name.Resolved, exp: TypedAst.Expression, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Definition

    /**
     * A typed AST node representing a lattice definition.
     *
     * @param tpe the type of the lattice elements.
     * @param bot the bottom element.
     * @param leq the partial order.
     * @param lub the least-upper-bound.
     * @param loc the source location.
     */
    case class Lattice(tpe: TypedAst.Type, bot: TypedAst.Expression, leq: TypedAst.Expression, lub: TypedAst.Expression, loc: SourceLocation) extends TypedAst.Definition

    /**
     * A typed AST node representing a relation definition.
     *
     * @param name the name of the relation.
     * @param attributes the attributes (columns) of the relation.
     * @param loc the source location.
     */
    case class Relation(name: Name.Resolved, attributes: List[TypedAst.Attribute], loc: SourceLocation) extends TypedAst.Definition

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
    case class Fact(head: TypedAst.Predicate.Head) extends TypedAst.Constraint

    /**
     * A typed AST node representing a rule declaration.
     *
     * @param head the head predicate.
     * @param body the body predicates.
     */
    // TODO: Equip with bindings: Map[String, TypedAst.Type]
    case class Rule(head: TypedAst.Predicate.Head, body: List[TypedAst.Predicate.Body]) extends TypedAst.Constraint

  }

  /**
   * A common super-type for typed literals.
   */
  sealed trait Literal extends TypedAst {
    /**
     * The type of `this` literal.
     */
    def tpe: TypedAst.Type

    /**
     * The source location of `this` literal.
     */
    def loc: SourceLocation
  }

  object Literal {

    /**
     * A typed AST node representing the unit literal.
     *
     * @param loc the source location.
     */
    case class Unit(loc: SourceLocation) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Unit
    }

    /**
     * A typed AST node representing a boolean literal.
     *
     * @param lit the boolean literal.
     * @param loc the source location.
     */
    case class Bool(lit: scala.Boolean, loc: SourceLocation) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Bool
    }

    /**
     * A typed AST node representing an integer literal.
     *
     * @param lit the integer literal.
     * @param loc the source location.
     */
    case class Int(lit: scala.Int, loc: SourceLocation) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Int
    }

    /**
     * A typed AST node representing a string literal.
     *
     * @param lit the string literal.
     * @param loc the source location.
     */
    case class Str(lit: java.lang.String, loc: SourceLocation) extends TypedAst.Literal {
      final val tpe = TypedAst.Type.Str
    }

    /**
     * A typed AST node representing a tagged literal.
     *
     * @param enum the enum name.
     * @param tag the tag name.
     * @param lit the nested literal.
     * @param tpe the type of the tag.
     * @param loc the source location.
     */
    case class Tag(enum: Name.Resolved, tag: Name.Ident, lit: TypedAst.Literal, tpe: TypedAst.Type.Enum, loc: SourceLocation) extends TypedAst.Literal

    /**
     * A typed AST node representing a tuple literal.
     *
     * @param elms the elements of the tuple.
     * @param tpe the typed of the tuple.
     * @param loc the source location.
     */
    case class Tuple(elms: List[TypedAst.Literal], tpe: TypedAst.Type.Tuple, loc: SourceLocation) extends TypedAst.Literal

  }

  sealed trait Expression extends TypedAst {
    /**
     * The type of `this` expression.
     */
    def tpe: Type

    /**
     * The source location of `this` expression.
     */
    def loc: SourceLocation
  }

  object Expression {

    /**
     * A typed AST node representing a literal expression.
     *
     * @param literal the literal.
     * @param tpe the type of the literal.
     * @param loc the source location.
     */
    case class Lit(literal: TypedAst.Literal, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a local variable expression (i.e. a parameter or let-bound variable).
     *
     * @param ident the name of the variable.
     * @param tpe the type of the variable.
     */
    case class Var(ident: Name.Ident, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a reference to a definition (i.e. a value or function).
     *
     * @param name the name of the definition.
     * @param tpe the type of the definition.
     * @param loc the source location.
     */
    case class Ref(name: Name.Resolved, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a lambda abstraction.
     *
     * @param args the formal arguments.
     * @param body the body expression of the lambda.
     * @param tpe the type of the entire function.
     * @param loc the source location.
     */
    case class Lambda(args: List[TypedAst.FormalArg], body: TypedAst.Expression, tpe: TypedAst.Type.Lambda, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a function call.
     *
     * @param exp the lambda/function expression.
     * @param args the function arguments.
     * @param tpe the return type of the function.
     * @param loc the source location.
     */
    case class Apply(exp: TypedAst.Expression, args: List[TypedAst.Expression], tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a unary expression.
     *
     * @param op the unary operator.
     * @param exp the expression.
     * @param tpe the type
     * @param loc the source location.
     */
    case class Unary(op: UnaryOperator, exp: TypedAst.Expression, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a binary expression.
     *
     * @param op the binary operator.
     * @param exp1 the lhs expression.
     * @param exp2 the rhs expression.
     * @param tpe the type of the expression.
     * @param loc the source location.
     */
    case class Binary(op: BinaryOperator, exp1: TypedAst.Expression, exp2: TypedAst.Expression, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing an if-then-else expression.
     *
     * @param exp1 the conditional expression.
     * @param exp2 the consequent expression.
     * @param exp3 the alternative expression.
     * @param tpe the type of the consequent and alternative expressions.
     * @param loc the source location.
     */
    case class IfThenElse(exp1: TypedAst.Expression, exp2: TypedAst.Expression, exp3: TypedAst.Expression, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a let expression.
     *
     * @param ident the name of the bound variable.
     * @param exp1 the value of the bound variable.
     * @param exp2 the body expression in which the bound variable is visible.
     * @param tpe the type of the expression (which is equivalent to the type of the body expression).
     * @param loc the source location.
     */
    case class Let(ident: Name.Ident, exp1: TypedAst.Expression, exp2: TypedAst.Expression, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a match expression.
     *
     * @param exp the match expression.
     * @param rules the match rules.
     * @param tpe the type of the match expression (which is equivalent to the type of each rule).
     * @param loc the source location.
     */
    case class Match(exp: TypedAst.Expression, rules: List[(TypedAst.Pattern, TypedAst.Expression)], tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a tagged expression.
     *
     * @param name the name of the enum.
     * @param ident the name of the tag.
     * @param exp the expression.
     * @param tpe the type of the expression.
     * @param loc the source location.
     */
    case class Tag(name: Name.Resolved, ident: Name.Ident, exp: TypedAst.Expression, tpe: TypedAst.Type.Enum, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing a tuple expression.
     *
     * @param elms the elements of the tuple.
     * @param tpe the type of the tuple.
     * @param loc the source location.
     */
    case class Tuple(elms: List[TypedAst.Expression], tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

    /**
     * A typed AST node representing an error expression.
     *
     * @param tpe the type of the error expression.
     * @param loc the source location.
     */
    case class Error(tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Expression

  }

  /**
   * A common-super type for typed patterns.
   */
  sealed trait Pattern extends TypedAst {
    /**
     * The type of `this` pattern.
     */
    def tpe: TypedAst.Type

    /**
     * The source location of `this` pattern.
     */
    def loc: SourceLocation

    /**
     * Returns the free variables (along with their types) in `this` pattern.
     */
    def freeVars: Map[String, TypedAst.Type] = {
      def visit(pat: TypedAst.Pattern, m: Map[String, TypedAst.Type]): Map[String, TypedAst.Type] =
        pat match {
          case TypedAst.Pattern.Wildcard(_, _) => m
          case TypedAst.Pattern.Var(ident, tpe, _) => m + (ident.name -> tpe)
          case TypedAst.Pattern.Lit(_, _, _) => m
          case TypedAst.Pattern.Tag(_, _, pat2, _, _) => visit(pat2, m)
          case TypedAst.Pattern.Tuple(elms, _, _) => elms.foldLeft(m) {
            case (macc, elm) => visit(elm, macc)
          }
        }

      visit(this, Map.empty)
    }
  }

  object Pattern {

    /**
     * A typed AST node representing a wildcard pattern.
     *
     * @param tpe the type of the wildcard variable.
     * @param loc the source location.
     */
    case class Wildcard(tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Pattern

    /**
     * A typed AST node representing a variable pattern.
     *
     * @param ident the name of the variable.
     * @param tpe the type of the variable.
     * @param loc the source location.
     */
    case class Var(ident: Name.Ident, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Pattern

    /**
     * A typed AST node representing a literal pattern.
     *
     * @param lit the literal.
     * @param tpe the type of the literal.
     * @param loc the source location.
     */
    case class Lit(lit: TypedAst.Literal, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Pattern

    /**
     * A typed AST node representing a tagged pattern.
     *
     * @param name the namespace of the tag.
     * @param ident the tag name.
     * @param pat the nested pattern.
     * @param tpe the type of the tag.
     * @param loc the source location.
     */
    case class Tag(name: Name.Resolved, ident: Name.Ident, pat: TypedAst.Pattern, tpe: TypedAst.Type.Tag, loc: SourceLocation) extends TypedAst.Pattern

    /**
     * A typed AST node representing a tuple pattern.
     *
     * @param elms the elements of the tuple.
     * @param tpe the type of the tuple.
     * @param loc the source location.
     */
    case class Tuple(elms: List[TypedAst.Pattern], tpe: TypedAst.Type.Tuple, loc: SourceLocation) extends TypedAst.Pattern

  }

  /**
   * A common super-type for typed predicates.
   */
  sealed trait Predicate extends TypedAst

  object Predicate {

    // TODO Add maps from String to Type for vars?

    /**
     * A typed predicate that is allowed to occur in the head of a rule.
     *
     * @param name the name of the predicate.
     * @param terms the terms of the predicate.
     * @param tpe the type of the predicate.
     * @param loc the source location.
     */
    case class Head(name: Name.Resolved, terms: List[TypedAst.Term.Head], tpe: TypedAst.Type.Predicate, loc: SourceLocation) extends TypedAst.Predicate

    /**
     * A typed predicate that is allowed to occur in the body of a rule.
     *
     * @param name the name of the predicate.
     * @param terms the terms of the predicate.
     * @param tpe the type of the predicate.
     * @param loc the source location.
     */
    case class Body(name: Name.Resolved, terms: List[TypedAst.Term.Body], tpe: TypedAst.Type.Predicate, loc: SourceLocation) extends TypedAst.Predicate

  }

  object Term {

    /**
     * A common super-type for terms that are allowed appear in a head predicate.
     */
    sealed trait Head extends TypedAst {
      /**
       * The type of `this` term.
       */
      def tpe: TypedAst.Type

      /**
       * The source location of `this` term.
       */
      def loc: SourceLocation
    }

    object Head {

      /**
       * A typed AST node representing a variable term.
       *
       * @param ident the variable name.
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Var(ident: Name.Ident, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Head

      /**
       * A typed AST node representing a literal term.
       *
       * @param literal the literal.
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Lit(literal: TypedAst.Literal, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Head

      /**
       * A typed AST node representing a function call term.
       *
       * @param name the name of the called function.
       * @param args the arguments to the function.
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Apply(name: Name.Resolved, args: List[TypedAst.Term.Head], tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Head

    }

    /**
     * A common super-type for terms that are allowed to appear in a body predicate.
     */
    sealed trait Body extends TypedAst {
      /**
       * The type of `this` term.
       */
      def tpe: TypedAst.Type

      /**
       * The source location of `this` term.
       */
      def loc: SourceLocation
    }

    object Body {

      /**
       * A typed AST node representing a wildcard term.
       *
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Wildcard(tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Body

      /**
       * A typed AST node representing a variable term.
       *
       * @param ident the variable name.
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Var(ident: Name.Ident, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Body

      /**
       * A typed AST node representing a literal term.
       *
       * @param literal the literal.
       * @param tpe the type of the term.
       * @param loc the source location.
       */
      case class Lit(literal: TypedAst.Literal, tpe: TypedAst.Type, loc: SourceLocation) extends TypedAst.Term.Body

    }

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
    case class Tag(name: Name.Resolved, ident: Name.Ident, tpe: TypedAst.Type) extends TypedAst.Type

    /**
     * An AST node representing an enum type (a set of tags).
     *
     * @param cases a map from tag names to tag types.
     */
    case class Enum(cases: Map[String, TypedAst.Type.Tag]) extends TypedAst.Type

    /**
     * An AST node representing a tuple type.
     *
     * @param elms the types of the elements.
     */
    case class Tuple(elms: List[TypedAst.Type]) extends TypedAst.Type

    /**
     * An AST node representing a function type.
     *
     * @param args the type of the arguments.
     * @param retTpe the type of the return type.
     */
    case class Lambda(args: List[TypedAst.Type], retTpe: TypedAst.Type) extends TypedAst.Type

    /**
     * An AST node representing a predicate type.
     *
     * @param terms the terms of the predicate.
     */
    case class Predicate(terms: List[TypedAst.Type]) extends TypedAst.Type

  }

  /**
   * A typed AST node representing an attribute in a relation.
   *
   * @param ident the name of the attribute.
   * @param tpe  the type of the attribute.
   */
  case class Attribute(ident: Name.Ident, tpe: TypedAst.Type, interp: Interpretation) extends TypedAst

  /**
   * A common super-type for attribute interpretations.
   */
  sealed trait Interpretation

  object Interpretation {

    /**
     * An AST node representing the standard set-based interpretation of an attribute in a relation.
     */
    case object Set extends TypedAst.Interpretation

    /**
     * An AST node representing a lattice-based interpretation of an attribute in a relation.
     */
    case object Lattice extends TypedAst.Interpretation

  }

  /**
   * A typed AST node representing a formal argument of a function.
   *
   * @param ident the name of the argument.
   * @param tpe the type of the argument.
   */
  case class FormalArg(ident: Name.Ident, tpe: TypedAst.Type) extends TypedAst

}
