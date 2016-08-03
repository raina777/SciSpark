/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.dia.core

import scala.collection.mutable
import scala.io.Source

import org.apache.log4j.LogManager

import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.rdd.RDD

import org.dia.Constants._
import org.dia.loaders.MergReader._
import org.dia.loaders.NetCDFReader._
import org.dia.loaders.RandomMatrixReader._
import org.dia.partitioners.SPartitioner._
import org.dia.tensors.{AbstractTensor, BreezeTensor, Nd4jTensor}
import org.dia.utils.NetCDFUtils

/**
 * A SciSparkContext is a wrapper for the SparkContext.
 * SciSparkContext provides existing SparkContext functionality.
 * However there are many private functions within SparkContext
 * that are useful for catching unwanted calls. Such as
 * executing one of the functions after the SparkContext has been stopped.
 */
class SciSparkContext(@transient val sparkContext: SparkContext) {

  /**
   * Log4j Setup
   * By default the natty Parser log4j messages are turned OFF.
   */
  // scalastyle:off classforname
  val DateParserClass = Class.forName("com.joestelmach.natty.Parser")
  // scalastyle:on classforname
  val ParserLevel = org.apache.log4j.Level.OFF
  val logger = org.slf4j.LoggerFactory.getLogger(this.getClass)
  val r = scala.util.Random
  var HTTPCredentials: mutable.Seq[(String, String, String)] = mutable.Seq()
  LogManager.getLogger(DateParserClass).setLevel(ParserLevel)

  /**
   * SparkContext setup
   * The default matrix library is Scala Breeze
   */
  sparkContext.setLocalProperty(ARRAY_LIB, BREEZE_LIB)


  def this(conf: SparkConf) {
    this(new SparkContext(conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .set("spark.kryo.classesToRegister", "java.lang.Thread")
      .set("spark.kryoserializer.buffer.max", "256MB")))
  }

  def this(uri: String, name: String) {
    this(new SparkConf().setMaster(uri).setAppName(name))
  }

  def this(uri: String, name: String, parser: (String) => (String)) {
    this(new SparkConf().setMaster(uri).setAppName(parser(name)))
  }

  def setLocalProperty(key: String, value: String): Unit = {
    sparkContext.setLocalProperty(key, value)
  }

  def getConf: SparkConf = sparkContext.getConf

  /**
   * Adds http credentials which is then registered by any function
   * using ucar's httpservices. Namely, if you are reading any opendap file
   * that requires you to enter authentication credentials in the browser.
   * Some datasets (like those hosted by gesdisc) require http credentials
   * for authentiation and access.
   *
   * @param uri
   * @param username
   * @param password
   */
  def addHTTPCredential(uri: String, username: String, password: String): Unit = {
    HTTPCredentials = HTTPCredentials.+:((uri, username, password))
  }


  /**
   * Constructs an SRDD from a file of URI's pointing to NetCDF datasets and a list of variable names.
   * If no names are provided then all variable arrays in the file are loaded.
   * The URI could be an OpenDapURL or a filesystem path.
   *
   * For reading from HDFS check NetcdfDFSFile.
   */
  def NetcdfFile(path: String,
                 varName: List[String] = Nil,
                 minPartitions: Int = 2): RDD[SciTensor] = {

    val URIsFile = sparkContext.textFile(path, minPartitions)
    val creds = HTTPCredentials.clone()
    val rdd = URIsFile.map(p => {
      for ((uri, username, password) <- creds) NetCDFUtils.setHTTPAuthentication(uri, username, password)
      val variableHashTable = new mutable.HashMap[String, AbstractTensor]
      // note that split, if it doesn't find the token, will result in just an empty string
      var uriVars = p.split("\\?").drop(1).mkString.split(",").filter(p => p != "")
      val mainURL = p.split("\\?").take(1).mkString + "?"
      var varDapPart = ""

      if (uriVars.length > 0) {
        varName.foreach(y => {
          uriVars.foreach(i => {
            if (i.contains(y)) varDapPart = i
          })
          if (varDapPart != "") {
            // We forced Thread to be serializable when the sparkContext was initalized
            // but this isnt working. So need to find another way to do this.
            // Thread.sleep((r.nextFloat * 10.0).toLong)
            val arrayandShape = loadNetCDFNDVar(mainURL + varDapPart, y)
            val absT = new Nd4jTensor(arrayandShape)
            variableHashTable += ((y, absT))
          } else {
            println(y + " is not available from the URL")
          }
        })
      } else {
        varName.foreach(y => {
          val arrayandShape = loadNetCDFNDVar(p, y)
          val absT = new Nd4jTensor(arrayandShape)
          variableHashTable += ((y, absT))
        })
      }
      val sciT = new SciTensor(variableHashTable)
      sciT.insertDictionary(("SOURCE", p))
      sciT
    })
    rdd
  }

