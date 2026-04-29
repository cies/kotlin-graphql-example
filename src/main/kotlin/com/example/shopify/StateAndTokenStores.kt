package com.example.shopify

import java.util.concurrent.ConcurrentHashMap

class OAuthStateStore {
  private val stateToShop = ConcurrentHashMap<String, String>()

  fun put(state: String, shop: String) {
    stateToShop[state] = shop
  }

  /** @return shop previously bound to this state, or null if missing / replay */
  fun remove(state: String): String? = stateToShop.remove(state)
}
