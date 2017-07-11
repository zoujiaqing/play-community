package controllers

import javax.inject._

import models._
import models.JsonFormats.userFormat
import reactivemongo.play.json._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.Json
import play.api.mvc._
import play.modules.reactivemongo.ReactiveMongoApi
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.collection.JSONCollection
import service.CounterService
import utils.{DateTimeUtil, HashUtil}

import scala.concurrent.Future
import scala.util.Random
import java.time.LocalDateTime

@Singleton
class Application @Inject()(val reactiveMongoApi: ReactiveMongoApi, counterService: CounterService) extends Controller {
//class Application @Inject()(cc: ControllerComponents, val reactiveMongoApi: ReactiveMongoApi)(implicit ec: ExecutionContext) extends AbstractController(cc) {
  def userColFuture = reactiveMongoApi.database.map(_.collection[JSONCollection]("common-user"))

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def message(title: String, message: String) = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.tips(title, message))
  }

  def register = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.register())
  }

  def doRegister = Action.async { implicit request: Request[AnyContent] =>
    Form(tuple("login" -> nonEmptyText, "name" -> nonEmptyText, "password" -> nonEmptyText, "repassword" -> nonEmptyText)).bindFromRequest().fold(
      errForm => Future.successful(Ok("err")),
      tuple => {
        val (login, name, password, repassword) = tuple
        (for{
          userCol <- userColFuture
          userOpt <- userCol.find(Json.obj("login" -> login)).one[User]
        } yield {
          userOpt match {
            case Some(u) =>
              Future.successful(Redirect(routes.Application.message("注册出错了", "您已经注册过了！")))
            case None =>
              if (password == repassword) {
                val verifyCode = (0 to 7).map(i => Random.nextInt(10).toString).mkString
                for{
                  uid <- counterService.getNextSequence("user-sequence")
                  wr <-  userCol.insert(User(uid, Role.COMMON_USER, login, HashUtil.sha256(password), name, "", "", "", request.remoteAddress, UserTimeStat(DateTimeUtil.now, DateTimeUtil.now, DateTimeUtil.now, DateTimeUtil.now()), 0, true, verifyCode))
                } yield {
                  if (wr.ok && wr.n == 1) {
                    Redirect(routes.UserController.home())
                      .withSession("login" -> login, "uid" -> uid.toString, "name" -> name)
                  } else {
                    Redirect(routes.Application.message("注册出错了", "很抱歉，似乎是发生了系统错误！"))
                  }
                }
              } else {
                Future.successful(Redirect(routes.Application.message("注册出错了", "您两次输入的密码不一致！")))
              }
          }
        }).flatMap(f1 => f1)
      }
    )
  }

  def login(login: Option[String]) = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.login())
  }

  def logout = Action { implicit request: Request[AnyContent] =>
    Redirect(routes.Application.login(request.session.get("login"))).withNewSession
  }

  def doLogin = Action.async { implicit request: Request[AnyContent] =>
    Form(tuple("login" -> nonEmptyText, "password" -> nonEmptyText)).bindFromRequest().fold(
      errForm => Future.successful(Ok("err")),
      tuple => {
        val (login, password) = tuple
        for{
          userCol <- userColFuture
          userOpt <- userCol.find(Json.obj("login" -> login, "password" -> HashUtil.sha256(password))).one[User]
        } yield {
          userOpt match {
            case Some(u) =>
              Redirect(routes.UserController.home())
                .withSession("uid" -> u._id.toString, "login" -> u.login, "name" -> u.name)
            case None =>
              Redirect(routes.Application.message("操作出错了！", "用户名或密码错误！"))
          }
        }
      }
    )
  }

  def notFound = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.notFound())
  }
}