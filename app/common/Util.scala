/*
 * Copyright 2014 Claude Mamo
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package common

import scala.concurrent.{Future, Promise}
import com.twitter.util.{Throw, Return}
import com.twitter.zk.{ZNode, ZkClient}
import common.Registry.PropertyConstants
import models.Zookeeper
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import org.apache.zookeeper.KeeperException.{NotEmptyException, NodeExistsException, NoNodeException}
import okapies.finagle.Kafka
import kafka.api.OffsetRequest

object Util {

  def twitterToScalaFuture[A](twitterFuture: com.twitter.util.Future[A]): Future[A] = {
    val promise = Promise[A]()
    twitterFuture respond {
      case Return(a) => promise success a
      case Throw(e) => promise failure e
    }
    promise.future
  }

  def getPartitionLeaders(topicName: String, zkClient: ZkClient): Future[Seq[String]] = {
    return for {
      partitionStates <- getZChildren(zkClient, "/brokers/topics/" + topicName + "/partitions/*/state")
      partitionsData <- Future.sequence(partitionStates.map(p => twitterToScalaFuture(p.getData().map(d => (p.path.split("/")(5), new String(d.bytes))))))
      brokerIds = partitionsData.map(d => (d._1, scala.util.parsing.json.JSON.parseFull(d._2).get.asInstanceOf[Map[String, Any]].get("leader").get))
      brokers <- Future.sequence(brokerIds.map(bid => getZChildren(zkClient, "/brokers/ids/" + bid._2.toString.toDouble.toInt).map((bid._1, _))))
      brokersData <- Future.sequence(brokers.map(d => twitterToScalaFuture(d._2.head.getData().map((d._1, _)))))
      brokersInfo = brokersData.map(d => (d._1, scala.util.parsing.json.JSON.parseFull(new String(d._2.bytes)).get.asInstanceOf[Map[String, Any]]))
      bb = brokersInfo.map(bi => (bi._1, bi._2.get("host").get + ":" + bi._2.get("port").get.toString.toDouble.toInt))
    } yield bb.sortBy(p => p._1.toInt).map(p => p._2)
  }

  def getPartitionsLogSize(topicName: String, partitionLeaders: Seq[String]): Future[Seq[Long]] = {
    return for {
      clients <- Future.sequence(partitionLeaders.map(addr => Future(Kafka.newRichClient(addr))))
      partitionsLogSize <- Future.sequence(clients.zipWithIndex.map { e =>
        val offset = twitterToScalaFuture(e._1.offset(topicName, e._2, OffsetRequest.LatestTime)).map(_.offsets.head)
        e._1.close()
        offset
      })
    } yield partitionsLogSize
  }

  def getPartitionOffsets(topicName: String, zkClient: ZkClient): Future[Map[String, Seq[Long]]] = {
    return for {
      offsetsPartitionsNodes <- getZChildren(zkClient, "/consumers/*/offsets/" + topicName + "/*")
      partitionOffsets <- Future.sequence(offsetsPartitionsNodes.map(p => twitterToScalaFuture(p.getData().map(d => (p.path.split("/")(2), p.name, new String(d.bytes).toLong)))))
      partitionOffsetsByConsumerGroup = partitionOffsets.groupBy(_._1).map(e1 => e1._1 -> e1._2.map(e2 => (e2._2, e2._3)))
      sortedPartitionOffsetsByConsumerGroup = partitionOffsetsByConsumerGroup.map(e => e._1 -> e._2.sortBy(p => p._1.toInt).map(p => p._2))
    } yield sortedPartitionOffsetsByConsumerGroup
  }

  def getTopics(zkClient: ZkClient): Future[Map[String, Seq[String]]] = {
    return for {
      allTopicNodes <- getZChildren(zkClient, "/brokers/topics/*")
      allTopics = allTopicNodes.map(p => (p.path.split("/").filter(_ != "")(2), Seq[String]())).toMap
      partitions <- getZChildren(zkClient, "/brokers/topics/*/partitions/*")
      topics = partitions.map(p => (p.path.split("/").filter(_ != "")(2), p.name)).groupBy(_._1).map(e => e._1 -> e._2.map(_._2))
    } yield topics
  }

  def connectedZookeepers[A](block: (Zookeeper, ZkClient) => A): Seq[A] = {
    val connectedZks = models.Zookeeper.findByStatusId(models.Status.Connected.id)

    val zkConnections: Map[String, ZkClient] = Registry.lookupObject(PropertyConstants.ZookeeperConnections) match {
      case Some(s: Map[_, _]) if connectedZks.size > 0 => s.asInstanceOf[Map[String, ZkClient]]
      case _ => Map()
    }

    zkConnections match {
      case _ if zkConnections.size > 0 => connectedZks.map(zk => block(zk, zkConnections.get(zk.name).get)).toSeq
      case _ => Seq.empty
    }

  }

  def getZChildren(zkClient: ZkClient, path: String): Future[Seq[ZNode]] = {
    val nodes = path.split('/').filter(_ != "").toSeq

    getZChildren(zkClient("/"), nodes)
  }

  def getZChildren(zNode: ZNode, path: Seq[String]): Future[Seq[ZNode]] = path match {

    case head +: tail if head == "*" => {

      val subtreesFuture = for {
        children <- twitterToScalaFuture(zNode.getChildren()).map(_.children).recover {
          case e: NoNodeException => Nil
        }
        subtrees <- Future.sequence(children.map(getZChildren(_, tail)))

      } yield subtrees

      subtreesFuture.map(_.flatten)
    }
    case head +: Nil => {
      twitterToScalaFuture(zNode(head).exists()).map(_ => Seq(zNode(head))).recover {
        case e: NoNodeException => Nil
      }
    }
    case head +: tail => getZChildren(zNode(head), tail)
    case Nil => Future(Seq(zNode))
  }

  def deleteZNode(zkClient: ZkClient, path: String): Future[ZNode] = {
    deleteZNode(zkClient(path))
  }

  def deleteZNode(zNode: ZNode): Future[ZNode] = {
    val delNode = twitterToScalaFuture(zNode.getData()).flatMap { d =>
      twitterToScalaFuture(zNode.delete(d.stat.getVersion)).recover {
        case e: NotEmptyException => {
          for {
            children <- getZChildren(zNode, Seq("*"))
            delChildren <- Future.sequence(children.map(n => deleteZNode(n)))
          } yield deleteZNode(zNode)
        }
        case e: NoNodeException => Future(ZNode)
      }
    }

    //TODO: investigate why actual type is Future[Object]
    delNode.asInstanceOf[Future[ZNode]]
  }
}
