package controllers

import akka.pattern.ask
import play.api._
import play.api.mvc._
import play.libs.Akka
import akka.actor.Props
import actors._
import akka.util.Timeout
import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.ExecutionContext.Implicits.global


import scala.concurrent.duration._
import actors.StatusResp

object Application extends Controller {

  val master = Akka.system.actorOf(Props[Master], "master")

  implicit val timeout = Timeout(10 seconds)

  def index = {
    Logger.info("Launching!")
    master ! Launch
    Action.async{
      (master ? StatusReq).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
    }
  }

  def get = Action {
    Ok.sendFile(new java.io.File("result.csv"))
  }

  def cooldown(num : Int) = Action.async{
    (master ? SetCooldown(num)).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
  }

  def pause(num : Int) = Action.async{
    (master ? SetPause(num)).mapTo[StatusResp].map(msg => Ok(views.html.index(msg)))
  }

}