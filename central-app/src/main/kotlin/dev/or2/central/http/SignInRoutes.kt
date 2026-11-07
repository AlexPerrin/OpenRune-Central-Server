package dev.or2.central.http

import dev.or2.central.signin.SignInService
import dev.or2.central.signin.SignedIn
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.encodeURLParameter
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.escapeHTML
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

fun ApplicationCall.publicBase(): String = request.origin.run { "$scheme://$serverHost:$serverPort/" }

private const val SCOPE = "openid offline gamesso.token.create"
private val AUTH_FIELDS = listOf("redirect_uri", "state", "nonce", "code_challenge", "client_id")

fun Route.signInRoutes(signIn: SignInService) {
    get("/.well-known/openid-configuration") {
        val base = call.publicBase()
        call.respond(
            buildJsonObject {
                put("issuer", base)
                put("authorization_endpoint", base + "oauth2/auth")
                put("token_endpoint", base + "oauth2/token")
                put("userinfo_endpoint", base + "userinfo")
                put("jwks_uri", base + "jwks")
                putJsonArray("response_types_supported") { add("code") }
                putJsonArray("subject_types_supported") { add("public") }
                putJsonArray("id_token_signing_alg_values_supported") { add("HS256") }
                putJsonArray("scopes_supported") { SCOPE.split(' ').forEach { add(it) } }
                putJsonArray("code_challenge_methods_supported") { add("S256") }
            },
        )
    }

    get("/oauth2/auth") {
        val q = call.request.queryParameters
        if (q["redirect_uri"] == null) return@get call.respondText("missing redirect_uri", status = HttpStatusCode.BadRequest)
        if (signIn.devAccount.isNotEmpty()) return@get call.redirectWithCode(signIn, signIn.devAccount, q)
        call.respondSignInForm(q, HttpStatusCode.OK)
    }

    post("/oauth2/auth") {
        val form = call.receiveParameters()
        if (form["redirect_uri"] == null) return@post call.respondText("missing redirect_uri", status = HttpStatusCode.BadRequest)
        val account = signIn.checkPassword(form["username"].orEmpty(), form["password"].orEmpty())
        if (account == null) return@post call.respondSignInForm(form, HttpStatusCode.Unauthorized)
        call.redirectWithCode(signIn, account, form)
    }

    post("/oauth2/token") {
        val form = call.receiveParameters()
        val signedIn =
            when (form["grant_type"]) {
                "authorization_code" -> form["code"]?.let { signIn.redeemCode(it, form["code_verifier"]) }
                "refresh_token" -> form["refresh_token"]?.let(signIn::redeemRefreshToken)?.let { SignedIn(it, null) }
                else -> null
            } ?: return@post call.respond(HttpStatusCode.BadRequest, buildJsonObject { put("error", "invalid_grant") })
        call.respond(
            buildJsonObject {
                put("access_token", signIn.issueAccessToken(signedIn.account))
                put("token_type", "Bearer")
                put("expires_in", 86_400)
                put("refresh_token", signIn.issueRefreshToken(signedIn.account))
                put("id_token", signIn.idToken(call.publicBase(), form["client_id"].orEmpty(), signedIn))
                put("scope", SCOPE)
            },
        )
    }

    get("/userinfo") {
        val account = call.bearer()?.let(signIn::accessAccount) ?: return@get call.respond(HttpStatusCode.Unauthorized, "")
        call.respond(
            buildJsonObject {
                put("sub", account)
                put("nickname", account)
            },
        )
    }

    get("/jwks") {
        call.respond(buildJsonObject { putJsonArray("keys") {} })
    }

    post("/game-session/v1/sessions") {
        val idToken = runCatching { Json.parseToJsonElement(call.receiveText()).jsonObject.getValue("idToken").jsonPrimitive.content }.getOrNull()
        val sessionId =
            idToken?.let(signIn::openSession)
                ?: return@post call.respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "invalid idToken") })
        call.respond(buildJsonObject { put("sessionId", sessionId) })
    }

    get("/game-session/v1/accounts") {
        val account = call.sessionAccount(signIn) ?: return@get call.respondInvalidSession()
        val id = SignInService.accountId(account)
        call.respond(
            buildJsonArray {
                addJsonObject {
                    put("accountId", id)
                    put("displayName", account)
                    put("userHash", id)
                }
            },
        )
    }

    post("/game-session/v1/tokens") {
        val account = call.sessionAccount(signIn) ?: return@post call.respondInvalidSession()
        call.respond(buildJsonObject { put("token", signIn.mintLoginToken(account)) })
    }
}

private fun ApplicationCall.bearer(): String? = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()

private fun ApplicationCall.sessionAccount(signIn: SignInService): String? = bearer()?.let(signIn::sessionAccount)

private suspend fun ApplicationCall.respondInvalidSession() =
    respond(HttpStatusCode.Unauthorized, buildJsonObject { put("error", "invalid session") })

private suspend fun ApplicationCall.redirectWithCode(signIn: SignInService, account: String, request: Parameters) {
    val redirect = request["redirect_uri"]!!
    val code = signIn.issueCode(account, request["nonce"], request["code_challenge"])
    val state = request["state"]?.let { "&state=" + it.encodeURLParameter() }.orEmpty()
    respondRedirect(redirect + (if ('?' in redirect) "&" else "?") + "code=" + code + state)
}

private suspend fun ApplicationCall.respondSignInForm(request: Parameters, status: HttpStatusCode) {
    val hidden =
        AUTH_FIELDS.mapNotNull { name ->
            request[name]?.let { """<input type="hidden" name="$name" value="${it.escapeHTML()}">""" }
        }.joinToString("")
    respondText(
        """<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>Sign in</title></head><body>""" +
            """<form method="post" action="/oauth2/auth">$hidden""" +
            """<p><label>Username <input name="username" autocomplete="username" required></label></p>""" +
            """<p><label>Password <input name="password" type="password" autocomplete="current-password" required></label></p>""" +
            """<p><button type="submit">Sign in</button></p></form></body></html>""",
        ContentType.Text.Html,
        status,
    )
}
