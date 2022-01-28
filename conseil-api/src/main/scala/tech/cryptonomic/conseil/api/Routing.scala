package tech.cryptonomic.conseil.api

import cats.effect.IO
import cats.syntax.option._
import org.http4s.HttpApp
import sttp.model.StatusCode
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.{Http4sServerInterpreter, Http4sServerOptions}
import sttp.tapir.server.interceptor.ValuedEndpointOutput
import sttp.tapir.statusCode
import sttp.tapir.server.interceptor.exception.ExceptionContext

object Routing {

  import tech.cryptonomic.conseil.api.info.model._
  import tech.cryptonomic.conseil.api.info.converters._

  // def instance[F[_]: Async]: HttpApp[F] =
  def instance(api: ConseilApi): HttpApp[IO] =
    Http4sServerInterpreter[IO](
      Http4sServerOptions
        .customInterceptors[IO, IO]
        .exceptionHandler { whoami: ExceptionContext =>
          ValuedEndpointOutput(
            jsonBody[GenericServerError].and(statusCode(StatusCode.InternalServerError)),
            GenericServerError("serverResource failed")
          ).some
        }
        .options
    ).toRoutes(api.route).orNotFound

}