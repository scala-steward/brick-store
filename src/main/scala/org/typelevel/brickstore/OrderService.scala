package org.typelevel.brickstore

import cats.data.NonEmptySet
import cats.effect.Concurrent
import cats.effect.Concurrent.ops._
import cats.implicits._
import cats.temp.par._
import io.scalaland.chimney.dsl._
import fs2._
import fs2.async.mutable.Topic
import org.typelevel.brickstore.cart.{CartLine, CartService}
import org.typelevel.brickstore.dto.OrderSummary
import org.typelevel.brickstore.entity.{OrderId, OrderLine, UserId}

import scala.collection.immutable.SortedSet

trait OrderService[F[_]] {
  val streamExisting: Stream[F, OrderSummary]

  def placeOrder(auth: UserId): F[Option[OrderId]]
}

class OrderServiceImpl[F[_]: Concurrent: Par](cartService: CartService[F],
                                              orderRepository: OrderRepository[F],
                                              newOrderTopic: Topic[F, OrderSummary])
    extends OrderService[F] {

  override val streamExisting: Stream[F, OrderSummary] = orderRepository.streamExisting

  override def placeOrder(auth: UserId): F[Option[OrderId]] = {
    val cartBricks = cartService.findLines(auth)

    cartBricks.map(_.to[SortedSet]).flatMap {
      _.toNes.traverse(saveOrder(_)(auth))
    }
  }

  private def saveOrder(cartLines: NonEmptySet[CartLine])(auth: UserId): F[OrderId] = {
    def publishSummary(orderId: OrderId): F[Unit] = {
      orderRepository.getSummary(orderId).flatMap(_.traverse_(newOrderTopic.publish1))
    }

    val createOrder: F[OrderId] =
      orderRepository
        .createOrder(auth)
        .flatTap(orderId => cartLines.toNonEmptyList.traverse(saveLine(orderId, _)))

    val clearCart = cartService.clear(auth)

    createOrder.flatTap {
      clearCart *> publishSummary(_).start.void
    }
  }

  private def saveLine(orderId: OrderId, line: CartLine): F[Unit] = {
    val orderLine = createOrderLine(orderId)(line)
    orderRepository.addOrderLine(orderId, orderLine)
  }

  private def createOrderLine(orderId: OrderId): CartLine => OrderLine =
    _.into[OrderLine].withFieldConst(_.orderId, orderId).transform
}
