package rendezvous.backend

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Map

class Ranking(taskId: TaskID, hash: Hash, initNodes: List[NodeID]):

  private val scores: Map[NodeID, Int] = Map.from(initNodes.map(nodeId => nodeId -> hashImpl(nodeId)))

  private val nodeIds: ArrayBuffer[NodeID] = ArrayBuffer.from(initNodes).sortBy(scores(_))

  private def hashImpl(nodeId: NodeID): Int = hash.hash(s"$taskId${nodeId}")

  def bestNode: Option[NodeID] = nodeIds.lastOption

  def secondBestNode: Option[NodeID] = Option.when(nodeIds.size >= 2)(nodeIds(nodeIds.size - 2))

  def addNode(nodeId: NodeID): Ranking =
    val score = hashImpl(nodeId)
    scores += nodeId -> score
    val idx = nodeIds.indexWhere(uuid => scores(uuid) > score)
    if idx == -1 then nodeIds += nodeId
    else nodeIds.insert(idx, nodeId)
    this

  def removeNode(nodeId: NodeID): Ranking =
    nodeIds.filterInPlace(_ != nodeId)
    scores -= nodeId
    this

  def nodes: List[NodeID] = nodeIds.toList
