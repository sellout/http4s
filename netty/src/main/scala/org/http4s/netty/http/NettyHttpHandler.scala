package org.http4s
package netty
package http

import io.netty.handler.codec.http._
import io.netty.handler.codec.http.HttpHeaders._
import io.netty.channel._
import io.netty.handler.ssl.SslHandler
import io.netty.buffer.{ByteBuf, Unpooled}

import scalaz.concurrent.Task
import Process._

import scala.collection.mutable.ListBuffer
import java.net.{InetSocketAddress, URI}
import org.http4s._
import io.netty.util.ReferenceCountUtil
import org.http4s.netty.utils.ChunkHandler
import org.http4s.netty.{ProcessWriter, NettySupport}
import org.http4s.netty.NettySupport._
import org.http4s.Response
import org.http4s.TrailerChunk
import java.util.concurrent.atomic.AtomicReference
import org.http4s.Response
import org.http4s.TrailerChunk
import scala.concurrent.{ExecutionContext, Future}
import java.util.concurrent.Executor


/**
 * @author Bryce Anderson
 *         Created on 11/28/13
 */
class NettyHttpHandler(val service: HttpService,
                       val localAddress: InetSocketAddress,
                       val remoteAddress: InetSocketAddress,
                       executor: Executor)
              extends NettySupport[HttpObject, HttpRequest] with ProcessWriter {

  import NettySupport._
  import NettyHttpHandler._

  private var _ctx: ChannelHandlerContext = null

  private def ctx = _ctx

  protected val ec = ExecutionContext.fromExecutor(executor)

  private var manager: ChannelManager = null

  val serverSoftware = ServerSoftware("HTTP4S / Netty / HTTP")

  private val requestQueue = new AtomicReference[ReqState](Idle)

  def onHttpMessage(ctx: ChannelHandlerContext, msg: AnyRef): Unit = msg match {
    case req: HttpRequest =>
      logger.trace("Netty request received")
      requestQueue.getAndSet(Pending(req)) match {
        case Idle =>
          requestQueue.set(Running)
          runHttpRequest(ctx, req)

        case Running => // Nothing to do

        case Pending(_) => // This is an invalid state
          ctx.fireExceptionCaught(new InvalidStateException(s"Received request with a pending request in queue"))
      }

    case c: LastHttpContent =>
      if (manager != null) {
        if (c.content().readableBytes() > 0)
          manager.enque(buffToBodyChunk(c.content))

        manager.close(TrailerChunk(toHeaders(c.trailingHeaders())))
        manager = null
      }
      else logger.trace("Received LastHttpContent but manager is null. Discarding.")

    case chunk: HttpContent =>
      logger.trace("Netty content received.")
      if (manager != null) manager.enque(buffToBodyChunk(chunk.content))
      else logger.trace("Received HttpContent but manager is null. Discarding.")

    case msg =>
      ReferenceCountUtil.retain(msg)   // Done know what it is, fire upstream
      ctx.fireChannelRead(msg)
  }

  final override def handlerAdded(ctx: ChannelHandlerContext) {
    this._ctx = ctx
  }

  override protected def writeBodyChunk(chunk: BodyChunk, flush: Boolean): Future[Channel] = {
    if (!ctx.channel().isOpen) {
      logger.trace(s"Channel closed. -- $chunk")
      return Future.failed(Cancelled)
    }
    if (chunk.length > 0) {
      val msg =  new DefaultHttpContent(chunkToBuff(chunk))
      if (flush) ctx.writeAndFlush(msg)
      else ctx.write(msg)
    }
    else Future.successful(ctx.channel())
  }

  override protected def writeEnd(chunk: BodyChunk, t: Option[TrailerChunk]): Future[Channel] = {
    if (!ctx.channel().isOpen) {
      logger.trace(s"Channel closed. -- $chunk $t")
      return Future.failed(Cancelled)
    }

    val msg = new DefaultLastHttpContent(chunkToBuff(chunk))
    if (t.isDefined)
      for ( h <- t.get.headers ) msg.trailingHeaders().set(h.name.toString, h.value)

    ctx.writeAndFlush(msg)
  }

  override protected def renderResponse(ctx: ChannelHandlerContext, req: HttpRequest, response: Response): Task[Unit] = {
    logger.trace("Rendering response.")

    val stat = new HttpResponseStatus(response.status.code, response.status.reason)

    val length = response.headers.get(Header.`Content-Length`).map(_.length)
    val isHttp10 = req.getProtocolVersion == HttpVersion.HTTP_1_0

    val headers = new ListBuffer[(String, String)]

    val closeOnFinish = if (isHttp10) {
      if (length.isEmpty && isKeepAlive(req)) {
        headers += ((Names.CONNECTION, Values.CLOSE))
        true
      } else if(isKeepAlive(req)) {
        headers += ((Names.CONNECTION, Values.KEEP_ALIVE))
        false
      } else true
    } else if (Values.CLOSE.equalsIgnoreCase(req.headers.get(Names.CONNECTION))){  // Http 1.1+
      headers += ((Names.CONNECTION, Values.CLOSE))
      true
    } else false

    if(!isHttp10 && length.isEmpty) headers += ((Names.TRANSFER_ENCODING, Values.CHUNKED))

    val msg = new DefaultHttpResponse(req.getProtocolVersion, stat)
    headers.foreach { case (k, v) => msg.headers.set(k,v) }
    response.headers.foreach(h => msg.headers().set(h.name.toString, h.value))
    if (length.isEmpty) ctx.writeAndFlush(msg)
    else ctx.write(msg)

    writeProcess(response.body).map { _ =>
      val next = requestQueue.getAndSet(Idle)
      if (closeOnFinish) {
        if (next.isInstanceOf[Pending])
          logger.warn(s"Received pending request, but channel set to close. Request: $next")
        ctx.close()
      } else next match {
        case Pending(req) =>
          requestQueue.set(Running)
          runHttpRequest(ctx, req)

        case _ => // Nothing to do
      }
    }
  }

  override def toRequest(ctx: ChannelHandlerContext, req: HttpRequest): Request = {

    if(manager != null) invalidState(s"Chunk manager still present. Is a previous request still underway? $manager")

    manager = new ChannelManager(ctx)

    val scheme = if (ctx.pipeline.get(classOf[SslHandler]) != null) "http" else "https"
    logger.trace("Received request: " + req.getUri)
    val uri = new URI(req.getUri)

    val servAddr = localAddress
    Request(
      requestMethod = Method(req.getMethod.name),
      //scriptName = contextPath,
      pathInfo = uri.getRawPath,
      queryString = uri.getRawQuery,
      protocol = ServerProtocol.resolve(req.getProtocolVersion.protocolName),
      headers = toHeaders(req.headers),
      urlScheme = HttpUrlScheme(scheme),
      serverName = servAddr.getHostName,
      serverPort = servAddr.getPort,
      serverSoftware = serverSoftware,
      remote = remoteAddress.getAddress,
      body = makeProcess(manager)
    )
  }

  /** Manages the input stream providing back pressure
    * @param ctx ChannelHandlerContext of the channel
    */          // TODO: allow control of buffer size and use bytes, not chunks as limit
  protected class ChannelManager(ctx: ChannelHandlerContext) extends ChunkHandler(10*1024*1024) { // 10MB
    override def onQueueFull() {
      logger.trace("Queue full.")
      assert(ctx != null)
      disableRead()
    }

    override def onBytesSent(n: Int) {
      logger.trace(s"Sent $n bytes. Queue ready.")
      assert(ctx != null)
      enableRead()
    }

    private def disableRead() {
      ctx.channel().config().setOption(ChannelOption.AUTO_READ, new java.lang.Boolean(false))
    }

    private def enableRead() {
      ctx.channel().config().setOption(ChannelOption.AUTO_READ, new java.lang.Boolean(true))
    }
  }
}

object NettyHttpHandler {
  private sealed trait ReqState
  private case object Idle extends ReqState
  private case object Running extends ReqState
  private case class Pending(req: HttpRequest) extends ReqState
}