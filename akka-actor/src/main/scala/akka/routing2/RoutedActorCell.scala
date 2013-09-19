/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.routing2

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorCell
import akka.actor.ActorInitializationException
import akka.actor.ActorSystemImpl
import akka.actor.AutoReceivedMessage
import akka.actor.IndirectActorProducer
import akka.actor.InternalActorRef
import akka.actor.Props
import akka.actor.Terminated
import akka.dispatch.Envelope
import akka.dispatch.MessageDispatcher
import akka.routing.RouterConfig
import akka.actor.ActorContext
import akka.actor.PoisonPill
import akka.actor.SupervisorStrategy

/**
 * INTERNAL API
 */
private[akka] object RoutedActorCell {
  class RouterActorCreator(routerConfig: RouterConfig) extends IndirectActorProducer {
    override def actorClass = classOf[RouterActor]
    override def produce() = routerConfig.createActor()
  }

}

/**
 * INTERNAL API
 */
private[akka] class RoutedActorCell(
  _system: ActorSystemImpl,
  _ref: InternalActorRef,
  _routerProps: Props,
  _routerDispatcher: MessageDispatcher,
  val routeeProps: Props,
  _supervisor: InternalActorRef)
  extends ActorCell(_system, _ref, _routerProps, _routerDispatcher, _supervisor) {

  // FIXME #3549 remove cast
  private[akka] val routerConfig = _routerProps.routerConfig.asInstanceOf[RouterConfig2]

  @volatile
  private var _router: Router = null // initialized in start, and then only updated from the actor
  def router: Router = _router

  def addRoutee(routee: Routee): Unit =
    addRoutees(List(routee))

  def addRoutees(routees: immutable.Iterable[Routee]): Unit = {
    routees foreach watch
    val r = _router
    _router = r.withRoutees(r.routees ++ routees)
  }

  def removeRoutee(routee: Routee, stopChild: Boolean): Unit =
    removeRoutees(List(routee), stopChild)

  def removeRoutees(routees: immutable.Iterable[Routee], stopChild: Boolean): Unit = {
    val r = _router
    val newRoutees = routees.foldLeft(r.routees) { (xs, x) ⇒ unwatch(x); xs.filterNot(_ == x) }
    _router = r.withRoutees(newRoutees)
    if (stopChild) routees foreach stopIfChild
  }

  private def watch(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ watch(ref)
    case _                   ⇒
  }

  private def unwatch(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ unwatch(ref)
    case _                   ⇒
  }

  private def stopIfChild(routee: Routee): Unit = routee match {
    case ActorRefRoutee(ref) ⇒ child(ref.path.name) match {
      case Some(`ref`) ⇒ ref ! PoisonPill
      case _           ⇒
    }
    case _ ⇒
  }

  override def start(): this.type = {
    // create the initial routees before scheduling the Router actor
    _router = routerConfig.createRouter()
    routerConfig match {
      case pool: Pool ⇒
        if (pool.nrOfInstances > 0)
          addRoutees(Vector.fill(pool.nrOfInstances)(pool.newRoutee(routeeProps, this)))
      case nozzle: Nozzle ⇒
        val paths = nozzle.paths
        if (paths.nonEmpty)
          addRoutees(paths.map(p ⇒ nozzle.routeeFor(p, this))(collection.breakOut))
      case _ ⇒
    }
    preSuperStart()
    super.start()
  }

  /**
   * Called when `router` is initalized but before `super.start()` to
   * be able to do extra initialization in subclass.
   */
  protected def preSuperStart(): Unit = ()

  /*
   * end of construction
   */

  /**
   * Route the message via the router to the selected destination.
   *
   * When [[akka.routing.CurrentRoutees]] is sent to the RoutedActorRef it
   * replies with [[akka.routing.RouterRoutees]].
   */
  override def sendMessage(envelope: Envelope): Unit = {
    if (routerConfig.isManagementMessage(envelope.message))
      super.sendMessage(envelope)
    else
      router.route(envelope.message, envelope.sender)
  }

}

/**
 * INTERNAL API
 */
private[akka] class RouterActor(override val supervisorStrategy: SupervisorStrategy) extends Actor {

  val cell = context match {
    case x: RoutedActorCell ⇒ x
    case _ ⇒
      throw ActorInitializationException("Router actor can only be used in RoutedActorRef, not in " + context.getClass)
  }

  def receive = {
    case Terminated(child) ⇒
      cell.removeRoutee(ActorRefRoutee(child), stopChild = false)
      stopIfAllRouteesRemoved()
    case CurrentRoutees ⇒
      sender ! RouterRoutees(cell.router.routees)
    case AddRoutee(routee) ⇒
      cell.addRoutee(routee)
    case RemoveRoutee(routee) ⇒
      cell.removeRoutee(routee, stopChild = true)
      stopIfAllRouteesRemoved()
    case AdjustPoolSize(change: Int) if cell.routerConfig.isInstanceOf[Pool] ⇒
      val pool = cell.routerConfig.asInstanceOf[Pool] // FIXME #3549 should we have a separate Pool actor?
      if (change > 0) {
        val newRoutees = Vector.fill(change)(pool.newRoutee(cell.routeeProps, context))
        cell.addRoutees(newRoutees)
      } else if (change < 0) {
        val currentRoutees = cell.router.routees
        val abandon = currentRoutees.drop(currentRoutees.length + change)
        cell.removeRoutees(abandon, stopChild = true)
      }
  }

  def stopIfAllRouteesRemoved(): Unit =
    if (cell.router.routees.isEmpty && cell.routerConfig.stopRouterWhenAllRouteesRemoved)
      context.stop(self)

  override def preRestart(cause: Throwable, msg: Option[Any]): Unit = {
    // do not scrap children
  }
}

