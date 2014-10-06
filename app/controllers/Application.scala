package controllers

import akka.pattern.ask
import play.api._
import play.api.mvc._
import play.libs.Akka
import akka.actor.Props
import actors.{StatusResp, StatusReq, Launch, Master}
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global


import scala.concurrent.duration._

object Application extends Controller {

  val master = Akka.system.actorOf(Props[Master], "master")

  implicit val timeout = Timeout(10 seconds)

  def index = {
    Logger.info("Launching!")
    master ! Launch
    Action.async{
      (master ? StatusReq).mapTo[StatusResp].map(msg => Ok(views.html.index("Cooldown: "+msg.toString)))
    }
  }

  def get = Action {
    Ok.sendFile(new java.io.File("result.csv"))
  }


}