package coop.rchain.comm

import cats.MonadError
import cats.effect.Concurrent
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import coop.rchain.catscontrib.ski.kp
import coop.rchain.comm.transport.{TcpTransportLayer, TransportState}
import coop.rchain.shared.Cell
import io.grpc.ManagedChannel

import scala.language.higherKinds

class CachedConnections[F[_]](
    clientChannel: PeerNode => F[ManagedChannel],
    val cell: TcpTransportLayer.TransportCell[F]
)(implicit E: MonadError[F, Throwable]) {

  def connection(peer: PeerNode, enforce: Boolean): F[ManagedChannel] =
    cell.modify { s =>
      if (s.shutdown && !enforce)
        E.raiseError(new RuntimeException("The transport layer has been shut down")).as(s)
      else
        for {
          c <- s.connections.get(peer).fold(clientChannel(peer))(_.pure[F])
        } yield s.copy(connections = s.connections + (peer -> c))
    } >>= kp(cell.read.map(_.connections.apply(peer)))
}

object CachedConnections {
  type ConnectionsCache[F[_]] = (PeerNode => F[ManagedChannel]) => CachedConnections[F]

  def apply[F[_]: Concurrent]: F[ConnectionsCache[F]] =
    for {
      connections <- Cell.mvarCell[F, TransportState](TransportState.empty)
    } yield new CachedConnections[F](_, connections)
}
