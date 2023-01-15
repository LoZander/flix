/*
 * Copyright 2023 Magnus Madsen
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
package ca.uwaterloo.flix.tools.pkg

import java.nio.file.Path

case class Manifest(name: String, description: String, version: SemVer, flix: SemVer, license: Option[String], authors: List[String], dependencies: List[Dependency]) {

  def getFlixPackages: List[Path] = Nil // TODO

  def getMavenPackages: List[Path] = Nil // TODO

}