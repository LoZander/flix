/*
 * Copyright 2022 Paul Butcher, Lukas Rønn
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

import ca.uwaterloo.flix.language.ast.TypedAst

object UseCompleter {
  /**
    * Returns a List of Completion for completer.
    */
  def getCompletions(context: CompletionContext)(implicit root: TypedAst.Root): Iterable[Completion] = {
      UseModuleCompleter.getCompletions(context)      ++
      UseEnumCompleter.getCompletions(context)        ++
      UseEffCompleter.getCompletions(context)         ++
      UseDefCompleter.getCompletions(context)         ++
      UseSignatureCompleter.getCompletions(context)   ++
      UseOpCompleter.getCompletions(context)          ++
      UseEnumTagCompleter.getCompletions(context)
  }
}
