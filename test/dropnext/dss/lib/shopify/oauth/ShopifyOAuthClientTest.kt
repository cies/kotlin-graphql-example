package dropnext.dss.lib.shopify.oauth

import dropnext.dss.config.ShopifyConfig
import dropnext.dss.lib.shopify.ShopDomain
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.time.Instant
import kotlin.test.Test


private val testConfig = ShopifyConfig(
  appClientId = "client-id-123",
  appClientSecret = "client-secret-xyz",
  scopes = "read_orders,write_products",
  publicBaseUrl = "https://dss.example.com",
  oauthRedirectPath = "/oauth/callback",
  apiVersion = "2026-04",
  serverPort = 8080,
)

private val shop = ShopDomain.parse("acme.myshopify.com")!!
private val now = Instant.parse("2026-05-22T12:00:00Z")

class ShopifyOAuthClientTest {

  private val httpClient = HttpClient(OkHttp)
  private val client = ShopifyOAuthClient(httpClient, testConfig)

  // ---------- authorizeUrl ----------

  @Test
  fun `authorizeUrl includes all required parameters`() {
    val url = client.authorizeUrl(shop, "state-xyz")
    assert(url.startsWith("https://acme.myshopify.com/admin/oauth/authorize?"))
    assert("client_id=client-id-123" in url)
    assert("scope=read_orders%2Cwrite_products" in url)
    assert("redirect_uri=https%3A%2F%2Fdss.example.com%2Foauth%2Fcallback" in url)
    assert("state=state-xyz" in url)
  }

  @Test
  fun `authorizeUrl encodes special characters in state`() {
    val url = client.authorizeUrl(shop, "a b|c")
    assert("state=a+b%7Cc" in url)
  }

  @Test
  fun `randomState returns 32 hex chars`() {
    val state = ShopifyOAuthClient.randomState()
    assert(state.length == 32)
    assert(state.all { it in '0'..'9' || it in 'a'..'f' })
  }

  @Test
  fun `randomState returns different values across calls`() {
    assert(ShopifyOAuthClient.randomState() != ShopifyOAuthClient.randomState())
  }

  // ---------- signed state ----------

  @Test
  fun `signedState validates with matching inputs`() {
    val state = client.signedState(shop, now)
    assert(client.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `signedState validates a few seconds after signing`() {
    val state = client.signedState(shop, now)
    assert(client.isSignedStateValid(state, shop, now.plusSeconds(30)))
  }

  @Test
  fun `signedState expires after 5 minutes`() {
    val state = client.signedState(shop, now)
    assert(!client.isSignedStateValid(state, shop, now.plusSeconds(301)))
  }

  @Test
  fun `signedState rejects mismatched shop`() {
    val state = client.signedState(shop, now)
    val other = ShopDomain.parse("other.myshopify.com")!!
    assert(!client.isSignedStateValid(state, other, now))
  }

  @Test
  fun `signedState rejects wrong secret`() {
    val state = client.signedState(shop, now)
    val otherConfig = testConfig.copy(appClientSecret = "different-secret")
    val otherClient = ShopifyOAuthClient(httpClient, otherConfig)
    assert(!otherClient.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `signedState rejects tampered shop portion`() {
    val state = client.signedState(shop, now)
    val parts = state.split('|')
    val tampered = listOf("evil.myshopify.com", parts[1], parts[2], parts[3]).joinToString("|")
    val evil = ShopDomain.parse("evil.myshopify.com")!!
    assert(!client.isSignedStateValid(tampered, evil, now))
  }

  @Test
  fun `signedState rejects malformed state with three segments`() {
    assert(!client.isSignedStateValid("only|three|parts", shop, now))
  }

  @Test
  fun `signedState rejects malformed state with five segments`() {
    assert(!client.isSignedStateValid("a|b|c|d|e", shop, now))
  }

  @Test
  fun `signedState rejects empty state`() {
    assert(!client.isSignedStateValid("", shop, now))
  }

  @Test
  fun `signedState rejects non-numeric expiry`() {
    val state = "${shop.host}|notANumber|nonce|signature"
    assert(!client.isSignedStateValid(state, shop, now))
  }

  @Test
  fun `signedState two consecutive signs produce different nonces`() {
    val a = client.signedState(shop, now)
    val b = client.signedState(shop, now)
    assert(a != b)
  }
}
