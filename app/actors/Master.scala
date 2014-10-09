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
import java.util.Date

/**
 * Created by DmiBaska on 06.10.2014.
 */
class Master(slaveFactory: ActorRefFactory => ActorRef,
             remoteFactory: (ActorRefFactory, String) => ActorRef) extends Actor {

  val helpers: List[String] =
    List("http://www.mylovelymac.com/poop.php",
      "http://www.pooper.host-ed.me/poop.php",
      "http://pooper.eu5.org/poop.php",
      "http://pooper.esy.es/poop.php",
      "http://pooper.orisale.ru/poop.php",
      "http://pooper.hostingsiteforfree.com/poop.php",
      "http://pooper.bugs3.com/poop.php")

  // "http://pooper.site90.net/poop.php")

  def this() = this(_.actorOf(Props[Slave], "selfie"),
    (f, url) => f.actorOf(Props(new RemoteSlave(url))))

  val slaveActor = slaveFactory(context)

  val slaves = slaveActor +: helpers.map(url => remoteFactory.apply(context, url))
  var blocks = Map[ActorRef, Boolean]()

  var cooldown = 1000
  var pause = 0

  var lastBlock = Map[ActorRef, Boolean]()

  var launched = false

  var finished = false

  var processed = 0

  var start: Option[Date] = None

  import play.api.Play.current
  lazy val emails: Iterator[String] = Source.fromFile("/tmp/emails.csv").getLines()

  override def receive: Receive = {
    case Launch => {
      if(!launched) {
        Logger.info("launch")
        launched = true
        start = Some(new Date())
        write("", false)
        slaves.foreach(_ ! Ready)
      }
      else Logger.info("Already launched")
    }
    case Next => {
      if(emails.hasNext) {
        val email = emails.next()
        Akka.system.scheduler.scheduleOnce(pause milliseconds, sender, Ask(Recovery, email))
      }
      else
        finished = true
    }
    case a@Answer(method, email, status, _) if status == 403 || a.isBlock => {
      Logger.info("Bad "+method.id+" answer "+email+" "+status.toString)
      if(lastBlock.getOrElse(sender, false) && cooldown < 100000) cooldown *= 2
      lastBlock += (sender -> true)
      Akka.system.scheduler.scheduleOnce(cooldown milliseconds, sender, Ask(method, email))
    }
    case a@Answer(Recovery, email, status, body) if a.isMrim => {
      Logger.info("Mrim answer "+email+" "+status.toString)
      lastBlock -= sender
      sender ! Ask(Access, email)
    }
    case a@Answer(method, email, status, body) => {
      Logger.info("Good answer "+email+" "+status.toString)
      lastBlock -= sender
      write((email :: {if(a.exists) "1" else "0"} :: body :: Nil).mkString(";") + "\r\n", true)
      processed += 1
      sender ! Ready

    }
    case StatusReq =>
      sender ! statusMessage
    case SetCooldown(num) =>
      cooldown = math.max(math.min(num, 100000), 100)
      sender ! statusMessage
    case SetPause(num) =>
      pause = math.max(math.min(num, 100000), 0)
      sender ! statusMessage
  }

  private def statusMessage = {
    val startMils = start.map(_.getTime)
    val currentMils = new Date().getTime
    StatusResp(cooldown, pause, processed, finished, startMils.map(s => processed.toLong*1000*60*60/(currentMils - s)).getOrElse(0))
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

case class StatusResp(cooldown: Int, pause: Int, processed: Int, finished: Boolean, throughputPerHour: Long)

case class SetCooldown(cooldown: Int)

case class SetPause(pause: Int)


