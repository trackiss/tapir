package sttp.tapir.server.interceptor.decodefailure

import sttp.model.{Header, HeaderNames, StatusCode}
import sttp.tapir.DecodeResult.Error.{JsonDecodeException, MultipartDecodeException}
import sttp.tapir.DecodeResult._
import sttp.tapir.internal.RichEndpoint
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.{DecodeResult, EndpointIO, EndpointInput, ValidationError, Validator, server, _}

import scala.annotation.tailrec

trait DecodeFailureHandler {

  /** Given the context in which a decode failure occurred (the request, the input and the failure), returns an optional response to the
    * request. `None` indicates that no action should be taken, and the request might be passed for decoding to other endpoints.
    *
    * Inputs are decoded in the following order: path, method, query, headers, body. Hence, if there's a decode failure on a query
    * parameter, any method & path inputs of the input must have matched and must have been decoded successfully.
    */
  def apply(ctx: DecodeFailureContext): Option[ValuedEndpointOutput[_]]
}

/** A decode failure handler, which:
  *   - decides whether the given decode failure should lead to a response (and if so, with which status code and headers), using `respond`
  *   - in case a response is sent, creates the message using `failureMessage`
  *   - in case a response is sent, creates the response using `response`, given the status code, headers, and the created failure message.
  *     By default, the headers might include authentication challenge.
  */
case class DefaultDecodeFailureHandler(
    respond: DecodeFailureContext => Option[(StatusCode, List[Header])],
    failureMessage: DecodeFailureContext => String,
    response: (StatusCode, List[Header], String) => ValuedEndpointOutput[_]
) extends DecodeFailureHandler {
  def apply(ctx: DecodeFailureContext): Option[ValuedEndpointOutput[_]] = {
    respond(ctx) match {
      case Some((sc, hs)) =>
        val failureMsg = failureMessage(ctx)
        Some(response(sc, hs, failureMsg))
      case None => None
    }
  }

  def response(messageOutput: String => ValuedEndpointOutput[_]): DefaultDecodeFailureHandler =
    copy(response = (s, h, m) => messageOutput(m).prepend(statusCode.and(headers), (s, h)))
}

object DefaultDecodeFailureHandler {

  /** The default implementation of the [[DecodeFailureHandler]].
    *
    * A 400 (bad request) is returned if a query, header or body input can't be decoded (for any reason), or if decoding a path capture
    * causes a validation error.
    *
    * A 401 (unauthorized) is returned when an authentication input (created using [[Tapir.auth]]) cannot be decoded. The appropriate
    * `WWW-Authenticate` headers are included.
    *
    * Otherwise (e.g. if the method, a path segment, or path capture is missing, there's a mismatch or a decode error), `None` is returned,
    * which is a signal to try the next endpoint.
    *
    * The error messages contain information about the source of the decode error, and optionally the validation error detail that caused
    * the failure.
    *
    * This is only used for failures that occur when decoding inputs, not for exceptions that happen when the server logic is invoked.
    */
  val default: DefaultDecodeFailureHandler = DefaultDecodeFailureHandler(
    respond(_, badRequestOnPathErrorIfPathShapeMatches = false, badRequestOnPathInvalidIfPathShapeMatches = true),
    FailureMessages.failureMessage,
    failureResponse
  )

  /** A [[default]] handler which responds with a `404 Not Found`, instead of a `401 Unauthorized` or `400 Bad Request`, in case any input
    * fails to decode, and the endpoint contains authentication inputs (created using [[Tapir.auth]]). No `WWW-Authenticate` headers are
    * sent.
    *
    * Hence, the information if the endpoint exists, but needs authentication is hidden from the client. However, the existence of the
    * endpoint might still be revealed using timing attacks.
    */
  val hideEndpointsWithAuth: DefaultDecodeFailureHandler =
    default.copy(respond = ctx => respondNotFoundIfHasAuth(ctx, default.respond(ctx)))

  def failureResponse(c: StatusCode, hs: List[Header], m: String): ValuedEndpointOutput[_] =
    server.model.ValuedEndpointOutput(statusCode.and(headers).and(stringBody), (c, hs, m))

