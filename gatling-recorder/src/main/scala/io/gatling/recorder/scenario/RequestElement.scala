/**
 * Copyright 2011-2013 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.recorder.scenario

import java.nio.charset.Charset

import scala.collection.JavaConversions.{ asScalaBuffer, mapAsScalaMap }

import org.jboss.netty.handler.codec.http.{ HttpRequest, QueryStringDecoder }
import org.jboss.netty.handler.codec.http.HttpHeaders.Names.{ AUTHORIZATION, CONTENT_TYPE }
import org.jboss.netty.handler.codec.http.HttpHeaders.Values.APPLICATION_X_WWW_FORM_URLENCODED

import com.ning.http.util.Base64

import io.gatling.http.util.HttpHelper.parseFormBody
import io.gatling.recorder.config.RecorderConfiguration.configuration
import io.gatling.recorder.scenario.template.RequestTemplate
import io.gatling.recorder.util.URIHelper

sealed trait RequestBody
case class RequestBodyParams(params: List[(String, String)]) extends RequestBody
case class RequestBodyBytes(bytes: Array[Byte]) extends RequestBody

object RequestElement {

	def apply(request: HttpRequest, statusCode: Int, simulationClass: Option[String]): RequestElement = {
		val headers: Map[String, String] = request.getHeaders.map { entry => (entry.getKey, entry.getValue) }.toMap
		val content = if (request.getContent.readableBytes > 0) {
			val bufferBytes = new Array[Byte](request.getContent.readableBytes)
			request.getContent.getBytes(request.getContent.readerIndex, bufferBytes)
			Some(bufferBytes)
		} else None

		val containsFormParams = headers.get(CONTENT_TYPE).exists(_.contains(APPLICATION_X_WWW_FORM_URLENCODED))
		val body = content.map(content => if (containsFormParams) RequestBodyParams(parseFormBody(new String(content, configuration.core.encoding))) else RequestBodyBytes(content))

		RequestElement(request.getUri, request.getMethod.toString, headers, body, statusCode, simulationClass)
	}
}

case class RequestElement(uri: String, method: String, headers: Map[String, String], body: Option[RequestBody], statusCode: Int, simulationClass: Option[String]) extends ScenarioElement {

	val (baseUrl, pathQuery) = URIHelper.splitURI(uri)
	private var printedUrl = uri.split("\\?")(0)

	var filteredHeadersId: Option[Int] = None

	val queryParams = convertParamsFromJavaToScala(new QueryStringDecoder(uri, Charset.forName(configuration.core.encoding)).getParameters)

	var id: Int = 0

	def setId(id: Int) {
		this.id = id
	}

	def makeRelativeTo(baseUrl: String): RequestElement = {
		if (baseUrl == this.baseUrl) printedUrl = pathQuery.split("?")(0)
		this
	}

	private def convertParamsFromJavaToScala(params: java.util.Map[String, java.util.List[String]]): List[(String, String)] =
		for {
			(key, list) <- params.toList
			e <- list
		} yield (key, e)

	private val basicAuthCredentials: Option[(String, String)] = {
		def parseCredentials(header: String) = {
			val credentials = new String(Base64.decode(header.split(" ")(1))).split(":")
			if (credentials.length == 2)
				Some(credentials(0), credentials(1))
			else
				None
		}

		headers.get(AUTHORIZATION).filter(_.startsWith("Basic ")).flatMap(parseCredentials)
	}

	override def toString =
		RequestTemplate.render(
			simulationClass.getOrElse(throw new UnsupportedOperationException("simulationName should be set before printing a request element!")),
			id,
			method,
			printedUrl,
			filteredHeadersId,
			basicAuthCredentials,
			queryParams,
			body,
			statusCode)
}