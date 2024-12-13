/*
 * Copyright 2021 Magnus Madsen
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
package ca.uwaterloo.flix.language.phase.extra

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.acceptors.AllAcceptor
import ca.uwaterloo.flix.api.lsp.{Consumer, Index, Visitor}
import ca.uwaterloo.flix.language.ast.TypedAst.Predicate.{Body, Head}
import ca.uwaterloo.flix.language.ast.TypedAst.*
import ca.uwaterloo.flix.language.ast.shared.{Annotation, Annotations, SymUse, TraitConstraint}
import ca.uwaterloo.flix.language.ast.shared.SymUse.DefSymUse
import ca.uwaterloo.flix.language.ast.{Ast, SourceLocation, Symbol, Type, TypeConstructor, TypedAst}
import ca.uwaterloo.flix.language.errors.CodeHint
import ca.uwaterloo.flix.util.collection.MultiMap

object CodeHinter {

  /**
    * Returns a collection of code quality hints for the given AST `root`.
    */
  def run(root: TypedAst.Root, sources: Set[String]): List[CodeHint] = {
    var hints: List[CodeHint] = Nil

    object HintConsumer extends Consumer {
      override def consumeTraitSymUse(symUse: SymUse.TraitSymUse): Unit = {
        hints = hints ++ considerTrait(root, symUse.sym, symUse.loc)
      }
      override def consumeTraitConstraintHead(tcHead: TraitConstraint.Head): Unit = {
        hints = hints ++ considerTrait(root, tcHead.sym, tcHead.loc)
      }
      override def consumeDefSymUse(sym: DefSymUse): Unit = {
        hints = hints ++ considerDef(root, sym.sym, sym.loc)
      }
      override def consumeType(tpe: Type): Unit = tpe match {
        case Type.Cst(TypeConstructor.Enum(sym, _), loc) =>
          hints = hints ++ considerEnum(root, sym, loc)
        case _ => ()
      }
      override def consumeCaseSymUse(sym: SymUse.CaseSymUse): Unit = {
        hints = hints ++ considerEnum(root, sym.sym.enumSym, sym.loc)
      }
    }

    Visitor.visitRoot(root, HintConsumer, AllAcceptor)

    hints.filter(include(_, sources))
  }

  private def considerTrait(root: Root, sym: Symbol.TraitSym, loc: SourceLocation): List[CodeHint] = {
    val trt = root.traits(sym)
    consider(trt.ann, loc)
  }

  private def considerDef(root: Root, sym: Symbol.DefnSym, loc: SourceLocation): List[CodeHint] = {
    val defn = root.defs(sym)
    consider(defn.spec.ann, loc)
  }

  private def considerEnum(root: Root, sym: Symbol.EnumSym, loc: SourceLocation): List[CodeHint] = {
    val enm = root.enums(sym)
    consider(enm.ann, loc)
  }


  private def consider(ann: Annotations, loc: SourceLocation): List[CodeHint] = {
    val isDepricated = ann.isDeprecated
    val isExperimental = ann.isExperimental
    val depricated = if (isDepricated) { CodeHint.Deprecated(loc) :: Nil } else { Nil }
    val experimental = if (isExperimental) { CodeHint.Experimental(loc) :: Nil } else { Nil }
    depricated ++ experimental
  }

  /**
    * Returns `true` if the given code `hint` should be included in the result.
    */
  private def include(hint: CodeHint, sources: Set[String]): Boolean =
    sources.contains(hint.loc.source.name)
}