  /** @param badRequestOnPathErrorIfPathShapeMatches
    *   Should a status 400 be returned if the shape of the path of the request matches, but decoding some path segment fails with a
    *   [[DecodeResult.Error]].
    * @param badRequestOnPathInvalidIfPathShapeMatches
    *   Should a status 400 be returned if the shape of the path of the request matches, but decoding some path segment fails with a
    *   [[DecodeResult.InvalidValue]].
    */
  def respond(
      ctx: DecodeFailureContext,
      badRequestOnPathErrorIfPathShapeMatches: Boolean,
      badRequestOnPathInvalidIfPathShapeMatches: Boolean
  ): Option[(StatusCode, List[Header])] = {
    failingInput(ctx) match {
      case _: EndpointInput.Query[_]       => Some(onlyStatus(StatusCode.BadRequest))
      case _: EndpointInput.QueryParams[_] => Some(onlyStatus(StatusCode.BadRequest))
      case _: EndpointInput.Cookie[_]      => Some(onlyStatus(StatusCode.BadRequest))
      case h: EndpointIO.Header[_] if ctx.failure.isInstanceOf[DecodeResult.Mismatch] && h.name == HeaderNames.ContentType =>
        Some(onlyStatus(StatusCode.UnsupportedMediaType))
      case _: EndpointIO.Header[_] => Some(onlyStatus(StatusCode.BadRequest))
      case fh: EndpointIO.FixedHeader[_] if ctx.failure.isInstanceOf[DecodeResult.Mismatch] && fh.h.name == HeaderNames.ContentType =>
        Some(onlyStatus(StatusCode.UnsupportedMediaType))
      case _: EndpointIO.FixedHeader[_] => Some(onlyStatus(StatusCode.BadRequest))
      case _: EndpointIO.Headers[_]     => Some(onlyStatus(StatusCode.BadRequest))
      case _: EndpointIO.Body[_, _]     => Some(onlyStatus(StatusCode.BadRequest))
      case _: EndpointIO.OneOfBody[_, _] if ctx.failure.isInstanceOf[DecodeResult.Mismatch] =>
        Some(onlyStatus(StatusCode.UnsupportedMediaType))
      case _: EndpointIO.StreamBodyWrapper[_, _] => Some(onlyStatus(StatusCode.BadRequest))
      // we assume that the only decode failure that might happen during path segment decoding is an error
      // a non-standard path decoder might return Missing/Multiple/Mismatch, but that would be indistinguishable from
      // a path shape mismatch
      case _: EndpointInput.PathCapture[_]
          if (badRequestOnPathErrorIfPathShapeMatches && ctx.failure.isInstanceOf[DecodeResult.Error]) ||
            (badRequestOnPathInvalidIfPathShapeMatches && ctx.failure.isInstanceOf[DecodeResult.InvalidValue]) =>
        Some(onlyStatus(StatusCode.BadRequest))
      // if the failing input contains an authentication input (potentially nested), sending its challenge
      case FirstAuth(a) => Some((StatusCode.Unauthorized, Header.wwwAuthenticate(a.challenge)))
      // other basic endpoints - the request doesn't match, but not returning a response (trying other endpoints)
      case _: EndpointInput.Basic[_] => None
      // all other inputs (tuples, mapped) - responding with bad request
      case _ => Some(onlyStatus(StatusCode.BadRequest))
    }
  }

  def respondNotFoundIfHasAuth(
      ctx: DecodeFailureContext,
      response: Option[(StatusCode, List[Header])]
  ): Option[(StatusCode, List[Header])] = response.map { r =>
    val e = ctx.endpoint
    if (e.auths.nonEmpty) {
      // all responses (both 400 and 401) are converted to a not-found
      onlyStatus(StatusCode.NotFound)
    } else r
  }

  private def onlyStatus(status: StatusCode): (StatusCode, List[Header]) = (status, Nil)

  private def failingInput(ctx: DecodeFailureContext) = {
    import sttp.tapir.internal.RichEndpointInput
    ctx.failure match {
      case DecodeResult.Missing =>
        def missingAuth(i: EndpointInput[_]) = i.pathTo(ctx.failingInput).collectFirst { case a: EndpointInput.Auth[_, _] =>
          a
        }
        missingAuth(ctx.endpoint.securityInput).orElse(missingAuth(ctx.endpoint.input)).getOrElse(ctx.failingInput)
      case _ => ctx.failingInput
    }
  }

  private object FirstAuth {
    def unapply(input: EndpointInput[_]): Option[EndpointInput.Auth[_, _]] = input match {
      case a: EndpointInput.Auth[_, _]           => Some(a)
      case EndpointInput.MappedPair(input, _)    => unapply(input)
      case EndpointIO.MappedPair(input, _)       => unapply(input)
      case EndpointInput.Pair(left, right, _, _) => unapply(left).orElse(unapply(right))
      case EndpointIO.Pair(left, right, _, _)    => unapply(left).orElse(unapply(right))
      case _                                     => None
    }
  }

  /** Default messages for [[DecodeResult.Failure]] s. */
  object FailureMessages {

    /** Describes the source of the failure: in which part of the request did the failure occur. */
    @tailrec
    def failureSourceMessage(input: EndpointInput[_]): String =
      input match {
        case EndpointInput.FixedMethod(_, _, _)      => s"Invalid value for: method"
        case EndpointInput.FixedPath(_, _, _)        => s"Invalid value for: path segment"
        case EndpointInput.PathCapture(name, _, _)   => s"Invalid value for: path parameter ${name.getOrElse("?")}"
        case EndpointInput.PathsCapture(_, _)        => s"Invalid value for: path"
        case EndpointInput.Query(name, _, _, _)      => s"Invalid value for: query parameter $name"
        case EndpointInput.QueryParams(_, _)         => "Invalid value for: query parameters"
        case EndpointInput.Cookie(name, _, _)        => s"Invalid value for: cookie $name"
        case _: EndpointInput.ExtractFromRequest[_]  => "Invalid value"
        case a: EndpointInput.Auth[_, _]             => failureSourceMessage(a.input)
        case _: EndpointInput.MappedPair[_, _, _, _] => "Invalid value"
        case _: EndpointIO.Body[_, _]                => s"Invalid value for: body"
        case _: EndpointIO.StreamBodyWrapper[_, _]   => s"Invalid value for: body"
        case EndpointIO.Header(name, _, _)           => s"Invalid value for: header $name"
        case EndpointIO.FixedHeader(name, _, _)      => s"Invalid value for: header $name"
        case EndpointIO.Headers(_, _)                => s"Invalid value for: headers"
        case _                                       => "Invalid value"
      }

