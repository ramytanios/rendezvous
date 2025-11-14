package rendezvous.backend

import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.Map

class Ranking(taskId: UUID, hash: Hash, initNodes: List[UUID]):

  private val scores: Map[UUID, Int] = Map.from(initNodes.map(nodeId => nodeId -> hashImpl(nodeId)))

  private val nodeIds: ArrayBuffer[UUID] = ArrayBuffer.from(initNodes).sortBy(scores(_))

  private def hashImpl(nodeId: UUID): Int = hash.hash(s"$taskId${nodeId}")

  def bestNode: Option[UUID] = nodeIds.lastOption

  def secondBestNode: Option[UUID] = Option.when(nodeIds.size >= 2)(nodeIds(nodeIds.size - 2))

  def addNode(nodeId: UUID): Ranking =
    val score = hashImpl(nodeId)
    scores += nodeId -> score
    val idx = nodeIds.indexWhere(uuid => scores(uuid) > score)
    if idx == -1 then nodeIds += nodeId
    else nodeIds.insert(idx, nodeId)
    this

  def removeNode(nodeId: UUID): Ranking =
    nodeIds.filterInPlace(_ != nodeId)
    scores -= nodeId
    this

  def nodes: List[UUID] = nodeIds.toList
