/*
 * Copyright 2015-2016 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.language.ast.NamedAst.Program
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.language.errors.TypeError
import ca.uwaterloo.flix.language.phase.Disambiguation.Target
import ca.uwaterloo.flix.language.phase.Unification._
import ca.uwaterloo.flix.util.Result.{Err, Ok}
import ca.uwaterloo.flix.util.Validation.{ToFailure, ToSuccess}
import ca.uwaterloo.flix.util.{InternalCompilerException, Result, Validation}

object Typer {

  /**
    * Type checks the given program.
    */
  def typer(program: NamedAst.Program)(implicit genSym: GenSym): Validation[TypedAst.Root, TypeError] = {
    val startTime = System.nanoTime()

    /*
     * Definitions.
     */
    val constants = program.definitions.foldLeft(Map.empty[Symbol.Resolved, TypedAst.Definition.Constant]) {
      case (macc, (ns, defns)) => macc ++ defns.foldLeft(Map.empty[Symbol.Resolved, TypedAst.Definition.Constant]) {
        case (macc2, (name, defn0)) =>
          Declarations.infer(defn0, ns, program) match {
            case Success(defn, _) => macc2 + (toResolvedTemporaryHelperMethod(ns, name) -> defn)
            case Failure(e) => return e.toFailure[TypedAst.Root, TypeError]
          }
      }
    }

    /*
     * Lattices.
     */
    val lattices = program.lattices.foldLeft(Map.empty[Type, TypedAst.Definition.BoundedLattice]) {
      case (macc, (tpe, decl)) =>
        val NamedAst.Declaration.BoundedLattice(tpe, e1, e2, e3, e4, e5, ns, loc) = decl

        val Success(resolvedType, subst) = {
          val declaredType = Disambiguation.resolve(tpe, ns, program) match {
            case Ok(tpe) => tpe
            case Err(e) => return e.toFailure
          }

          for (
            botType <- Expressions.infer(e1, ns, program);
            topType <- Expressions.infer(e2, ns, program);
            leqType <- Expressions.infer(e3, ns, program);
            lubType <- Expressions.infer(e4, ns, program);
            glbType <- Expressions.infer(e5, ns, program);
            _______ <- unifyM(botType, declaredType, loc);
            _______ <- unifyM(topType, declaredType, loc)
          // TODO Add constraints for leq, lub, glb, etc.
          )
            yield declaredType
        }

        val bot = Expressions.reassemble(e1, ns, program, subst)
        val top = Expressions.reassemble(e2, ns, program, subst)
        val leq = Expressions.reassemble(e3, ns, program, subst)
        val lub = Expressions.reassemble(e4, ns, program, subst)
        val glb = Expressions.reassemble(e5, ns, program, subst)

        val lattice = TypedAst.Definition.BoundedLattice(resolvedType, bot, top, leq, lub, glb, loc)
        macc + (resolvedType -> lattice)
    }

    /*
     * Tables.
     */
    val tables = inferTables(program) match {
      case Success(m, _) => m
      case Failure(e) => return e.toFailure
    }

    /*
     * Indexes.
     */
    val indexes = program.indexes.foldLeft(Map.empty[Symbol.TableSym, TypedAst.Definition.Index]) {
      case (macc, (sym, NamedAst.Declaration.Index(ident, idxs, loc))) => macc + (sym -> TypedAst.Definition.Index(sym, idxs, loc))
    }

    /*
     * Facts.
     */
    val facts = program.facts.flatMap {
      case (ns, fs) => fs map {
        case NamedAst.Declaration.Fact(head0, loc) =>
          Predicates.infer(head0, ns, program) match {
            case Success(_, subst) =>
              val head = Predicates.reassemble(head0, ns, program, subst)
              TypedAst.Constraint.Fact(head)
            case Failure(e) => return e.toFailure
          }
      }

    }

    /*
     * Rule.
     */
    val rules = program.rules.flatMap {
      case (ns, rs) => rs map {
        case NamedAst.Declaration.Rule(head0, body0, loc) =>
          Predicates.infer(head0, ns, program) match {
            case Success(_, subst) =>
              val head = Predicates.reassemble(head0, ns, program, subst)
              val body = body0.map {
                case b => Predicates.infer(b, ns, program) match {
                  case Success(_, subst1) => Predicates.reassemble(b, ns, program, subst1)
                  case Failure(e) => return e.toFailure
                }
              }
              TypedAst.Constraint.Rule(head, body)

            case Failure(e) => return e.toFailure
          }

      }
    }

    val hooks = program.hooks.flatMap {
      case (ns, hs) => hs.map {
        case (name, hook) => Symbol.Resolved.mk(ns.parts ::: name :: Nil) -> hook
      }
    }

    val currentTime = System.nanoTime()
    val time = program.time.copy(typer = currentTime - startTime)

    TypedAst.Root(constants, lattices, tables, indexes, facts.toList, rules.toList, hooks, Nil, time).toSuccess
  }


  // TODO: Document and move somewhere.
  def inferTables(program: Program): InferMonad[Map[Symbol.TableSym, TypedAst.Table]] = {
    // resolve types for attributes in relations and lattices.
    val tables = program.tables.toList.flatMap {
      case (ns, decls) => decls.map {
        // relation, infer types for the attributes.
        case (_, NamedAst.Table.Relation(sym, attr, loc)) =>
          seqM(attr.map(a => infer(a, ns, program))) map {
            case as => sym -> (TypedAst.Table.Relation(sym, as, loc): TypedAst.Table)
          }
        // lattice, infer types for the keys and value.
        case (_, NamedAst.Table.Lattice(sym, keys, value, loc)) =>
          seqM(keys.map(a => infer(a, ns, program))) flatMap {
            case ks => infer(value, ns, program) map {
              case v => sym -> (TypedAst.Table.Lattice(sym, ks, v, loc): TypedAst.Table)
            }
          }
      }
    }

    seqM(tables).map(_.toMap)
  }


  def toResolvedTemporaryHelperMethod(ns: Name.NName, name: String): Symbol.Resolved =
    if (ns.isRoot) Symbol.Resolved.mk(name) else Symbol.Resolved.mk(ns.parts ::: name :: Nil)


  /**
    * Translates the given named attribute into a typed attributes.
    *
    * Substitutes the declared type for a resolved type.
    */
  def infer(attr: NamedAst.Attribute, ns: Name.NName, program: Program): InferMonad[TypedAst.Attribute] = attr match {
    case NamedAst.Attribute(ident, tpe, loc) => Disambiguation.resolve(tpe, ns, program) match {
      case Ok(rtpe) => liftM(TypedAst.Attribute(ident, rtpe))
      case Err(e) => failM(e)
    }
  }

  object Declarations {

    /**
      * Infers the type of the given definition `defn0` in the given namespace `ns0`.
      */
    def infer(defn0: NamedAst.Declaration.Definition, ns0: Name.NName, program: NamedAst.Program)(implicit genSym: GenSym): InferMonad[TypedAst.Definition.Constant] = {
      // Resolve the declared type.
      val declaredType = Disambiguation.resolve(defn0.tpe, ns0, program) match {
        case Ok(tpe) => tpe
        case Err(e) => return failM(e)
      }

      val subst = getSubst(defn0.params, ns0, program).get

      // TODO: Some duplication
      val argumentTypes = Disambiguation.resolve(defn0.params.map(_.tpe), ns0, program) match {
        case Ok(tpes) => tpes
        case Err(e) => return failM(e)
      }

      val result = for (
        _ <- liftM(null, subst);
        resultType <- Expressions.infer(defn0.exp, ns0, program);
        unifiedType <- unifyM(declaredType, Type.mkArrow(argumentTypes, resultType), defn0.loc)
      ) yield unifiedType

      // TODO: See if this can be rewritten nicer
      result match {
        case Success(resultType, subst0) =>
          val exp = Expressions.reassemble(defn0.exp, ns0, program, subst0)

          // Translate the named formals into typed formals.
          val formals = defn0.params.map {
            case NamedAst.FormalParam(sym, tpe, loc) =>
              val t = Disambiguation.resolve(tpe, ns0, program).get
              TypedAst.FormalArg(sym.toIdent, subst0(t))
          }

          liftM(TypedAst.Definition.Constant(defn0.ann, defn0.sym.toResolvedTemporaryHelperMethod, formals, exp, resultType, defn0.loc))
        case Failure(e) => Failure(e)
      }
    }

  }

  object Expressions {

    /**
      * Infers the type of the given expression `exp0` in the namespace `ns0` and `program`.
      */
    def infer(exp0: NamedAst.Expression, ns0: Name.NName, program: NamedAst.Program)(implicit genSym: GenSym): InferMonad[Type] = {

      /**
        * Infers the type of the given expression `exp0` inside the inference monad.
        */
      def visitExp(e0: NamedAst.Expression): InferMonad[Type] = e0 match {

        /*
         * Wildcard expression.
         */
        case NamedAst.Expression.Wild(tpe, loc) => liftM(tpe)

        /*
         * Variable expression.
         */
        case NamedAst.Expression.Var(sym, loc) => liftM(sym.tvar)

        /*
         * Reference expression.
         */
        case NamedAst.Expression.Ref(ref, tvar, loc) =>
          Disambiguation.lookupRef(ref, ns0, program) match {
            case Ok(Target.Defn(ns, defn)) =>
              Disambiguation.resolve(defn.tpe, ns, program) match {
                case Ok(declaredType) => unifyM(tvar, declaredType, loc)
                case Err(e) => failM(e)
              }
            case Ok(Target.Hook(hook)) => liftM(hook.tpe)
            case Err(e) => failM(e)
          }

        /*
         * Literal expression.
         */
        case NamedAst.Expression.Unit(loc) => liftM(Type.Unit)
        case NamedAst.Expression.True(loc) => liftM(Type.Bool)
        case NamedAst.Expression.False(loc) => liftM(Type.Bool)
        case NamedAst.Expression.Char(lit, loc) => liftM(Type.Char)
        case NamedAst.Expression.Float32(lit, loc) => liftM(Type.Float32)
        case NamedAst.Expression.Float64(lit, loc) => liftM(Type.Float64)
        case NamedAst.Expression.Int8(lit, loc) => liftM(Type.Int8)
        case NamedAst.Expression.Int16(lit, loc) => liftM(Type.Int16)
        case NamedAst.Expression.Int32(lit, loc) => liftM(Type.Int32)
        case NamedAst.Expression.Int64(lit, loc) => liftM(Type.Int64)
        case NamedAst.Expression.BigInt(lit, loc) => liftM(Type.BigInt)
        case NamedAst.Expression.Str(lit, loc) => liftM(Type.Str)

        /*
         * Lambda expression.
         */
        case NamedAst.Expression.Lambda(args, body, tvar, loc) =>
          val argTypes = args.map(_.tvar)
          for (
            bodyType <- visitExp(body);
            resultType <- unifyM(tvar, Type.mkArrow(argTypes, bodyType), loc)
          ) yield resultType

        /*
         * Apply expression.
         */
        case NamedAst.Expression.Apply(lambda, actuals, tvar, loc) =>
          for (
            lambdaType <- visitExp(lambda);
            actualTypes <- seqM(actuals.map(visitExp));
            arrowType <- unifyM(lambdaType, Type.mkArrow(actualTypes, tvar), loc)
          ) yield tvar

        /*
         * Unary expression.
         */
        case NamedAst.Expression.Unary(op, exp1, tvar, loc) => op match {
          case UnaryOperator.LogicalNot =>
            for (
              tpe1 <- visitExp(exp1);
              res <- unifyM(tvar, tpe1, Type.Bool, loc)
            ) yield res

          case UnaryOperator.Plus =>
            for (
              tpe1 <- visitExp(exp1)
            ) yield tpe1

          case UnaryOperator.Minus =>
            for (
              tpe1 <- visitExp(exp1)
            ) yield tpe1

          case UnaryOperator.BitwiseNegate =>
            for (
              tpe1 <- visitExp(exp1)
            ) yield tpe1
        }

        /*
         * Binary expression.
         */
        case NamedAst.Expression.Binary(op, exp1, exp2, tvar, loc) => op match {
          case BinaryOperator.Plus =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Minus =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Times =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Divide =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Modulo =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Exponentiate =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.Equal | BinaryOperator.NotEqual =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              ____ <- unifyM(tpe1, tpe2, loc);
              resultType <- unifyM(tvar, Type.Bool, loc)
            ) yield resultType

          case BinaryOperator.Less | BinaryOperator.LessEqual | BinaryOperator.Greater | BinaryOperator.GreaterEqual =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              ____ <- unifyM(tpe1, tpe2, loc);
              resultType <- unifyM(tvar, Type.Bool, loc)
            ) yield resultType

          case BinaryOperator.LogicalAnd | BinaryOperator.LogicalOr | BinaryOperator.Implication | BinaryOperator.Biconditional =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, Type.Bool, loc)
            ) yield resultType

          case BinaryOperator.BitwiseAnd | BinaryOperator.BitwiseOr | BinaryOperator.BitwiseXor =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              resultType <- unifyM(tvar, tpe1, tpe2, guesstimateType(tpe1, tpe2), loc)
            ) yield resultType

          case BinaryOperator.BitwiseLeftShift | BinaryOperator.BitwiseRightShift =>
            for (
              tpe1 <- visitExp(exp1);
              tpe2 <- visitExp(exp2);
              lhsType <- unifyM(tvar, tpe1, loc);
              rhsType <- unifyM(tpe2, Type.Int32, loc)
            ) yield lhsType
        }

        /*
         * Let expression.
         */
        case NamedAst.Expression.Let(sym, exp1, exp2, tvar, loc) =>
          for (
            tpe1 <- visitExp(exp1);
            tpe2 <- visitExp(exp2);
            resultVar <- unifyM(tvar, tpe2, loc)
          ) yield resultVar

        /*
         * If-then-else expression.
         */
        case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, loc) =>
          for (
            tpe1 <- visitExp(exp1);
            tpe2 <- visitExp(exp2);
            tpe3 <- visitExp(exp3);
            ____ <- unifyM(Type.Bool, tpe1, loc);
            rtpe <- unifyM(tvar, tpe2, tpe3, loc)
          ) yield rtpe

        /*
         * Match expression.
         */
        case NamedAst.Expression.Match(exp1, rules, tvar, loc) =>
          val patterns = rules.map(_._1)
          val bodies = rules.map(_._2)

          for (
            matchType <- visitExp(exp1);
            patternTypes <- visitPats2(patterns, ns0);
            patternType <- unifyM(patternTypes, loc);
            ___________ <- unifyM(matchType, patternType, loc);
            resultType <- visitExps(bodies, tvar)
          ) yield resultType

        /*
           * Switch expression.
           */
        case NamedAst.Expression.Switch(rules, tvar, loc) =>
          val condExps = rules.map(_._1)
          val bodyExps = rules.map(_._2)
          for (
            condType <- visitExps(condExps, Type.Bool);
            bodyType <- visitExps(bodyExps, Type.freshTypeVar());
            _ <- unifyM(condType, Type.Bool, loc);
            resultType <- unifyM(tvar, bodyType, loc)
          ) yield resultType

        /*
         * Tag expression.
         */
        case NamedAst.Expression.Tag(enum, tag, exp, tvar, loc) =>
          Disambiguation.lookupEnumByTag(enum, tag, ns0, program) match {
            case Ok(decl) => Disambiguation.resolve(decl.tpe, ns0, program) match {
              case Ok(enumType) =>
                val cazeType = enumType.asInstanceOf[Type.Enum].cases(tag.name)
                for (
                  innerType <- visitExp(exp);
                  _________ <- unifyM(innerType, cazeType, loc);
                  resultType <- unifyM(tvar, enumType, loc)
                ) yield resultType
              case Err(e) => failM(e)
            }
            case Err(e) => failM(e)
          }

        /*
         * Tuple expression.
         */
        case NamedAst.Expression.Tuple(elms, tvar, loc) =>
          for (
            elementTypes <- seqM(elms.map(visitExp));
            resultType <- unifyM(tvar, Type.mkFTuple(elementTypes), loc)
          ) yield resultType

        /*
         * None expression.
         */
        case NamedAst.Expression.FNone(tvar, loc) =>
          liftM(Type.mkFOpt(tvar))

        /*
         * Some expression.
         */
        case NamedAst.Expression.FSome(exp, tvar, loc) =>
          for (
            innerType <- visitExp(exp);
            resultType <- unifyM(tvar, Type.mkFOpt(innerType), loc)
          ) yield resultType

        /*
         * Nil expression.
         */
        case NamedAst.Expression.FNil(tvar, loc) =>
          liftM(Type.mkFList(tvar))

        /*
         * List expression.
         */
        case NamedAst.Expression.FList(head, tail, tvar, loc) =>
          for (
            headType <- visitExp(head);
            tailType <- visitExp(tail);
            resultType <- unifyM(tvar, Type.mkFList(headType), tailType, loc)
          ) yield resultType

        /*
         * Vector expression.
         */
        case NamedAst.Expression.FVec(elms, tvar, loc) =>
          for (
            elementType <- visitExps(elms, Type.freshTypeVar());
            resultType <- unifyM(Type.mkFVec(elementType), tvar, loc)
          ) yield resultType

        /*
         * Set expression.
         */
        case NamedAst.Expression.FSet(elms, tvar, loc) =>
          for (
            elementType <- visitExps(elms, Type.freshTypeVar());
            resultType <- unifyM(tvar, Type.mkFSet(elementType), loc)
          ) yield resultType

        /*
         * Map expression.
         */
        case NamedAst.Expression.FMap(elms, tvar, loc) =>
          val keys = elms.map(_._1)
          val vals = elms.map(_._2)
          for (
            keyType <- visitExps(keys, Type.freshTypeVar());
            valType <- visitExps(vals, Type.freshTypeVar());
            resultType <- unifyM(tvar, Type.mkFMap(keyType, valType), loc)
          ) yield resultType

        /*
         * GetIndex expression.
         */
        case NamedAst.Expression.GetIndex(exp1, exp2, tvar, loc) =>
          for (
            tpe1 <- visitExp(exp1);
            tpe2 <- visitExp(exp2);
            ____ <- unifyM(tpe1, Type.mkFVec(tvar), loc);
            ____ <- unifyM(tpe2, Type.Int32, loc)
          ) yield tvar

        /*
         * PutIndex expression.
         */
        case NamedAst.Expression.PutIndex(exp1, exp2, exp3, tvar, loc) =>
          val elementType = Type.freshTypeVar()
          for (
            tpe1 <- visitExp(exp1);
            tpe2 <- visitExp(exp2);
            tpe3 <- visitExp(exp3);
            ____ <- unifyM(tpe2, Type.Int32, loc);
            ____ <- unifyM(tpe3, elementType, loc);
            resultType <- unifyM(tvar, tpe1, Type.mkFVec(elementType), loc)
          ) yield resultType

        /*
         * Existential expression.
         */
        case NamedAst.Expression.Existential(params, exp, loc) =>
          // TODO: Check formal parameters.
          for (
            tpe <- visitExp(exp);
            resultType <- unifyM(tpe, Type.Bool, loc)
          ) yield resultType

        /*
         * Universal expression.
         */
        case NamedAst.Expression.Universal(params, exp, loc) =>
          val subst0 = params.foldLeft(Substitution.empty) {
            // TODO: Need to setup connection between sym and the Exp.Var's tvar.
            case (subst, NamedAst.FormalParam(sym, tpe, loc)) => ???
          }

          for (
            ___ <- liftM(Type.Bool, subst0);
            tpe <- visitExp(exp);
            ___ <- unifyM(Type.Bool, tpe, loc)
          ) yield Type.Bool

        /*
         * Ascribe expression.
         */
        case NamedAst.Expression.Ascribe(exp, expectedType, loc) =>
          Disambiguation.resolve(expectedType, ns0, program) match {
            case Ok(resolvedType) =>
              for (
                actualType <- visitExp(exp);
                resultType <- unifyM(actualType, resolvedType, loc)
              ) yield resultType
            case Err(e) => failM(e)
          }

        /*
         * User Error expression.
         */
        case NamedAst.Expression.UserError(tvar, loc) => liftM(tvar)

      }

      // TODO: Doc and names.
      def visitExps(es: List[NamedAst.Expression], tpe: Type): InferMonad[Type] = es match {
        case Nil => liftM(tpe)
        case x :: xs =>
          for (
            tpe1 <- visitExp(x);
            tpe2 <- visitExps(xs, tpe);
            resultType <- unifyM(tpe1, tpe2, x.loc)
          ) yield resultType
      }

      /**
        * Infers the type of the given pattern `pat0`.
        */
      def visitPat(pat0: NamedAst.Pattern, ns0: Name.NName): InferMonad[Type] = pat0 match {
        case NamedAst.Pattern.Wild(tvar, loc) => liftM(tvar)
        case NamedAst.Pattern.Var(sym, tvar, loc) => unifyM(sym.tvar, tvar, loc)
        case NamedAst.Pattern.Unit(loc) => liftM(Type.Unit)
        case NamedAst.Pattern.True(loc) => liftM(Type.Bool)
        case NamedAst.Pattern.False(loc) => liftM(Type.Bool)
        case NamedAst.Pattern.Char(c, loc) => liftM(Type.Char)
        case NamedAst.Pattern.Float32(i, loc) => liftM(Type.Float32)
        case NamedAst.Pattern.Float64(i, loc) => liftM(Type.Float64)
        case NamedAst.Pattern.Int8(i, loc) => liftM(Type.Int8)
        case NamedAst.Pattern.Int16(i, loc) => liftM(Type.Int16)
        case NamedAst.Pattern.Int32(i, loc) => liftM(Type.Int32)
        case NamedAst.Pattern.Int64(i, loc) => liftM(Type.Int64)
        case NamedAst.Pattern.BigInt(i, loc) => liftM(Type.BigInt)
        case NamedAst.Pattern.Str(s, loc) => liftM(Type.Str)
        case NamedAst.Pattern.Tag(enum, tag, pat, tvar, loc) =>
          Disambiguation.lookupEnumByTag(enum, tag, ns0, program) match {
            case Ok(decl) => Disambiguation.resolve(decl.tpe, ns0, program) match {
              case Ok(enumType) =>
                val cazeType = enumType.asInstanceOf[Type.Enum].cases(tag.name)
                for (
                  innerType <- visitPat(pat, ns0);
                  _________ <- unifyM(innerType, cazeType, loc);
                  resultType <- unifyM(tvar, enumType, loc)
                ) yield resultType
              case Err(e) => failM(e)
            }
            case Err(e) => failM(e)
          }

        case NamedAst.Pattern.Tuple(elms, tvar, loc) =>
          for (
            elementTypes <- visitPats2(elms, ns0);
            resultType <- unifyM(tvar, Type.mkFTuple(elementTypes), loc)
          ) yield resultType

        case NamedAst.Pattern.FNone(tvar, loc) => ???
        case NamedAst.Pattern.FSome(pat, tvar, loc) => ???
        case NamedAst.Pattern.FNil(tvar, loc) => ???
        case NamedAst.Pattern.FList(hd, tl, tvar, loc) => ???
        case NamedAst.Pattern.FVec(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FSet(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FMap(elms, rest, tvar, loc) => ???
      }

      // TODO: Doc and names.
      def visitPats2(es: List[NamedAst.Pattern], ns: Name.NName): InferMonad[List[Type]] = es match {
        case Nil => liftM(Nil)
        case x :: xs =>
          for (
            tpe <- visitPat(x, ns);
            tpes <- visitPats2(xs, ns)
          ) yield tpe :: tpes
      }


      // TODO: Need to create initial type environment from defn

      visitExp(exp0)

    }

    /**
      * Applies the given substitution `subst0` to the given expression `exp0` in the given namespace `ns0`.
      */
    def reassemble(exp0: NamedAst.Expression, ns0: Name.NName, program: Program, subst0: Substitution): TypedAst.Expression = {
      /**
        * Applies the given substitution `subst0` to the given expression `exp0`.
        */
      def visitExp(exp0: NamedAst.Expression, subst0: Substitution): TypedAst.Expression = exp0 match {
        /*
         * Wildcard expression.
         */
        case NamedAst.Expression.Wild(tvar, loc) => throw InternalCompilerException("Not yet supported")

        /*
         * Variable expression.
         */
        // TODO: Does this mean we should not distinguish variables?
        case NamedAst.Expression.Var(sym, loc) =>
          val qname = Name.mkQName(sym.text)
          Disambiguation.lookupRef(qname, ns0, program) match {
            case Ok(Target.Defn(ns, defn)) => TypedAst.Expression.Ref(defn.sym.toResolvedTemporaryHelperMethod, subst0(sym.tvar), loc)
            case Ok(Target.Hook(hook)) => TypedAst.Expression.Hook(hook, subst0(sym.tvar), loc)
            case Err(e) => TypedAst.Expression.Var(sym.toIdent, subst0(sym.tvar), loc)
          }

        /*
         * Reference expression.
         */
        case NamedAst.Expression.Ref(qname, tvar, loc) =>
          Disambiguation.lookupRef(qname, ns0, program).get match {
            case Target.Defn(ns, defn) => TypedAst.Expression.Ref(defn.sym.toResolvedTemporaryHelperMethod, subst0(tvar), loc)
            case Target.Hook(hook) => TypedAst.Expression.Hook(hook, hook.tpe, loc)
          }

        /*
         * Literal expression.
         */
        case NamedAst.Expression.Unit(loc) => TypedAst.Expression.Unit(loc)
        case NamedAst.Expression.True(loc) => TypedAst.Expression.True(loc)
        case NamedAst.Expression.False(loc) => TypedAst.Expression.False(loc)
        case NamedAst.Expression.Char(lit, loc) => TypedAst.Expression.Char(lit, loc)
        case NamedAst.Expression.Float32(lit, loc) => TypedAst.Expression.Float32(lit, loc)
        case NamedAst.Expression.Float64(lit, loc) => TypedAst.Expression.Float64(lit, loc)
        case NamedAst.Expression.Int8(lit, loc) => TypedAst.Expression.Int8(lit, loc)
        case NamedAst.Expression.Int16(lit, loc) => TypedAst.Expression.Int16(lit, loc)
        case NamedAst.Expression.Int32(lit, loc) => TypedAst.Expression.Int32(lit, loc)
        case NamedAst.Expression.Int64(lit, loc) => TypedAst.Expression.Int64(lit, loc)
        case NamedAst.Expression.BigInt(lit, loc) => TypedAst.Expression.BigInt(lit, loc)
        case NamedAst.Expression.Str(lit, loc) => TypedAst.Expression.Str(lit, loc)

        /*
         * Apply expression.
         */
        case NamedAst.Expression.Apply(lambda, actuals, tvar, loc) =>
          val l = visitExp(lambda, subst0)
          val as = actuals.map(e => visitExp(e, subst0))
          TypedAst.Expression.Apply(l, as, subst0(tvar), loc)

        /*
         * Lambda expression.
         */
        case NamedAst.Expression.Lambda(params, exp, tvar, loc) =>
          val lambdaArgs = params map {
            case sym =>
              val argIdent = sym.toIdent
              val argType = subst0(sym.tvar)
              TypedAst.FormalArg(argIdent, argType)
          }
          val lambdaBody = reassemble(exp, ns0, program, subst0)
          val lambdaType = subst0(tvar)
          TypedAst.Expression.Lambda(lambdaArgs, lambdaBody, lambdaType, loc)

        /*
         * Unary expression.
         */
        case NamedAst.Expression.Unary(op, exp, tvar, loc) =>
          val e = visitExp(exp, subst0)
          TypedAst.Expression.Unary(op, e, subst0(tvar), loc)

        /*
         * Binary expression.
         */
        case NamedAst.Expression.Binary(op, exp1, exp2, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val e2 = visitExp(exp2, subst0)
          TypedAst.Expression.Binary(op, e1, e2, subst0(tvar), loc)

        /*
         * If-then-else expression.
         */
        case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val e2 = visitExp(exp2, subst0)
          val e3 = visitExp(exp3, subst0)
          TypedAst.Expression.IfThenElse(e1, e2, e3, subst0(tvar), loc)

        /*
         * Let expression.
         */
        case NamedAst.Expression.Let(sym, exp1, exp2, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val e2 = visitExp(exp2, subst0)
          TypedAst.Expression.Let(sym.toIdent, e1, e2, subst0(tvar), loc)

        /*
         * Match expression.
         */
        case NamedAst.Expression.Match(exp1, rules, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val rs = rules map {
            case (pat, exp) => visitPat(pat, subst0) -> visitExp(exp, subst0)
          }
          TypedAst.Expression.Match(e1, rs, subst0(tvar), loc)

        /*
         * Switch expression.
         */
        case NamedAst.Expression.Switch(rules, tvar, loc) =>
          val rs = rules.map {
            case (cond, body) => (visitExp(cond, subst0), visitExp(body, subst0))
          }
          TypedAst.Expression.Switch(rs, subst0(tvar), loc)

        /*
         * Tag expression.
         */
        case NamedAst.Expression.Tag(enum, tag, exp, tvar, loc) =>
          val e = visitExp(exp, subst0)
          TypedAst.Expression.Tag(enum.toResolved, tag, e, subst0(tvar), loc)

        /*
         * Tuple expression.
         */
        case NamedAst.Expression.Tuple(elms, tvar, loc) =>
          val es = elms.map(e => visitExp(e, subst0))
          TypedAst.Expression.Tuple(es, subst0(tvar), loc)

        /*
         * None expression.
         */
        case NamedAst.Expression.FNone(tvar, loc) =>
          TypedAst.Expression.FNone(subst0(tvar), loc)

        /*
         * Some expression.
         */
        case NamedAst.Expression.FSome(exp, tvar, loc) =>
          val e = visitExp(exp, subst0)
          TypedAst.Expression.FSome(e, subst0(tvar), loc)

        /*
         * Nil expression.
         */
        case NamedAst.Expression.FNil(tvar, loc) =>
          TypedAst.Expression.FNil(subst0(tvar), loc)

        /*
         * List expression.
         */
        case NamedAst.Expression.FList(hd, tl, tvar, loc) =>
          val e1 = visitExp(hd, subst0)
          val e2 = visitExp(tl, subst0)
          TypedAst.Expression.FList(e1, e2, subst0(tvar), loc)

        /*
         * Vec expression.
         */
        case NamedAst.Expression.FVec(elms, tvar, loc) =>
          val es = elms.map(e => visitExp(e, subst0))
          TypedAst.Expression.FVec(es, subst0(tvar), loc)

        /*
         * Set expression.
         */
        case NamedAst.Expression.FSet(elms, tvar, loc) =>
          val es = elms.map(e => visitExp(e, subst0))
          TypedAst.Expression.FSet(es, subst0(tvar), loc)

        /*
         * Map expression.
         */
        case NamedAst.Expression.FMap(elms, tvar, loc) =>
          val es = elms map {
            case (key, value) => (visitExp(key, subst0), visitExp(value, subst0))
          }
          TypedAst.Expression.FMap(es, subst0(tvar), loc)

        /*
         * GetIndex expression.
         */
        case NamedAst.Expression.GetIndex(exp1, exp2, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val e2 = visitExp(exp2, subst0)
          TypedAst.Expression.GetIndex(e1, e2, subst0(tvar), loc)

        /*
         * PutIndex expression.
         */
        case NamedAst.Expression.PutIndex(exp1, exp2, exp3, tvar, loc) =>
          val e1 = visitExp(exp1, subst0)
          val e2 = visitExp(exp2, subst0)
          val e3 = visitExp(exp3, subst0)
          TypedAst.Expression.PutIndex(e1, e2, e3, subst0(tvar), loc)

        /*
         * Existential expression.
         */
        case NamedAst.Expression.Existential(params, exp, loc) =>
          val e = visitExp(exp, subst0)
          TypedAst.Expression.Existential(compat(params, subst0), e, loc)

        /*
         * Universal expression.
         */
        case NamedAst.Expression.Universal(params, exp, loc) =>
          val e = visitExp(exp, subst0)
          TypedAst.Expression.Universal(compat(params, subst0), e, loc)

        /*
         * Ascribe expression.
         */
        case NamedAst.Expression.Ascribe(exp, tpe, loc) =>
          // simply reassemble the nested expression.
          visitExp(exp, subst0)

        /*
         * User Error expression.
         */
        case NamedAst.Expression.UserError(tvar, loc) =>
          TypedAst.Expression.Error(subst0(tvar), loc)
      }

      /**
        * Applies the given substitution `subst0` to the given pattern `pat0`.
        */
      def visitPat(pat0: NamedAst.Pattern, subst0: Substitution): TypedAst.Pattern = pat0 match {
        case NamedAst.Pattern.Wild(tvar, loc) => TypedAst.Pattern.Wildcard(subst0(tvar), loc)
        case NamedAst.Pattern.Var(sym, tvar, loc) => TypedAst.Pattern.Var(sym.toIdent, subst0(tvar), loc)
        case NamedAst.Pattern.Unit(loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Unit(loc), Type.Unit, loc)
        case NamedAst.Pattern.True(loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Bool(lit = true, loc), Type.Bool, loc)
        case NamedAst.Pattern.False(loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Bool(lit = false, loc), Type.Bool, loc)
        case NamedAst.Pattern.Char(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Char(lit, loc), Type.Char, loc)
        case NamedAst.Pattern.Float32(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Float32(lit, loc), Type.Float32, loc)
        case NamedAst.Pattern.Float64(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Float64(lit, loc), Type.Float64, loc)
        case NamedAst.Pattern.Int8(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Int8(lit, loc), Type.Int8, loc)
        case NamedAst.Pattern.Int16(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Int16(lit, loc), Type.Int16, loc)
        case NamedAst.Pattern.Int32(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Int32(lit, loc), Type.Int32, loc)
        case NamedAst.Pattern.Int64(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Int64(lit, loc), Type.Int64, loc)
        case NamedAst.Pattern.BigInt(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.BigInt(lit, loc), Type.BigInt, loc)
        case NamedAst.Pattern.Str(lit, loc) => TypedAst.Pattern.Lit(TypedAst.Literal.Str(lit, loc), Type.Str, loc)
        case NamedAst.Pattern.Tag(enum, tag, pat, tvar, loc) =>
          val p = visitPat(pat, subst0)
          TypedAst.Pattern.Tag(enum.toResolved, tag, p, subst0(tvar), loc)
        case NamedAst.Pattern.Tuple(elms, tvar, loc) =>
          val es = elms.map(e => visitPat(e, subst0))
          TypedAst.Pattern.Tuple(es, subst0(tvar), loc)
        case NamedAst.Pattern.FNone(tvar, loc) => ???
        case NamedAst.Pattern.FSome(pat, tvar, loc) => ???
        case NamedAst.Pattern.FNil(tvar, loc) => ???
        case NamedAst.Pattern.FList(hd, tl, tvar, loc) => ???
        case NamedAst.Pattern.FVec(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FSet(elms, rest, tvar, loc) => ???
        case NamedAst.Pattern.FMap(elms, rest, tvar, loc) => ???
      }

      visitExp(exp0, subst0)
    }
  }

  object Predicates {

    /**
      * Infers the type of the given head predicate.
      */
    def infer(head: NamedAst.Predicate.Head, ns: Name.NName, program: Program)(implicit genSym: GenSym): InferMonad[List[Type]] = head match {
      case NamedAst.Predicate.Head.True(loc) => Unification.liftM(Nil)
      case NamedAst.Predicate.Head.False(loc) => Unification.liftM(Nil)
      case NamedAst.Predicate.Head.Table(qname, terms, loc) =>
        val declaredTypes = Disambiguation.lookupTable(qname, ns, program) match {
          case Ok(NamedAst.Table.Relation(sym, attr, _)) => attr.map(_.tpe)
          case Ok(NamedAst.Table.Lattice(sym, keys, value, _)) => keys.map(_.tpe) ::: value.tpe :: Nil
          case Err(e) => return failM(e)
        }

        Disambiguation.resolve(declaredTypes, ns, program) match {
          case Ok(expectedTypes) =>
            for (
              actualTypes <- seqM(terms.map(t => Expressions.infer(t, ns, program)));
              unifiedTypes <- Unification.unifyM(expectedTypes, actualTypes, loc)
            ) yield unifiedTypes
          case Err(e) => failM(e)
        }
    }

    /**
      * Infers the type of the given body predicate.
      */
    def infer(body0: NamedAst.Predicate.Body, ns0: Name.NName, program: Program)(implicit genSym: GenSym): InferMonad[List[Type]] = body0 match {
      case NamedAst.Predicate.Body.Table(qname, terms, loc) =>
        val declaredTypes = Disambiguation.lookupTable(qname, ns0, program) match {
          case Ok(NamedAst.Table.Relation(sym, attr, _)) => attr.map(_.tpe)
          case Ok(NamedAst.Table.Lattice(sym, keys, value, _)) => keys.map(_.tpe) ::: value.tpe :: Nil
          case Err(e) => return failM(e)
        }
        val expectedTypes = Disambiguation.resolve(declaredTypes, ns0, program) match {
          case Ok(tpes) => tpes
          case Err(e) => return failM(e)
        }
        for (
          actualTypes <- seqM(terms.map(t => Expressions.infer(t, ns0, program)));
          unifiedTypes <- Unification.unifyM(expectedTypes, actualTypes, loc)
        ) yield unifiedTypes
      case NamedAst.Predicate.Body.Filter(qname, terms, loc) =>
        Disambiguation.lookupRef(qname, ns0, program) match {
          case Ok(Target.Defn(ns, defn)) =>
            val expectedTypes = Disambiguation.resolve(defn.params.map(_.tpe), ns, program) match {
              case Ok(tpes) => tpes
              case Err(e) => return failM(e)
            }
            for (
              actualTypes <- seqM(terms.map(t => Expressions.infer(t, ns0, program)));
              unifiedTypes <- Unification.unifyM(expectedTypes, actualTypes, loc)
            ) yield unifiedTypes
          case Ok(Target.Hook(hook)) =>
            val Type.Apply(Type.Arrow(l), ts) = hook.tpe
            val declaredTypes = ts.take(l - 1)
            for (
              actualTypes <- seqM(terms.map(t => Expressions.infer(t, ns0, program)));
              unifiedTypes <- Unification.unifyM(declaredTypes, actualTypes, loc)
            ) yield unifiedTypes
          case Err(e) => failM(e)
        }
      case NamedAst.Predicate.Body.NotEqual(ident1, ident2, loc) => Unification.liftM(Nil) // TODO
      case NamedAst.Predicate.Body.Loop(ident, term, loc) => Unification.liftM(Nil) // TODO
    }

    /**
      * Applies the given substitution `subst0` to the given head predicate `head0` in the given namespace `ns0`.
      */
    def reassemble(head0: NamedAst.Predicate.Head, ns0: Name.NName, program: Program, subst0: Substitution): TypedAst.Predicate.Head = head0 match {
      case NamedAst.Predicate.Head.True(loc) => TypedAst.Predicate.Head.True(loc)
      case NamedAst.Predicate.Head.False(loc) => TypedAst.Predicate.Head.False(loc)
      case NamedAst.Predicate.Head.Table(qname, terms, loc) =>
        Disambiguation.lookupTable(qname, ns0, program) match {
          case Ok(NamedAst.Table.Relation(sym, _, _)) =>
            TypedAst.Predicate.Head.Table(sym, terms.map(t => Terms.compatHead(t, ns0, program, subst0)), loc)
          case Ok(NamedAst.Table.Lattice(sym, _, _, _)) =>
            TypedAst.Predicate.Head.Table(sym, terms.map(t => Terms.compatHead(t, ns0, program, subst0)), loc)
          case Err(e) => throw InternalCompilerException("Lookup should have failed during type inference.")
        }
    }

    /**
      * Applies the given substitution `subst0` to the given body predicate `body0` in the given namespace `ns0`.
      */
    def reassemble(body0: NamedAst.Predicate.Body, ns0: Name.NName, program: Program, subst0: Substitution): TypedAst.Predicate.Body = body0 match {
      case NamedAst.Predicate.Body.Table(qname, terms, loc) =>
        Disambiguation.lookupTable(qname, ns0, program) match {
          case Ok(NamedAst.Table.Relation(sym, _, _)) => TypedAst.Predicate.Body.Table(sym, terms.map(t => Terms.compatBody(t, ns0, program, subst0)), loc)
          case Ok(NamedAst.Table.Lattice(sym, _, _, _)) => TypedAst.Predicate.Body.Table(sym, terms.map(t => Terms.compatBody(t, ns0, program, subst0)), loc)
          case Err(e) => throw InternalCompilerException("Lookup should have failed during type inference.")
        }
      case NamedAst.Predicate.Body.Filter(qname, terms, loc) =>
        Disambiguation.lookupRef(qname, ns0, program) match {
          case Ok(Target.Defn(ns, defn)) =>
            TypedAst.Predicate.Body.ApplyFilter(defn.sym.toResolvedTemporaryHelperMethod, terms.map(t => Terms.compatBody(t, ns0, program, subst0)), loc)
          case Ok(Target.Hook(hook)) => TypedAst.Predicate.Body.ApplyHookFilter(hook, terms.map(t => Terms.compatBody(t, ns0, program, subst0)), loc)
          case Err(e) => throw InternalCompilerException(s"Never happens. Unable to lookup filter function '$qname'.")
        }
      case NamedAst.Predicate.Body.NotEqual(ident1, ident2, loc) =>
        // TODO: Need to retrieve the symbol...
        TypedAst.Predicate.Body.NotEqual(ident1, ident2, loc)
      case NamedAst.Predicate.Body.Loop(ident, term, loc) =>
        // TODO: Need to retrieve the symbol...
        TypedAst.Predicate.Body.Loop(ident, Terms.compatHead(term, ns0, program, subst0), loc)
    }

  }

  object Terms {

    // TODO: Temporary method
    def compatHead(exp0: NamedAst.Expression, ns0: Name.NName, program: Program, subst0: Substitution): TypedAst.Term.Head = {
      exp2headterm(Expressions.reassemble(exp0, ns0, program, subst0), ns0, program)
    }

    def compatBody(exp0: NamedAst.Expression, ns0: Name.NName, program: Program, subst0: Substitution): TypedAst.Term.Body = {
      exp2bodyterm(Expressions.reassemble(exp0, ns0, program, subst0))
    }

    def exp2headterm(exp0: TypedAst.Expression, ns0: Name.NName, program: Program): TypedAst.Term.Head = {

      def visit(e0: TypedAst.Expression): TypedAst.Term.Head = e0 match {
        case TypedAst.Expression.Var(ident, tpe, loc) => TypedAst.Term.Head.Var(ident, tpe, loc)
        case TypedAst.Expression.Unit(loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Unit(loc), Type.Unit, loc)
        case TypedAst.Expression.True(loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Bool(true, loc), Type.Bool, loc)
        case TypedAst.Expression.False(loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Bool(false, loc), Type.Bool, loc)
        case TypedAst.Expression.Char(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Char(lit, loc), Type.Char, loc)
        case TypedAst.Expression.Float32(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Float32(lit, loc), Type.Float32, loc)
        case TypedAst.Expression.Float64(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Float64(lit, loc), Type.Float32, loc)
        case TypedAst.Expression.Int8(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Int8(lit, loc), Type.Int8, loc)
        case TypedAst.Expression.Int16(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Int16(lit, loc), Type.Int16, loc)
        case TypedAst.Expression.Int32(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Int32(lit, loc), Type.Int32, loc)
        case TypedAst.Expression.Int64(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Int64(lit, loc), Type.Int64, loc)
        case TypedAst.Expression.BigInt(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.BigInt(lit, loc), Type.BigInt, loc)
        case TypedAst.Expression.Str(lit, loc) => TypedAst.Term.Head.Lit(TypedAst.Literal.Str(lit, loc), Type.Str, loc)
        case TypedAst.Expression.Lit(lit, tpe, loc) => TypedAst.Term.Head.Lit(lit, tpe, loc)
        case TypedAst.Expression.Tag(enumName, tagName, exp, tpe, loc) => TypedAst.Term.Head.Tag(enumName, tagName, visit(exp), tpe, loc)
        case TypedAst.Expression.Tuple(elms, tpe, loc) => TypedAst.Term.Head.Tuple(elms map visit, tpe, loc)
        case TypedAst.Expression.Apply(base, args, tpe, loc) => base match {
          case TypedAst.Expression.Ref(name, _, _) => TypedAst.Term.Head.Apply(name, args.map(visit), tpe, loc)
          case TypedAst.Expression.Hook(hook, _, _) => TypedAst.Term.Head.ApplyHook(hook, args.map(visit), tpe, loc)
          case _ => throw new RuntimeException(s"Unknown expression $base") // TODO
        }

        case _ => throw new UnsupportedOperationException(s"Not implemented for $e0")
      }

      visit(exp0)
    }

    def exp2bodyterm(exp0: TypedAst.Expression): TypedAst.Term.Body = exp0 match {
      case TypedAst.Expression.Wild(tpe, loc) => TypedAst.Term.Body.Wildcard(tpe, loc)
      case TypedAst.Expression.Var(ident, tpe, loc) => TypedAst.Term.Body.Var(ident, tpe, loc)
      case TypedAst.Expression.Unit(loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Unit(loc), Type.Unit, loc)
      case TypedAst.Expression.True(loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Bool(true, loc), Type.Bool, loc)
      case TypedAst.Expression.False(loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Bool(false, loc), Type.Bool, loc)
      case TypedAst.Expression.Char(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Char(lit, loc), Type.Char, loc)
      case TypedAst.Expression.Float32(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Float32(lit, loc), Type.Float32, loc)
      case TypedAst.Expression.Float64(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Float64(lit, loc), Type.Float32, loc)
      case TypedAst.Expression.Int8(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Int8(lit, loc), Type.Int8, loc)
      case TypedAst.Expression.Int16(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Int16(lit, loc), Type.Int16, loc)
      case TypedAst.Expression.Int32(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Int32(lit, loc), Type.Int32, loc)
      case TypedAst.Expression.Int64(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Int64(lit, loc), Type.Int64, loc)
      case TypedAst.Expression.BigInt(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.BigInt(lit, loc), Type.BigInt, loc)
      case TypedAst.Expression.Str(lit, loc) => TypedAst.Term.Body.Lit(TypedAst.Literal.Str(lit, loc), Type.Str, loc)
      case TypedAst.Expression.Lit(lit, tpe, loc) => TypedAst.Term.Body.Lit(lit, tpe, loc)
      case _ => throw new UnsupportedOperationException(s"Not implemented for $exp0")
    }

  }

  /**
    * Returns a substitution from formal parameters to their declared types.
    *
    * @param params  the formal parameters.
    * @param ns0     the current namespace.
    * @param program the program.
    */
  // TODO: Move into Unification?
  private def getSubst(params: List[NamedAst.FormalParam], ns0: Name.NName, program: Program): Result[Unification.Substitution, TypeError] = {
    val declaredTypes = Disambiguation.resolve(params.map(_.tpe), ns0, program) match {
      case Ok(tpes) => tpes
      case Err(e) => return Err(e)
    }

    val substitution = (params zip declaredTypes).foldLeft(Substitution.empty) {
      case (substacc, (NamedAst.FormalParam(sym, _, _), declaredType)) =>
        substacc ++ Substitution.singleton(sym.tvar, declaredType)
    }

    Ok(substitution)
  }

  // TODO: Compatability --------------

  private def compat(ps: List[NamedAst.FormalParam], subst: Substitution): List[Ast.FormalParam] = ps map {
    case NamedAst.FormalParam(sym, tpe, loc) => Ast.FormalParam(sym.toIdent, subst(???))
  }

  // TODO: Ugly hack. Remove once we have type classes.
  def guesstimateType(tpe1: Type, tpe2: Type): Type = (tpe1, tpe2) match {
    case (Type.Float32, _) => Type.Float32
    case (_, Type.Float32) => Type.Float32

    case (Type.Float64, _) => Type.Float64
    case (_, Type.Float64) => Type.Float64

    case (Type.Int8, _) => Type.Int8
    case (_, Type.Int8) => Type.Int8

    case (Type.Int16, _) => Type.Int16
    case (_, Type.Int16) => Type.Int16

    case (Type.Int32, _) => Type.Int32
    case (_, Type.Int32) => Type.Int32

    case (Type.Int64, _) => Type.Int64
    case (_, Type.Int64) => Type.Int64

    case (Type.BigInt, _) => Type.BigInt
    case (_, Type.BigInt) => Type.BigInt

    case _ => Type.Int32
  }

}
