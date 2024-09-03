/*
 * Copyright 2024 Alexander Dybdahl Troelsen
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
package ca.uwaterloo.flix.api.lsp.provider.completion

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.api.lsp.Index
import ca.uwaterloo.flix.language.ast.TypedAst

/**
  * A collection of keyword completers, including
  *
  * - `Decl`: completer for declaraiton keywords
  * - `Enum`: completer for declaration keywords
  * - `Expr`: completer for expressoin keywords
  * - `Other`: completer for miscellaneous keywords
  *
  */
object KeywordCompleter {
  private def toCompletion(entry: (String, String => String)): Completion = {
    val (keyword, priority) = entry
    Completion.KeywordCompletion(keyword, priority(keyword))
  }
    

  /**
    * Miscellaneous keywords.
    */
  def getOtherKeywords(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root): Iterable[Completion] =
    List(
      ("with", Priority.highest),
      ("@Test", Priority.highest),
      ("law", Priority.higher),
      ("redef", Priority.high),
      ("where", Priority.high),
      ("fix", Priority.high),
      ("@Parallel", Priority.low),
      ("@Lazy", Priority.low),
      ("@ParallelWhenPure", Priority.lower),
      ("@LazyWhenPure", Priority.lower),
      ("@Deprecated", Priority.lowest),
      ("Record", Priority.lowest),
      ("Schema", Priority.lowest),
    ) map toCompletion

  /**
    * Trait declaration keywords. These are keywords that occur within a trait declaration.
    */
  def getTraitKeywords(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root): Iterable[Completion] =
    List(
      ("def", Priority.highest),
      ("pub", Priority.highest),
    ) map toCompletion


  /**
    * Declaration keywords. These are keywords that denote a declaration.
    */
  def getDeclKeywords(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root): Iterable[Completion] =
    List(
      ("def"      , Priority.highest),   
      ("pub"      , Priority.highest),
      ("instance" , Priority.higher),
      ("import"   , Priority.high),
      ("type"     , Priority.high),
      ("mod"      , Priority.high),
      ("trait"    , Priority.low),
      ("enum"     , Priority.low),
      ("eff"      , Priority.lower),
      ("struct"   , Priority.lowest),
      ("sealed"   , Priority.lowest),
    ) map toCompletion

  /**
    * Enum keywords. These are keywords that can appear within the declaration of an enum.
    */
  def getEnumKeywords(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root): Iterable[Completion] =
    List(
      ("case", Priority.low)
    ) map toCompletion

  /**
    * Expression keywords. These are keywords that can appear within expressions (fx within the body of a function).
    */
  def getExprKeywords(context: CompletionContext)(implicit flix: Flix, index: Index, root: TypedAst.Root): Iterable[Completion] =
    List(
      "and",
      "as",
      "def",
      "discard",
      "do",
      "else",
      "false",
      "forA",
      "forM",
      "force",
      "foreach",
      "from",
      "if",
      "inject",
      "into",
      "lazy",
      "let",
      "match",
      "new",
      "not",
      "or",
      "par",
      "query",
      "region",
      "select",
      "solve",
      "spawn",
      "struct",
      "true",
      "try",
      "typematch",
      "unsafe",
      "use",
      "without",
      "yield"
    ) map (name => Completion.KeywordCompletion(name, Priority.lower(name)))
}