    def failureDetailMessage(failure: DecodeResult.Failure): Option[String] = failure match {
      case InvalidValue(errors) if errors.nonEmpty => Some(ValidationMessages.validationErrorsMessage(errors))
      case Error(_, JsonDecodeException(errors, _)) if errors.nonEmpty =>
        Some(
          errors
            .map { error =>
              val at = if (error.path.nonEmpty) s" at '${error.path.map(_.encodedName).mkString(".")}'" else ""
              error.msg + at
            }
            .mkString(", ")
        )
      case Error(_, MultipartDecodeException(partFailures)) =>
        Some(
          partFailures
            .map { case (partName, partDecodeFailure) =>
              combineSourceAndDetail(s"part: $partName", failureDetailMessage(partDecodeFailure))
            }
            .mkString(", ")
        )
      case Missing        => Some("missing")
      case Multiple(_)    => Some("multiple values")
      case Mismatch(_, _) => Some("value mismatch")
      case _              => None
    }

    def combineSourceAndDetail(source: String, detail: Option[String]): String =
      detail match {
        case None    => source
        case Some(d) => s"$source ($d)"
      }

    /** Default message describing the source of a decode failure, alongside with optional validation/decode failure details. */
    def failureMessage(ctx: DecodeFailureContext): String = {
      val base = failureSourceMessage(ctx.failingInput)
      val detail = failureDetailMessage(ctx.failure)
      combineSourceAndDetail(base, detail)
    }
  }

  /** Default messages when the decode failure is due to a validation error. */
  object ValidationMessages {

    /** Default message describing why a value is invalid.
      * @param valueName
      *   Name of the validated value to be used in error messages
      */
    def invalidValueMessage[T](ve: ValidationError[T], valueName: String): String = {
      ve.customMessage match {
        case Some(message) => s"expected $valueName to pass validation: $message, but was: ${ve.invalidValue}"
        case None =>
          ve.validator match {
            case Validator.Min(value, exclusive) =>
              s"expected $valueName to be greater than ${if (exclusive) "" else "or equal to "}$value, but was ${ve.invalidValue}"
            case Validator.Max(value, exclusive) =>
              s"expected $valueName to be less than ${if (exclusive) "" else "or equal to "}$value, but was ${ve.invalidValue}"
            // TODO: convert to patterns when https://github.com/lampepfl/dotty/issues/12226 is fixed
            case p: Validator.Pattern[T] => s"expected $valueName to match: ${p.value}, but was: ${ve.invalidValue}"
            case m: Validator.MinLength[T] =>
              s"expected $valueName to have length greater than or equal to ${m.value}, but was ${ve.invalidValue}"
            case m: Validator.MaxLength[T] =>
              s"expected $valueName to have length less than or equal to ${m.value}, but was ${ve.invalidValue} "
            case m: Validator.MinSize[T, Iterable] =>
              s"expected size of $valueName to be greater than or equal to ${m.value}, but was ${ve.invalidValue.size}"
            case m: Validator.MaxSize[T, Iterable] =>
              s"expected size of $valueName to be less than or equal to ${m.value}, but was ${ve.invalidValue.size}"
            case Validator.Custom(_, _) => s"expected $valueName to pass validation, but was: ${ve.invalidValue}"
            case Validator.Enumeration(possibleValues, encode, _) =>
              val encodedPossibleValues =
                encode.fold(possibleValues.map(_.toString))(e => possibleValues.flatMap(e(_).toList).map(_.toString))
              s"expected $valueName to be one of ${encodedPossibleValues.mkString("(", ", ", ")")}, but was ${ve.invalidValue}"
          }
      }
    }

    /** Default message describing the path to an invalid value. This is the path inside the validated object, e.g.
      * `user.address.street.name`.
      */
    def pathMessage(path: List[FieldName]): Option[String] =
      path match {
        case Nil => None
        case l   => Some(l.map(_.encodedName).mkString("."))
      }

    /** Default message describing the validation error: which value is invalid, and why. */
    def validationErrorMessage(ve: ValidationError[_]): String = invalidValueMessage(ve, pathMessage(ve.path).getOrElse("value"))

    /** Default message describing a list of validation errors: which values are invalid, and why. */
    def validationErrorsMessage(ve: List[ValidationError[_]]): String = ve.map(validationErrorMessage).mkString(", ")
  }
}
