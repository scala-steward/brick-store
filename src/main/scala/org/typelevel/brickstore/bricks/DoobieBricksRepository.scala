package org.typelevel.brickstore.bricks

import cats.effect.Bracket
import doobie.free.connection.ConnectionIO
import doobie.implicits._
import doobie.util.transactor.Transactor

final class DoobieBricksRepository[F[_]: Bracket[?[_], Throwable]](implicit xa: Transactor[F])
    extends BricksRepository[F, ConnectionIO] {

  override val findBrickIds: fs2.Stream[F, BrickId] = sql"""select id from bricks""".query[BrickId].stream.transact(xa)

  override def insert(brick: Brick): F[BrickId] = {
    val query =
      sql"""insert into bricks(name, price, color)
            values(${brick.name}, ${brick.price}, ${brick.color})"""

    query.update.withUniqueGeneratedKeys[BrickId]("id").transact(xa)
  }

  override def findById(id: BrickId): F[Option[Brick]] = {
    sql"""select name, price, color from bricks where id = $id""".query[Brick].option.transact(xa)
  }
}
