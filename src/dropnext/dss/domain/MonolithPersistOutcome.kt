package dropnext.dss.domain


/**
 * Outcome of attempting to persist the freshly obtained Shopify Admin token to the monolith
 * during OAuth installation. The view renders one of these as a success / error notice.
 */
sealed interface MonolithPersistOutcome {
  data class Persisted(val storeId: StoreId) : MonolithPersistOutcome

  /** [httpStatus] is `null` when the monolith never answered (a transport failure). */
  data class Failed(val httpStatus: Int?, val detail: String?) : MonolithPersistOutcome
}
