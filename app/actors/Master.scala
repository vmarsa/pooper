package actors

import akka.actor.{Props, ActorRefFactory, ActorRef, Actor}
import akka.actor.Actor.Receive
import play.libs.Akka
import scala.concurrent.duration._
import java.io.{File, FileWriter}
import scala.io.Source
import play.api.Play
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.Logger
import java.util.Date
import java.text.SimpleDateFormat

/**
 * Created by DmiBaska on 06.10.2014.
 */
case class SlaveStat(throuputPerHour: Long = 0L, good: Long = 0L, bad: Long = 0L, mrim: Long = 0L, toAnother: Long = 0L)

class Master(slaveFactory: ActorRefFactory => ActorRef,
             remoteFactory: (ActorRefFactory, String) => ActorRef,
              proxyFactory: (ActorRefFactory, String) => ActorRef) extends Actor {

  val helpers: List[String] =
    List("http://www.mylovelymac.com/poop.php",
      //"http://www.pooper.host-ed.me/poop.php",
      "http://pooper.eu5.org/poop.php",
      "http://pooper.esy.es/poop.php",
      "http://pooper.orisale.ru/poop.php",
      "http://pooper.hostingsiteforfree.com/poop.php",
      "http://pooper.bugs3.com/poop.php",
      "http://pooper.site90.net/poop.php",
      "http://pooper.1eko.com/poop.php",
      "http://vmarsa.xhc.ru/poop.php",
      "http://pooper.bb777.ru/poop.php",
      "http://www.pooper.seo-vip.com/poop.php",
      "http://stark-gorge-8713.herokuapp.com/poop.php",
      "http://pooper.cloudcontrolled.com",
      "http://pooperxaz.appspot.com/",
      "http://php-pooper2.rhcloud.com/poop.php")

  def this() = this(_.actorOf(Props[Slave], "selfie"),
    (f, url) => f.actorOf(Props(new RemoteSlave(url))),
    (f, p) => f.actorOf(Props(new ProxySlave(p)))
    )
  import play.api.Play.current
  val proxies = Source.fromInputStream(Play.classloader.getResourceAsStream("proxy")).getLines().toList

  val slaveActor = slaveFactory(context)
  val slavesTuples = (("SELF",slaveActor) +: helpers.map(url => url -> remoteFactory.apply(context, url)))// ++ proxies.map(p => p -> proxyFactory(context, p))
  val slavesMap = slavesTuples.toMap.map(_.swap)
  var slavesStats = slavesMap.map(s => s._1 -> SlaveStat())
  val slaves = slavesTuples.map(_._2)
  var blocks = Map[ActorRef, Boolean]()

  var cooldown = 8000
  var pause = 4000

  var lastBlock = Map[ActorRef, Boolean]()

  var launched = false

  var finished = false

  var processed = 0

  var start: Option[Date] = None
  var end: Option[Date] = None

  var currentId: String = null


  var emails: Iterator[String] = Iterator.empty
  var size = 0



  var giveAnotherSlave: List[(ActorRef, String)] = Nil

  var vacant: List[ActorRef] = Nil

  def updateStat(s: ActorRef, fun: SlaveStat => SlaveStat) = {
    val current = slavesStats(s)
    slavesStats += s -> fun(current)
  }

  override def receive: Receive = {
    case Launch(id) => {
      if(!launched) {
        currentId = id
        size = Source.fromFile(RequestPath.requestPath(currentId)).getLines().size
        emails = Source.fromFile(RequestPath.requestPath(currentId)).getLines()
        Logger.info("launch")
        launched = true
        processed = 0
        finished = false
        vacant = List.empty
        slavesStats = slavesMap.map(s => s._1 -> SlaveStat())
        lastBlock = Map[ActorRef, Boolean]()
        start = Some(new Date())
        end = None
        write("", false)
        slaves.foreach(_ ! Ready)
      }
      else Logger.info("Already launched")
    }
    case GiveAnotherSlave(email) => {
      Logger.info("Give another slave: "+ email)
      updateStat(sender, c => c.copy(toAnother = c.toAnother + 1))
      giveAnotherSlave :+= (sender, email)
      Akka.system.scheduler.scheduleOnce(cooldown milliseconds, sender, Ready)
    }
    case Next => {
      if(giveAnotherSlave.nonEmpty || emails.hasNext) {
        val (email, notSendTo) = {
          if(giveAnotherSlave.nonEmpty) {
            val (ns, em) = giveAnotherSlave.head
            giveAnotherSlave = giveAnotherSlave.tail
            Logger.info("Giving from stack: "+ em)
            (em, Some(ns))
          }
          else (emails.next(), None)
        }
        //TODO not send to
        val target = if(notSendTo == Some(sender) && vacant.nonEmpty) {
          val target = vacant.head
          vacant = vacant.tail :+ sender
          Logger.info("Sending to another sender")
          target
        }
        else sender

        Akka.system.scheduler.scheduleOnce(pause milliseconds, target, Ask(Recovery, email))
      }
      else {
        vacant :+= sender
        if(vacant.size == slaves.size) {
          finished = true
          launched = false
          end = Some(new Date())
        }
      }
    }
    case BlockAnswer(method, email) => {
      Logger.info("Bad "+method.id+" answer "+email)
      updateStat(sender, c => c.copy(bad = c.bad + 1))
      //if(lastBlock.getOrElse(sender, false) && cooldown < 100000) cooldown *= 2
      lastBlock += (sender -> true)
      Akka.system.scheduler.scheduleOnce(cooldown milliseconds, sender, Ask(method, email))
    }
    case MrimAnswer(email) => {
      Logger.info("Mrim answer "+email)
      updateStat(sender, c => c.copy(mrim = c.mrim + 1))
      lastBlock -= sender
      sender ! Ask(Access, email)
    }
    case a@Answer(method, email, status, body) => {
      Logger.info("Good answer "+email+" "+status.toString)
      updateStat(sender, c => c.copy(good = c.good + 1))
      lastBlock -= sender
      write((email :: {if(a.notExists) "0" else if(a.exists) "1" else "2"} :: body :: Nil).mkString(";") + "\r\n", true)
      processed += 1
      sender ! Ready
    }
    case StatusReq =>
      sender ! statusMessage
    case SetCooldown(num) =>
      cooldown = math.max(math.min(num, 100000), 100)
    case SetPause(num) =>
      pause = math.max(math.min(num, 100000), 0)
  }

  private def statusMessage = {
    (launched, finished) match {
      case (false, false) => Initial
      case (true, _) =>
        val startMils = start.map(_.getTime)
        val currentMils = new Date().getTime
        StatusResp(currentId, cooldown, pause, size, processed, finished, startMils.map(s => processed.toLong*1000*60*60/(currentMils - s)).getOrElse(0), slavesTuples.map(s => s._1 -> slavesStats(s._2).copy(throuputPerHour = startMils.map(k => slavesStats(s._2).good*1000*60*60/(currentMils - k)).getOrElse(0) )))
      case (false, true) => {
       Finished(currentId, processed, start.get, end.get, speed(end.get))
      }
    }
  }

  private def speed(endDate: Date) = {
    val startMils = start.map(_.getTime)
    val currentMils = endDate.getTime
    startMils.map(s => processed.toLong*1000*60*60/(currentMils - s)).getOrElse(0L)
  }

  private def write(line: String, append: Boolean) = {
    val fw = new FileWriter(RequestPath.resultPath(currentId), append)
    try {
      fw.write(line)
    }
    finally fw.close()
  }
}

case class Launch(id: String)

case object Next

case object StatusReq

case class StatusResp(id: String, cooldown: Int, pause: Int, size: Int, processed: Int, finished: Boolean, throughputPerHour: Long, slavesStat: List[(String, SlaveStat)]) extends Status

case class SetCooldown(cooldown: Int)

case class SetPause(pause: Int)

trait Status

case class Finished(id: String, size: Long, startDate: Date, endDate: Date, speed: Long) extends Status

case object Initial extends Status

object RequestPath {
  private val formatter = new SimpleDateFormat("yyyy-MM-hh-HH-mm-SSS")
  def generateId = formatter.format(new Date())

  private val dirName = "requests"

  def requestPath(id: String) = dirName + "/"+id+"/request.csv"
  def resultPath(id: String) = dirName +"/"+id+"/result.csv"

  def lastIds: List[String] = {
    val dir = new File(dirName)
    if(!dir.exists() || !dir.isDirectory) Nil
    else dir.listFiles().toList.filter(_.isDirectory).map(_.getName)
  }
}

