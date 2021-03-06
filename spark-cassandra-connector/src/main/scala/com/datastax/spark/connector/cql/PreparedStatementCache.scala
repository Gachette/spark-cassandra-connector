package com.datastax.spark.connector.cql

import com.datastax.driver.core.{RegularStatement, Session, Cluster, PreparedStatement}
import com.datastax.spark.connector.util.Logging

import scala.collection.concurrent.TrieMap

/** Caches prepared statements so they are not prepared
  * multiple times by different threads. */
object PreparedStatementCache extends Logging {

  private val clusterCache = 
    TrieMap[Cluster, TrieMap[String, PreparedStatement]]()

  private def get(cluster: Cluster, query: String): Option[PreparedStatement] =
    for (statementCache <- clusterCache.get(cluster);
         statement <- statementCache.get(query)) yield statement

  private def put(cluster: Cluster, query: String, statement: PreparedStatement): PreparedStatement = {
    clusterCache.get(cluster) match {
      case Some(statementCache) => statementCache.put(query, statement)
      case None => clusterCache.put(cluster, TrieMap(query -> statement))
    }
    statement
  }

  private[datastax] def getSize(cluster: Cluster): Int = {
    clusterCache.get(cluster).size
  }

  /** Removes all statements associated with the `Cluster` from the cache. */
  def remove(cluster: Cluster) {
    synchronized {
      clusterCache.remove(cluster)
    }
  }

  /** Retrieves a `PreparedStatement` from cache or
    * creates a new one if not found and updates the cache. */
  def prepareStatement(session: Session, query: RegularStatement): PreparedStatement = {
    val cluster = session.getCluster
    get(cluster, query.toString) match {
      case Some(stmt) => stmt
      case None =>
        synchronized {
          get(cluster, query.toString) match {
            case Some(stmt) => stmt
            case None =>
              val stmt = session.prepare(query)
              put(cluster, query.toString, stmt)
          }
        }
    }
  }

}
