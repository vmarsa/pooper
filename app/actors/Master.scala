package actors

import akka.actor.{Props, ActorRefFactory, ActorRef, Actor}
import akka.actor.Actor.Receive
import play.libs.Akka
import scala.concurrent.duration._
import java.io.FileWriter
import scala.io.Source
import play.api.Play
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Created by DmiBaska on 06.10.2014.
 */
class Master(slaveFactory: ActorRefFactory => ActorRef) extends Actor {

  def this() = this(_.actorOf(Props[Slave], "selfie"))

  val slaveActor = slaveFactory(context)

  var cooldown = 1000

  var lastBlock = false

  var launched = false

  import play.api.Play.current
  lazy val emails: Iterator[String] = Source.fromInputStream(Play.classloader.getResourceAsStream("emails.csv")).getLines()

  override def receive: Receive = {
    case Launch => {
      launched = true
      write("", false)
      self ! Next
    }
    case Next => {
      if(emails.hasNext) {
        val email = emails.next()
        slaveActor ! Ask(email)
      }
    }
    case a@Answer(email, status, _) if status == 403 || a.isBlock => {
      print("Bas answer "+email+" "+status.toString)
      if(lastBlock) cooldown *= 2
      lastBlock = true
      Akka.system.scheduler.scheduleOnce(50 milliseconds, slaveActor, Ask(email))
    }
    case a@Answer(email, status, body) => {
      print("Good answer "+email+" "+status.toString)
      lastBlock = false
      write(email+","+{if(a.exists) "1" else "0"}+","+body, true)
      self ! Next
    }
    case StatusReq => sender ! StatusResp(cooldown)
  }

  private def write(line: String, append: Boolean) = {
    val fw = new FileWriter("result.csv", append)
    try {
      fw.write("")
    }
    finally fw.close()
  }
}

case object Launch

case object Next

case object StatusReq

case class StatusResp(cooldown: Int)


