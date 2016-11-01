/*
 * Copyright 2014–2016 SlamData Inc.
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

package ygg.tests

import scalaz._, Scalaz._
import ygg._, common._, json._, table._, trans._
import org.specs2.matcher.TraversableMatchers._

class SampleSpec extends TableQspec {
  "in sample" >> {
     "sample from a dataset"                                in testSample
     "return no samples given empty sequence of transspecs" in testSampleEmpty
     "sample from a dataset given non-identity transspecs"  in testSampleTransSpecs
     "return full set when sample size larger than dataset" in testLargeSampleSize
     "resurn empty table when sample size is 0"             in test0SampleSize
  }

  private lazy val simpleData: Stream[JValue] = Stream.tabulate(100) { i =>
    JObject(JField("id", if (i % 2 == 0) JString(i.toString) else JNum(i)) :: Nil)
  }

  private lazy val simpleData2: Stream[JValue] = Stream.tabulate(100) { i =>
    JObject(
      JField("id", if (i      % 2 == 0) JString(i.toString) else JNum(i)) ::
        JField("value", if (i % 2 == 0) JBool(true) else JNum(i)) ::
          Nil)
  }

  private def testSample = {
    val data  = SampleData(simpleData)
    val table = fromSample(data)

    table.sample(15, Seq(TransSpec1.Id, TransSpec1.Id)).copoint.toList must beLike {
      case s1 :: s2 :: Nil =>
        val result1 = toJson(s1).copoint
        val result2 = toJson(s2).copoint
        result1 must have size (15)
        result2 must have size (15)
        simpleData must containAllOf(result1)
        simpleData must containAllOf(result2)
    }
  }

  private def testSampleEmpty = {
    val data  = SampleData(simpleData)
    val table = fromSample(data)
    table.sample(15, Seq()).copoint.toList mustEqual Nil
  }

  private def testSampleTransSpecs = {
    val data  = SampleData(simpleData2)
    val table = fromSample(data)
    val specs = Seq[TransSpec1](root.id, root.value)

    table.sample(15, specs).copoint.toList must beLike {
      case s1 :: s2 :: Nil =>
        val result1 = toJson(s1).copoint
        val result2 = toJson(s2).copoint
        result1 must have size (15)
        result2 must have size (15)

        val expected1 = toJson(table.transform(root.id)).copoint
        val expected2 = toJson(table.transform(root.value)).copoint
        expected1 must containAllOf(result1)
        expected2 must containAllOf(result2)
    }
  }

  private def testLargeSampleSize = {
    val data = SampleData(simpleData)
    fromSample(data).sample(1000, Seq(TransSpec1.Id)).copoint.toList must beLike {
      case s :: Nil =>
        val result = toJson(s).copoint
        result must have size (100)
    }
  }

  private def test0SampleSize = {
    val data = SampleData(simpleData)
    fromSample(data).sample(0, Seq(TransSpec1.Id)).copoint.toList must beLike {
      case s :: Nil =>
        val result = toJson(s).copoint
        result must have size (0)
    }
  }
}
