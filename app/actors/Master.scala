package actors

import akka.actor.{Props, ActorRefFactory, ActorRef, Actor}
import akka.actor.Actor.Receive
import play.libs.Akka
import scala.concurrent.duration._
import java.io.FileWriter
import scala.io.Source
import play.api.Play
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Logger

/**
 * Created by DmiBaska on 06.10.2014.
 */
class Master(slaveFactory: ActorRefFactory => ActorRef) extends Actor {

  def this() = this(_.actorOf(Props[Slave], "selfie"))

  val slaveActor = slaveFactory(context)

  var cooldown = 1000
  var pause = 100

  var lastBlock = false

  var launched = false

  var finished = false

  var processed = 0

  import play.api.Play.current
  lazy val emails: Iterator[String] = Source.fromInputStream(Play.classloader.getResourceAsStream("emails.csv")).getLines()

  override def receive: Receive = {
    case Launch => {
      if(!launched) {
        Logger.info("launch")
        launched = true
        write("", false)
        self ! Next
      }
      else Logger.info("Already launched")
    }
    case Next => {
      if(emails.hasNext) {
        val email = emails.next()
        Akka.system.scheduler.scheduleOnce(pause milliseconds, slaveActor, Ask(Recovery, email))
      }
      else
        finished = true
    }
    case a@Answer(method, email, status, _) if status == 403 || a.isBlock => {
      Logger.info("Bad "+method.id+" answer "+email+" "+status.toString)
      if(lastBlock && cooldown < 100000) cooldown *= 2
      lastBlock = true
      Akka.system.scheduler.scheduleOnce(cooldown milliseconds, slaveActor, Ask(method, email))
    }
    case a@Answer(Recovery, email, status, body) if a.isMrim => {
      Logger.info("Mrim answer "+email+" "+status.toString)
      lastBlock = false
      slaveActor ! Ask(Access, email)
    }
    case a@Answer(method, email, status, body) => {
      Logger.info("Good answer "+email+" "+status.toString)
      lastBlock = false
      write(email+","+{if(a.exists) "1" else "0"}+","+body+"\r\n", true)
      processed += 1
      self ! Next
    }
    case StatusReq =>
      sender ! StatusResp(cooldown, pause, processed, finished)
    case SetCooldown(num) =>
      cooldown = math.max(math.min(num, 100000), 100)
      sender ! StatusResp(cooldown, pause, processed, finished)
    case SetPause(num) =>
      pause = math.max(math.min(num, 100000), 5)
      sender ! StatusResp(cooldown, pause, processed, finished)
  }

  private def write(line: String, append: Boolean) = {
    val fw = new FileWriter("result.csv", append)
    try {
      fw.write(line)
    }
    finally fw.close()
  }
}

case object Launch

case object Next

case object StatusReq

case class StatusResp(cooldown: Int, pause: Int, processed: Int, finished: Boolean)

case class SetCooldown(cooldown: Int)

case class SetPause(pause: Int)