  /**
   * Constructs an RDD given a URI pointing to an HDFS directory of Netcdf files and a list of variable names.
   * Note that since the files are read from HDFS, the binaryFiles function is used which is called
   * from SparkContext. This is why a normal RDD is returned instead of an SRDD.
   *
   * TODO :: Create an SRDD instead of a normal RDD
   */
  def NetcdfDFSFile(path: String,
                    varName: List[String] = Nil,
                    minPartitions: Int = 2): RDD[SciTensor] = {

    val textFiles = sparkContext.binaryFiles(path, minPartitions)
    val rdd = textFiles.map(p => {
      val byteArray = p._2.toArray()
      val dataset = loadNetCDFFile(p._1, byteArray)
      val variableHashTable = new mutable.LinkedHashMap[String, AbstractTensor]

      varName.foreach(y => {
        val arrayandShape = loadNetCDFNDVar(dataset, y)
        val absT = new Nd4jTensor(arrayandShape)
        variableHashTable += ((y, absT))
      })
      val sciT = new SciTensor(variableHashTable)
      sciT.insertDictionary(("SOURCE", p._1))
      sciT
    })
    rdd
  }


  /**
   * Constructs a random SRDD from a file of URI's, a list of variable names, and matrix dimensions.
   * The seed for matrix values is the path values, so the same input set will yield the same data.
   *
   */
  def randomMatrices(path: String,
                     varName: List[String] = Nil,
                     matrixSize: (Int, Int),
                     minPartitions: Int = 2): SRDD[SciTensor] = {

    val URIs = Source.fromFile(path).mkString.split("\n").toList
    val partitionSize = if (URIs.size > minPartitions) (URIs.size + minPartitions) / minPartitions else 1
    val variables: List[String] = varName

    new SRDD[SciTensor](sparkContext, URIs, variables, loadRandomArray(matrixSize), mapNUri(partitionSize))
  }

  /**
   * Constructs an SRDD given a file of URI's pointing to MERG files, a list of variables names,
   * and matrix dimensions. By default the variable name is set to TMP, the dimensions are 9896 x 3298
   * and the value offset is 75.
   */
  def mergeFile(path: String,
                varName: List[String] = List("TMP"),
                shape: Array[Int] = Array(9896, 3298),
                offset: Double = 75,
                minPartitions: Int = 2): SRDD[SciTensor] = {

    val URIs = Source.fromFile(path).mkString.split("\n").toList
    val partitionSize = if (URIs.size > minPartitions) (URIs.size + minPartitions) / minPartitions else 1

    new SRDD[SciTensor](sparkContext, URIs, varName, loadMERGArray(shape, offset), mapNUri(partitionSize))
  }

  /**
   * Constructs an RDD given URI pointing to an HDFS directory of MERG files, a list of variable names,
   * and matrix dimensions. By default the variable name is set to TMP, the dimensions are 9896 x 3298
   * and the value offset is 75.
   *
   * Note that since the files are read from HDFS, the binaryFiles function is used which is called
   * from SparkContext. This is why a normal RDD is returned instead of an SRDD.
   *
   * TODO :: Create an SRDD instead of a normal RDD
   */
  def mergDFSFile(path: String,
                  varName: List[String] = List("TMP"),
                  offset: Double = 75,
                  shape: Array[Int] = Array(9896, 3298),
                  minPartitions: Int = 2): RDD[SciTensor] = {

    val textFiles = sparkContext.binaryFiles(path, minPartitions)
    val rdd = textFiles.map(p => {
      val byteArray = p._2.toArray()
      val doubleArray = readMergBytetoJavaArray(byteArray, offset, shape)
      val absT = new BreezeTensor(doubleArray, shape)
      val sciT = new SciTensor("TMP", absT)
      sciT.insertDictionary(("SOURCE", p._1))
      sciT
    })
    rdd
  }

  /**
   * Constructs an SRDD given a nested directories of NetCDF files.
   */
  def OpenPath(path: String, varName: List[String] = Nil): SRDD[SciTensor] = {
    val datasetPaths = List(path)
    new SRDD[SciTensor](sparkContext, datasetPaths, varName, loadNetCDFNDVar, mapSubFoldersToFolders)
  }

}
