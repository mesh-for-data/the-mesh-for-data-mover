/**
  * (C) Copyright IBM Corporation 2020.
  *
  * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
  * the License. You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
  * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
  * specific language governing permissions and limitations under the License.
  */
package com.ibm.datamesh.mover.transformation

import com.ibm.datamesh.mover.datastore.kafka.KafkaUtils
import com.ibm.datamesh.mover.spark.SparkTest
import com.ibm.datamesh.mover.spark._
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import org.apache.commons.codec.digest.DigestUtils
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

/**
  * Tests the different behavior of transformation operations on log data and change data.
  */
class TransformationSuite extends AnyFlatSpec with Matchers with SparkTest {
  it should "remove a column in log data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClass(1, "a", 1.0),
        MyClass(2, "b", 2.0),
        MyClass(3, "c", 3.0)
      ))

      val transformation = RemoveColumnTransformation("n", Seq("i"), ConfigFactory.empty(), ConfigFactory.empty())

      val transformedDF = transformation.transformLogData(df)

      transformedDF.schema.fieldNames shouldBe Array("s", "d")
      transformedDF.count() shouldBe 3
    }
  }

  it should "remove a column in a change data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClassKV(MyClassKey(1), MyClass(1, "a", 1.0)),
        MyClassKV(MyClassKey(2), MyClass(2, "b", 2.0)),
        MyClassKV(MyClassKey(3), MyClass(3, "c", 3.0))
      ))

      val transformation = RemoveColumnTransformation("n", Seq("s"), ConfigFactory.empty(), ConfigFactory.empty())

      val transformedDF = transformation.transformChangeData(df)

      transformedDF.schema.fieldNames should have size 2
      transformedDF.schema.fieldNames should contain theSameElementsAs Seq("key", "value")
      val keyStruct = transformedDF.schema.fieldAsStructType("key")
      val valueStruct = transformedDF.schema.fieldAsStructType("value")
      keyStruct.fieldNames shouldBe Array("i")
      valueStruct.fieldNames shouldBe Array("i", "d")
      transformedDF.count() shouldBe 3
    }
  }

  it should "filter rows in log data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClass(1, "a", 1.0),
        MyClass(2, "b", 2.0),
        MyClass(3, "c", 3.0)
      ))

      val transformation = FilterRowsTransformation("n", ConfigFactory.empty().withValue("clause", ConfigValueFactory.fromAnyRef("i == 1")), ConfigFactory.empty())

      val transformedDF = transformation.transformLogData(df)

      transformedDF.count() shouldBe 1
    }
  }

  it should "hash column in md5 in log data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClass(1, "a", 1.0),
        MyClass(2, "b", 2.0),
        MyClass(3, "c", 3.0)
      ))

      val transformation = DigestColumnsTransformation("n", Seq("s"), ConfigFactory.empty(), ConfigFactory.empty())

      val transformedDF = transformation.transformLogData(df)

      import spark.implicits._

      transformedDF.as[MyClass].collect() shouldBe Array(
        MyClass(1, DigestUtils.md5Hex("a"), 1.0),
        MyClass(2, DigestUtils.md5Hex("b"), 2.0),
        MyClass(3, DigestUtils.md5Hex("c"), 3.0)
      )
    }
  }

  it should "redact a column in log data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClass(1, "a", 1.0),
        MyClass(2, "b", 2.0),
        MyClass(3, "c", 3.0)
      ))

      val transformation = RedactColumnsTransformation("n", Seq("s"), ConfigFactory.empty(), ConfigFactory.empty())

      val transformedDF = transformation.transformLogData(df)

      import spark.implicits._

      transformedDF.as[MyClass].collect() shouldBe Array(
        MyClass(1, "XXXXXXXXXX", 1.0),
        MyClass(2, "XXXXXXXXXX", 2.0),
        MyClass(3, "XXXXXXXXXX", 3.0)
      )
    }
  }

  it should "redact a column in a change data" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClassKV(MyClassKey(1), MyClass(1, "a", 1.0)),
        MyClassKV(MyClassKey(2), MyClass(2, "b", 2.0)),
        MyClassKV(MyClassKey(3), MyClass(3, "c", 3.0))
      ))

      val transformation = RedactColumnsTransformation("n", Seq("s"), ConfigFactory.empty(), ConfigFactory.empty())

      val transformedDF = transformation.transformChangeData(df)
      import spark.implicits._

      transformedDF.schema.fieldNames should have size 2
      transformedDF.schema.fieldNames should contain theSameElementsAs Seq("key", "value")
      val keyStruct = transformedDF.schema.fieldAsStructType("key")
      val valueStruct = transformedDF.schema.fieldAsStructType("value")
      keyStruct.fieldNames shouldBe Array("i")
      valueStruct.fieldNames shouldBe Array("i", "s", "d")
      transformedDF.count() shouldBe 3
      KafkaUtils.mapToValue(transformedDF).as[MyClass].collect() shouldBe Array(
        MyClass(1, "XXXXXXXXXX", 1.0),
        MyClass(2, "XXXXXXXXXX", 2.0),
        MyClass(3, "XXXXXXXXXX", 3.0)
      )
    }
  }

  // This test is flaky and thus ignored.
  ignore should "sample a fraction of the dataset" in {
    withSparkSession { spark =>
      val df = spark.createDataFrame(Seq(
        MyClass(1, "a", 1.0),
        MyClass(2, "b", 2.0),
        MyClass(3, "c", 3.0),
        MyClass(4, "d", 4.0),
        MyClass(5, "e", 5.0),
        MyClass(6, "f", 6.0),
        MyClass(7, "g", 7.0),
        MyClass(8, "h", 8.0),
        MyClass(9, "i", 9.0),
        MyClass(10, "j", 10.0)
      ))

      val transformation = SampleRowsTransformation("n", ConfigFactory.empty().withValue("fraction", ConfigValueFactory.fromAnyRef(0.2)), ConfigFactory.empty())

      val transformedDF = transformation.transformLogData(df)

      val n = transformedDF.count()
      (n >= 0 && n < 5) shouldBe true // Spark sample is not always accurate. Give test some room to succeed.
    }
  }

  it should "merge transformations correctly" in {
    val s =
      """
        |transformation = [
        |{
        |  name = "n"
        |  action = "RemoveColumn"
        |  columns = ["c1"]
        |},
        |{
        |  name = "n2"
        |  action = "RemoveColumn"
        |  columns = ["c2"]
        |}
        |,
        |{
        |  name = "n"
        |  action = "RedactColumn"
        |  columns = ["c3"]
        |}]""".stripMargin

    val c = ConfigFactory.parseString(s)
    val ts = Transformation.loadTransformations(c)

    ts should have size 2
    ts(0) shouldBe a[RemoveColumnTransformation]
    ts(1) shouldBe a[RedactColumnsTransformation]

    ts(0).asInstanceOf[RemoveColumnTransformation].columns should contain theSameElementsAs Seq("c1", "c2")

    val seq = Seq(
      RemoveColumnTransformation("n", Seq("c1"), ConfigFactory.empty(), ConfigFactory.empty()),
      RemoveColumnTransformation("n", Seq("c2"), ConfigFactory.empty(), ConfigFactory.empty())
    )
    val ts2 = Transformation.merge(seq)

    ts2 should have size 1
  }
}

case class MyClass(i: Integer, s: String, d: Double)
case class MyClassKey(i: Integer)
case class MyClassKV(key: MyClassKey, value: MyClass)
