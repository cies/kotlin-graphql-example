const CURRENCIES = ["USD", "GBP", "CHF", "CAD", "AUD"] as const;

export type EurLiveQuotes = Record<string, number>;

/**
 * Fetches EUR→quote cross factors as `EUR${currency}` keys (apilayer-style shape).
 * Uses exchangerate.host when `EXCHANGERATE_API_KEY` is set; otherwise Frankfurter (no key, ECB).
 */
export async function fetchEurLiveQuotes(): Promise<EurLiveQuotes | null> {
  const apiKey = process.env.EXCHANGERATE_API_KEY?.trim();

  if (apiKey) {
    const url = new URL("https://api.exchangerate.host/live");
    url.searchParams.set("access_key", apiKey);
    url.searchParams.set("source", "EUR");
    url.searchParams.set("currencies", CURRENCIES.join(","));
    const response = await fetch(url.toString());
    if (!response.ok) return null;
    const data = (await response.json()) as { quotes?: Record<string, number> };
    const quotes = data.quotes;
    return quotes && typeof quotes === "object" ? quotes : null;
  }

  const response = await fetch("https://api.frankfurter.app/latest?from=EUR");
  if (!response.ok) return null;
  const data = (await response.json()) as { rates?: Record<string, number> };
  const rates = data.rates;
  if (!rates || typeof rates !== "object") return null;
  const quotes: EurLiveQuotes = {};
  for (const c of CURRENCIES) {
    const r = rates[c];
    if (typeof r === "number") quotes[`EUR${c}`] = r;
  }
  return Object.keys(quotes).length ? quotes : null;
}
