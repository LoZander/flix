package ca.uwaterloo.flix.api.lsp

import ca.uwaterloo.flix.language.ast.Ast.AssocTypeConstructor
import ca.uwaterloo.flix.language.ast.{Symbol, Type}
import ca.uwaterloo.flix.language.ast.TypedAst.Pattern.Record.RecordLabelPattern
import ca.uwaterloo.flix.language.ast.TypedAst.{AssocTypeDef, AssocTypeSig, Binder, Case, CatchRule, Constraint, ConstraintParam, Def, Effect, Enum, Expr, FormalParam, HandlerRule, Instance, JvmMethod, MatchRule, Op, ParYieldFragment, Pattern, Predicate, PredicateParam, SelectChannelRule, Sig, Struct, StructField, Trait, TypeAlias, TypeMatchRule, TypeParam}
import ca.uwaterloo.flix.language.ast.shared.SymUse.{AssocTypeSymUse, CaseSymUse, DefSymUse, EffectSymUse, LocalDefSymUse, OpSymUse, SigSymUse, StructFieldSymUse, TraitSymUse}
import ca.uwaterloo.flix.language.ast.shared.{Annotation, Derivation, Derivations, EqualityConstraint, TraitConstraint}

/**
  * Defines how each AST node type is handled when it's visited.
  *
  * Each consume function `consumeX` takes a value of type `X` and returns [[Unit]].
  * Thus, anything it does is through effects.
  *
  * Implementations need only implement the consume functions that they care about,
  * Any consume function not implemented falls back on the default of doing nothing
  * upon consuming the corresponding AST node.
  *
  * Example
  * {{{
  * object ExprListConsumer extends Consumer {
  *   var stack: List[Expr] = Nil
  *   override consumeExpr(exp: Expr): Unit = {
  *     stack = exp :: stack
  *   }
  * }
  * }}}
  *
  * This consumer only cares about [[Expr]]s and simply collects all expressions visited.
  */
trait Consumer {
  def consumeAnnotation(ann: Annotation): Unit = ()
  def consumeAssocTypeConstructor(tcst: AssocTypeConstructor): Unit = ()
  def consumeAssocTypeDef(tdefn: AssocTypeDef): Unit = ()
  def consumeAssocTypeSig(tsig: AssocTypeSig): Unit = ()
  def consumeAssocTypeSymUse(symUse: AssocTypeSymUse): Unit = ()
  def consumeBinder(bnd: Binder): Unit = ()
  def consumeCase(cse: Case): Unit = ()
  def consumeCaseSymUse(sym: CaseSymUse): Unit = ()
  def consumeCatchRule(rule: CatchRule): Unit = ()
  def consumeConstraint(c: Constraint): Unit = ()
  def consumeConstraintParam(cparam: ConstraintParam): Unit = ()
  def consumeDef(defn: Def): Unit = ()
  def consumeDefSymUse(sym: DefSymUse): Unit = ()
  def consumeDerivation(derive: Derivation): Unit = ()
  def consumeDerivations(derives: Derivations): Unit = ()
  def consumeEff(eff: Effect): Unit = ()
  def consumeEffectSymUse(effUse: EffectSymUse): Unit = ()
  def consumeEnum(enm: Enum): Unit = ()
  def consumeEqualityConstraint(ec: EqualityConstraint): Unit = ()
  def consumeExpr(exp: Expr): Unit = ()
  def consumeFormalParam(fparam: FormalParam): Unit = ()
  def consumeParYieldFragment(frag: ParYieldFragment): Unit = ()
  def consumeHandlerRule(rule: HandlerRule): Unit = ()
  def consumeInstance(ins: Instance): Unit = ()
  def consumeJvmMethod(method: JvmMethod): Unit = ()
  def consumeLocalDefSym(symUse: LocalDefSymUse): Unit = ()
  def consumeMatchRule(rule: MatchRule): Unit = ()
  def consumeOp(op: Op): Unit = ()
  def consumeOpSymUse(sym: OpSymUse): Unit = ()
  def consumePattern(pat: Pattern): Unit = ()
  def consumePredicate(p: Predicate): Unit = ()
  def consumePredicateParam(pparam: PredicateParam): Unit = ()
  def consumeRecordLabelPattern(pat: RecordLabelPattern): Unit = ()
  def consumeSelectChannelRule(rule: SelectChannelRule): Unit = ()
  def consumeSig(sig: Sig): Unit = ()
  def consumeSigSymUse(symUse: SigSymUse): Unit = ()
  def consumeStruct(struct: Struct): Unit = ()
  def consumeStructField(field: StructField): Unit = ()
  def consumeStructFieldSymUse(symUse: StructFieldSymUse): Unit = ()
  def consumeTypeMatchRule(rule: TypeMatchRule): Unit = ()
  def consumeTrait(traitt: Trait): Unit = ()
  def consumeTraitConstraint(tc: TraitConstraint): Unit = ()
  def consumeTraitConstraintHead(tcHead: TraitConstraint.Head): Unit = ()
  def consumeTraitSymUse(symUse: TraitSymUse): Unit= ()
  def consumeType(tpe: Type): Unit = ()
  def consumeTypeAlias(alias: TypeAlias): Unit = ()
  def consumeTypeParam(tparam: TypeParam): Unit = ()
  def consumeVarBinder(varSym: Symbol.VarSym, tpe: Type): Unit = ()
}