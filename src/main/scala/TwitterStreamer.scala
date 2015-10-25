/**
 * This is a fully working example of Twitter's Streaming API client.
 * NOTE: this may stop working if at any point Twitter does some breaking changes to this API or the JSON structure.
 */

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import com.hunorkovacs.koauth.domain.KoauthRequest
import com.hunorkovacs.koauth.service.consumer.DefaultConsumerService
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Try, Failure, Success}
import org.json4s._
import org.json4s.native.JsonMethods._

object TwitterStreamer extends App {

	val conf = ConfigFactory.load()

	//Get your credentials from https://apps.twitter.com and replace the values below
	private val consumerKey = "YOUR_CONSUMER_KEY"
	private val consumerSecret = "YOUR_CONSUMER_SECRET"
	private val accessToken = "APP_ACCESS_TOKEN"
	private val accessTokenSecret = "APP_ACCESS_TOKEN_SECRET"
	private val url = "https://stream.twitter.com/1.1/statuses/filter.json"

	implicit val system = ActorSystem()
	implicit val materializer = ActorMaterializer()
	implicit val formats = DefaultFormats

	private val consumer = new DefaultConsumerService(system.dispatcher)

	//Filter tweets by a term "london"
	val body = "track=london"
	val source = Uri(url)

	//Create Oauth 1a header
	val oauthHeader: Future[String] = consumer.createOauthenticatedRequest(
		KoauthRequest(
			method = "POST",
			url = url,
			authorizationHeader = None,
			body = Some(body)
		),
		consumerKey,
		consumerSecret,
		accessToken,
		accessTokenSecret
	) map (_.header)

	oauthHeader.onComplete {
		case Success(header) =>
			val httpHeaders: List[HttpHeader] = List(
				HttpHeader.parse("Authorization", header) match {
					case ParsingResult.Ok(h, _) => Some(h)
					case _ => None
				},
				HttpHeader.parse("Accept", "*/*") match {
					case ParsingResult.Ok(h, _) => Some(h)
					case _ => None
				}
			).flatten
			val httpRequest: HttpRequest = HttpRequest(
				method = HttpMethods.POST,
				uri = source,
				headers = httpHeaders,
				entity = HttpEntity(contentType = ContentType(MediaTypes.`application/x-www-form-urlencoded`), string = body)
			)
			val request = Http().singleRequest(httpRequest)
			request.flatMap { response =>
				if (response.status.intValue() != 200) {
					println(response.entity.dataBytes.runForeach(_.utf8String))
					Future(Unit)
				} else {
					response.entity.dataBytes
						.scan("")((acc, curr) => if (acc.contains("\r\n")) curr.utf8String else acc + curr.utf8String)
						.filter(_.contains("\r\n"))
						.map(json => Try(parse(json).extract[Tweet]))
						.runForeach {
							case Success(tweet) =>
								println("-----")
								println(tweet.text)
							case Failure(e) =>
								println("-----")
								println(e.getStackTrace)
						}
				}
			}
		case Failure(failure) => println(failure.getMessage)
	}

}