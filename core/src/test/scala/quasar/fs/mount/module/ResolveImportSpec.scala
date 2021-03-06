/*
 * Copyright 2014–2018 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.fs.mount.module

import slamdata.Predef._
import quasar.common.CIName
import quasar.compile.SemanticError, SemanticError._
import quasar.contrib.pathy.ADir
import quasar.fp.ski.κ
import quasar.sql._

import matryoshka.data.Fix
import pathy.Path._
import scalaz._, Scalaz._

class ResolveImportSpec extends quasar.Qspec {
  def resolveImports(
      scopedExpr: ScopedExpr[Fix[Sql]],
      baseDir: ADir,
      retrieve: ADir => List[Statement[Fix[Sql]]])
      : SemanticError \/ Fix[Sql] =
    ResolveImports[SemanticError \/ ?, Fix](scopedExpr, rootDir, retrieve.andThen(_.right))

  "Import resolution" >> {
    "simple case" >> {
      val scopedExpr = sqlB"import `/mymodule/`; TRIVIAL(`/foo`)"
      val trivial = FunctionDecl(CIName("Trivial"), List(CIName("from")), sqlE"select * FROM :from")
      val mymodule = rootDir </> dir("mymodule")
      val retrieve: ADir => List[FunctionDecl[Fix[Sql]]] = {
        case `mymodule` => List(trivial)
        case _          => Nil
      }
      resolveImports(scopedExpr, rootDir, retrieve) must_=== sqlE"select * from `/foo`".right
    }
    "relative paths in function bodies should resolve relative to the module location" >> {
      val scopedExpr = sqlB"import `/mymodule/a/b/c/`; TRIVIAL(`/foo`)"
      val trivial = FunctionDecl(CIName("Trivial"), List(CIName("from")), sqlE"select * FROM `./data`")
      val mymodule = rootDir </> dir("mymodule") </> dir("a") </> dir("b") </> dir("c")
      val retrieve: ADir => List[FunctionDecl[Fix[Sql]]] = {
        case `mymodule` => List(trivial)
        case _          => Nil
      }
      resolveImports(scopedExpr, rootDir, retrieve) must_=== sqlE"select * from `/mymodule/a/b/c/data`".right
    }
    "multiple imports" >> {
      val scopedExpr = sqlB"import `/mymodule/`; import `/othermodule/`; FOO(1) + Bar(2)"
      val foo = FunctionDecl(CIName("foo"), List(CIName("a")), sqlE":a + 1")
      val bar = FunctionDecl(CIName("bar"), List(CIName("a")), sqlE":a + 2")
      val mymodule    = rootDir </> dir("mymodule")
      val otherModule = rootDir </> dir("othermodule")
      val retrieve: ADir => List[FunctionDecl[Fix[Sql]]] = {
        case `mymodule`    => List(foo)
        case `otherModule` => List(bar)
        case _             => Nil
      }
      resolveImports(scopedExpr, rootDir, retrieve) must_=== sqlE"(1 + 1) + (2 + 2)".right
    }
    "multiple functions within one import" >> {
      val scopedExpr = sqlB"import `/mymodule/`; FOO(1) + Bar(2)"
      val foo = FunctionDecl(CIName("foo"), List(CIName("a")), sqlE":a + 1")
      val bar = FunctionDecl(CIName("bar"), List(CIName("a")), sqlE":a + 2")
      val mymodule    = rootDir </> dir("mymodule")
      val retrieve: ADir => List[FunctionDecl[Fix[Sql]]] = {
        case `mymodule` => List(foo, bar)
        case _          => Nil
      }
      resolveImports(scopedExpr, rootDir, retrieve) must_=== sqlE"(1 + 1) + (2 + 2)".right
    }
    "multiple functions within one import and unused imports" >> {
      val scopedExpr = sqlB"import `/mymodule/`; import `/othermodule/`; FOO(1) + Bar(2)"
      val foo = FunctionDecl(CIName("foo"), List(CIName("a")), sqlE":a + 1")
      val bar = FunctionDecl(CIName("bar"), List(CIName("a")), sqlE":a + 2")
      val mymodule    = rootDir </> dir("mymodule")
      val otherModule = rootDir </> dir("othermodule")
      val retrieve: ADir => List[FunctionDecl[Fix[Sql]]] = {
        case `mymodule`    => List(foo, bar)
        case `otherModule` => Nil
        case _             => Nil
      }
      resolveImports(scopedExpr, rootDir, retrieve) must_=== sqlE"(1 + 1) + (2 + 2)".right
    }
    "a function that depends on another" >> {
      val scopedExpr = sqlB"""
        CREATE FUNCTION FOO(:a)
          BEGIN
            tmp := Bar(:a);
            SELECT * FROM tmp
          END;
        CREATE FUNCTION BAR(:a)
          BEGIN
            select * from :a
          END;
        FOO(`/people`)
      """
      resolveImports(scopedExpr, rootDir, κ(Nil)) must_=== sqlE"tmp := select * from `/people`; select * from tmp".right
    }
    "multiple use of the same var name" >> {
      val scopedExpr = sqlB"""
        CREATE FUNCTION FOO(:a)
          BEGIN
            COUNT(:a, :a, :a)
          End;
        FOO(1)
      """
      resolveImports(scopedExpr, rootDir, κ(Nil)) must_=== sqlE"COUNT(1,1,1)".right
    }
    "multiple arguments" >> {
      val scopedExpr = sqlB"""
        CREATE FUNCTION FOO(:a, :b, :c)
          BEGIN
            COUNT(:a, :b, :c)
          End;
        FOO(1,2,3)
      """
      resolveImports(scopedExpr, rootDir, κ(Nil)) must_=== sqlE"COUNT(1,2,3)".right
    }
    "error out if same function defined twice" >> {
      val scopedExpr = sqlB"""
        CREATE FUNCTION FOO(:a)
          BEGIN
            COUNT(:a)
          End;
        CREATE FUNCTION FOO(:a)
          BEGIN
            FLOOR(:a)
          END;
        FOO(1)
      """
      // This error is slightly bizarre because they are both defined in the same scope, but I don't think it's
      // worth creating a special error type for this particular case
      // Besides, this is the kind of thing that should be caught when compiling to LogicalPlan once
      // importing resolution is done at that layer
      resolveImports(scopedExpr, rootDir, κ(Nil)) must_===
        AmbiguousFunctionInvoke(CIName("foo"), List((CIName("foo"), rootDir), (CIName("foo"), rootDir))).left
    }
    // This should not be the kind of thing caught by import resolution,
    // but it's a stop gap solution until LogicalPlan has user functions
    "error out if same var name appears multiple times in function signature" >> {
      val func = FunctionDecl(CIName("FOO"), List(CIName("a"), CIName("a")), sqlE"1")
      val scopedExpr = ScopedExpr(sqlE"FOO(1,2)", List(func))
      resolveImports(scopedExpr, rootDir, κ(Nil)) must_===
        InvalidFunctionDefinition(func, "parameter :a is defined multiple times").left
    }
  }

  "apply arguments to a function declaration" >> {
    "in the general case" in {
      val funcDef = FunctionDecl(CIName("foo"), args = List(CIName("bar")), body = sqlE"SELECT * from `/person` where :bar")
      ResolveImports.applyFunction(funcDef, List(sqlE"age > 10")) must_=== sqlE"SELECT * from `/person` where age > 10".right
    }

    "if the variable is inside of a `from`" in {
      val funcDef = FunctionDecl(CIName("foo"), args = List(CIName("bar")), body = sqlE"SELECT * from :bar")
      ResolveImports.applyFunction(funcDef, List(sqlE"`/person`")) must_=== sqlE"SELECT * from `/person`".right
    }
  }
}
