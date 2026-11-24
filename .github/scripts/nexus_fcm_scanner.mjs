/**
 * NEXUS TRADING AI — Optional Zero-Cost GitHub Actions FCM Alert Scanner
 *
 * Runs strictly on free-tier GitHub Actions (Node 20 built-in fetch + crypto, zero paid packages).
 * Reads Firebase service account credentials ONLY from env FIREBASE_SERVICE_ACCOUNT_JSON
 * (stored in GitHub Repository Secrets — never in source code or APK).
 *
 * Rules enforced identically to HighRewardOpportunityDetector.kt:
 * - Uses completed candles only (no look-ahead bias)
 * - Never assumes missing/unreliable funding data is zero (WAIT / FUNDING DATA UNAVAILABLE)
 * - Calculates Gross R:R and Net R:R after round-trip fees (0.05%/side), slippage (0.03%/side), and adverse funding
 * - Never widens TP or tightens SL artificially
 * - Sends separate FCM topic alerts:
 *     - Net R:R >= 5.0 -> topic `nexus_high_reward_1_5` ("NEXUS 1:5 HIGH REWARD OPPORTUNITY")
 *     - Net R:R >= 4.0 & < 5.0 -> topic `nexus_qualified_1_4` ("NEXUS QUALIFIED 1:4 OPPORTUNITY")
 * - Formats all trigger timestamps in Asia/Karachi (PKT)
 * - Deduplicates via deterministic `event_id` persisted in cache
 * - Alert & analysis only — NEVER places trades
 */

import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";

const WATCHLIST = [
  "BTCUSDT",
  "ETHUSDT",
  "SOLUSDT",
  "BNBUSDT",
  "XRPUSDT",
  "DOGEUSDT",
  "ADAUSDT",
  "AVAXUSDT",
  "LINKUSDT",
  "SUIUSDT",
];

const FEE_PER_SIDE = 0.0005; // 0.05%
const SLIP_PER_SIDE = 0.0003; // 0.03%
const FUNDING_INTERVALS = 1.0;
const MIN_SL_PCT = 0.25;
const MAX_SL_PCT = 6.5;
const MAX_FUNDING_AGE_MS = 10 * 60 * 1000;
const SETUP_EXPIRY_MS = 12 * 15 * 60 * 1000; // 3 hours (12 x 15m bars)
const STATE_DIR = path.resolve(process.cwd(), ".nexus-fcm-cache");
const STATE_FILE = path.join(STATE_DIR, "sent_events.json");

function formatPktTimestamp(ms) {
  const dt = new Date(ms);
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Asia/Karachi",
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: true,
  }).formatToParts(dt);
  const get = (type) => parts.find((p) => p.type === type)?.value || "";
  return `${get("day")} ${get("month")} ${get("year")}, ${get("hour")}:${get("minute")}:${get("second")} ${get("dayPeriod")} PKT`;
}

function fmtPrice(p) {
  if (!Number.isFinite(p)) return "$0.00";
  if (p >= 100) return `$${p.toFixed(2)}`;
  if (p >= 1) return `$${p.toFixed(3)}`;
  if (p >= 0.01) return `$${p.toFixed(4)}`;
  return `$${p.toFixed(6)}`;
}

async function fetchJson(url) {
  const res = await fetch(url, {
    headers: {
      Accept: "application/json",
      "User-Agent":
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
    },
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) {
    const err = new Error(`HTTP ${res.status} from ${url}`);
    err.status = res.status;
    throw err;
  }
  return res.json();
}

function symbolToOkxInstId(symbol) {
  if (symbol.endsWith("USDT")) {
    return `${symbol.slice(0, -4)}-USDT-SWAP`;
  }
  return symbol;
}

function intervalToOkxBar(tf) {
  if (tf === "5m") return "5m";
  if (tf === "15m") return "15m";
  if (tf === "1h") return "1H";
  if (tf === "4h") return "4H";
  return "15m";
}

function intervalMs(tf) {
  if (tf === "5m") return 5 * 60_000;
  if (tf === "15m") return 15 * 60_000;
  if (tf === "1h") return 60 * 60_000;
  if (tf === "4h") return 4 * 60 * 60_000;
  return 15 * 60_000;
}

async function fetchOkxJson(url) {
  const json = await fetchJson(url);
  if (json?.code !== "0") {
    throw new Error(`OKX API error (${json?.code ?? "unknown"}): ${json?.msg || "Invalid response"}`);
  }
  return json;
}

function normalizeOkxCandles(rawRows, tf) {
  const dur = intervalMs(tf);
  return (rawRows || [])
    .slice()
    .reverse()
    .map((k) => {
      const ot = Number(k[0]);
      return {
        openTime: ot,
        open: Number(k[1]),
        high: Number(k[2]),
        low: Number(k[3]),
        close: Number(k[4]),
        volume: Number(k[5]),
        closeTime: ot + dur - 1,
        confirmed: k[8] === undefined ? true : k[8] === "1",
      };
    });
}

async function fetchPairMarketData(symbol) {
  const instId = symbolToOkxInstId(symbol);
  const [c4hRes, c1hRes, c15mRes, fundingRes, tickerRes] = await Promise.all([
    fetchOkxJson(`https://www.okx.com/api/v5/market/candles?instId=${instId}&bar=${intervalToOkxBar("4h")}&limit=30`),
    fetchOkxJson(`https://www.okx.com/api/v5/market/candles?instId=${instId}&bar=${intervalToOkxBar("1h")}&limit=50`),
    fetchOkxJson(`https://www.okx.com/api/v5/market/candles?instId=${instId}&bar=${intervalToOkxBar("15m")}&limit=50`),
    fetchOkxJson(`https://www.okx.com/api/v5/public/funding-rate?instId=${instId}`),
    fetchOkxJson(`https://www.okx.com/api/v5/market/ticker?instId=${instId}`),
  ]);

  const tickerRow = tickerRes?.data?.[0];
  if (!tickerRow) throw new Error(`No OKX ticker data available for ${instId}`);

  const fundingRow = fundingRes?.data?.[0];
  const rawFunding =
    fundingRow && fundingRow.fundingRate !== undefined && fundingRow.fundingRate !== ""
      ? Number(fundingRow.fundingRate) * 100
      : NaN;
  const rawFundingTs =
    fundingRow && fundingRow.ts !== undefined && fundingRow.ts !== "" ? Number(fundingRow.ts) : NaN;

  return {
    provider: "OKX Public Perpetual SWAP",
    candles4h: normalizeOkxCandles(c4hRes?.data, "4h"),
    candles1h: normalizeOkxCandles(c1hRes?.data, "1h"),
    candles15m: normalizeOkxCandles(c15mRes?.data, "15m"),
    fundingRatePercent: Number.isFinite(rawFunding) ? rawFunding : null,
    fundingTimeMs: Number.isFinite(rawFundingTs) && rawFundingTs > 0 ? rawFundingTs : null,
    currentPrice: Number(tickerRow.last),
  };
}

function calcEma(values, period) {
  if (!values.length) return null;
  const k = 2 / (period + 1);
  let ema = values[0];
  for (let i = 1; i < values.length; i++) {
    ema = values[i] * k + ema * (1 - k);
  }
  return ema;
}

function calcRsi14(closes) {
  if (closes.length < 15) return 50;
  let gains = 0;
  let losses = 0;
  for (let i = 1; i <= 14; i++) {
    const diff = closes[i] - closes[i - 1];
    if (diff >= 0) gains += diff;
    else losses -= diff;
  }
  let avgGain = gains / 14;
  let avgLoss = losses / 14;
  for (let i = 15; i < closes.length; i++) {
    const diff = closes[i] - closes[i - 1];
    avgGain = (avgGain * 13 + (diff > 0 ? diff : 0)) / 14;
    avgLoss = (avgLoss * 13 + (diff < 0 ? -diff : 0)) / 14;
  }
  if (avgLoss === 0) return 100;
  const rs = avgGain / avgLoss;
  return 100 - 100 / (1 + rs);
}

function calcAtr14(candles) {
  if (candles.length < 2) return candles[0]?.close * 0.01 || 1;
  const trs = [];
  for (let i = 1; i < candles.length; i++) {
    const c = candles[i];
    const p = candles[i - 1];
    trs.push(Math.max(c.high - c.low, Math.abs(c.high - p.close), Math.abs(c.low - p.close)));
  }
  const slice = trs.slice(-14);
  return slice.reduce((a, b) => a + b, 0) / slice.length;
}

function findSwings(candles) {
  const highs = [];
  const lows = [];
  for (let i = 2; i < candles.length - 2; i++) {
    const c = candles[i];
    if (c.high >= candles[i - 1].high && c.high >= candles[i - 2].high && c.high > candles[i + 1].high && c.high > candles[i + 2].high) {
      highs.push(c.high);
    }
    if (c.low <= candles[i - 1].low && c.low <= candles[i - 2].low && c.low < candles[i + 1].low && c.low < candles[i + 2].low) {
      lows.push(c.low);
    }
  }
  return { highs, lows };
}

function evaluateHighRewardSetup(symbol, data, nowMs) {
  if (!Number.isFinite(data.currentPrice) || data.currentPrice <= 0) return null;
  const completed15m = data.candles15m.filter((c) => c.confirmed !== false && c.closeTime <= nowMs);
  const completed1h = data.candles1h.filter((c) => c.confirmed !== false && c.closeTime <= nowMs);
  const completed4h = data.candles4h.filter((c) => c.confirmed !== false && c.closeTime <= nowMs);
  if (completed15m.length < 20 || completed1h.length < 20) return null;

  // Strictly reject missing, stale, or unreliable funding (NEVER assume 0%)
  const fr = data.fundingRatePercent;
  if (fr === null || !Number.isFinite(fr) || Math.abs(fr) > 5.0) {
    console.log(`[${symbol}] WAIT / FUNDING DATA UNAVAILABLE (rate=${fr})`);
    return null;
  }
  if (data.fundingTimeMs !== null && Math.abs(nowMs - data.fundingTimeMs) > MAX_FUNDING_AGE_MS) {
    console.log(`[${symbol}] WAIT / FUNDING DATA UNAVAILABLE (stale funding timestamp)`);
    return null;
  }

  const price = data.currentPrice;
  const latest15m = completed15m[completed15m.length - 1];
  if (Math.abs(price - latest15m.close) / latest15m.close > 0.15) return null;

  const closes1h = completed1h.map((c) => c.close);
  const closes15m = completed15m.map((c) => c.close);
  const ema50_1h = calcEma(closes1h, 50) ?? price;
  const ema200_1h = calcEma(closes1h, 200) ?? price;
  const ema50_4h = completed4h.length >= 15 ? calcEma(completed4h.map((c) => c.close), 50) ?? price : ema50_1h;
  const rsi15m = calcRsi14(closes15m);
  const atr15m = Math.max(calcAtr14(completed15m), price * 0.002);
  const atr1h = Math.max(calcAtr14(completed1h), atr15m);
  const atr4h = completed4h.length >= 15 ? Math.max(calcAtr14(completed4h), atr1h) : atr1h * 2.2;

  const avgVol = completed15m.slice(-20).reduce((s, c) => s + c.volume, 0) / 20;
  const rvol15m = avgVol > 0 ? latest15m.volume / avgVol : 1;

  const swings15m = findSwings(completed15m);
  const swings1h = findSwings(completed1h);
  const swings4h = completed4h.length >= 15 ? findSwings(completed4h) : swings1h;

  const candidates = [];
  for (const dir of ["LONG", "SHORT"]) {
    const isLong = dir === "LONG";
    let conf = 0;

    const htfAligned = isLong
      ? price >= ema50_1h && (ema50_1h >= ema200_1h || price >= ema50_4h)
      : price <= ema50_1h && (ema50_1h <= ema200_1h || price <= ema50_4h);
    if (htfAligned) conf += 25;

    const lastSwingHigh = swings15m.highs[swings15m.highs.length - 1];
    const lastSwingLow = swings15m.lows[swings15m.lows.length - 1];
    const structureBreak = isLong
      ? lastSwingHigh !== undefined && latest15m.close > lastSwingHigh
      : lastSwingLow !== undefined && latest15m.close < lastSwingLow;
    if (structureBreak) conf += 25;

    if (rsi15m >= 32 && rsi15m <= 68) conf += 15;
    if (rvol15m >= 1.1) conf += 15;

    // Require minimum structural confluence (60%+)
    if (conf < 60) continue;

    const priorCloses = completed15m.slice(-8, -1).map((c) => c.close);
    const triggerLevel = isLong ? Math.max(...priorCloses) : Math.min(...priorCloses);
    const confirmedClose = isLong
      ? latest15m.close >= triggerLevel && latest15m.close >= latest15m.open
      : latest15m.close <= triggerLevel && latest15m.close <= latest15m.open;
    if (!confirmedClose) continue;

    const buffer = atr15m * 0.25;
    const recent14 = completed15m.slice(-14);
    const sl = isLong
      ? Math.min(Math.min(...recent14.map((c) => c.low)) - buffer, price * (1 - MIN_SL_PCT / 100))
      : Math.max(Math.max(...recent14.map((c) => c.high)) + buffer, price * (1 + MIN_SL_PCT / 100));

    const slDist = Math.abs(price - sl);
    const slPct = (slDist / price) * 100;
    if (slPct < MIN_SL_PCT || slPct > MAX_SL_PCT) continue;

    const maxReach = Math.min(Math.max(atr4h * 3.5, atr1h * 6.0, atr15m * 14.0), price * 0.25);
    const tpPool = [
      ...swings4h.highs,
      ...swings4h.lows,
      ...swings1h.highs,
      ...swings1h.lows,
      isLong ? Math.max(...completed15m.map((c) => c.high)) : Math.min(...completed15m.map((c) => c.low)),
    ].filter((lvl) => (isLong ? lvl > price && lvl - price <= maxReach : lvl > 0 && lvl < price && price - lvl <= maxReach));

    if (!tpPool.length) continue;
    const tp = isLong ? Math.max(...tpPool) : Math.min(...tpPool);
    const tpDist = Math.abs(tp - price);

    const grossRR = tpDist / slDist;
    const fundingDec = fr / 100;
    const signedFunding = isLong ? fundingDec : -fundingDec;
    const adverseFundingFrac = Math.max(0, signedFunding) * FUNDING_INTERVALS;

    const netLoss = slDist + price * (FEE_PER_SIDE + SLIP_PER_SIDE) + sl * (FEE_PER_SIDE + SLIP_PER_SIDE) + price * adverseFundingFrac;
    const netProfit = tpDist - price * (FEE_PER_SIDE + SLIP_PER_SIDE) - tp * (FEE_PER_SIDE + SLIP_PER_SIDE) - price * adverseFundingFrac;
    const netRR = netLoss > 0 ? netProfit / netLoss : 0;

    if (netRR < 4.0) continue;

    const triggerTs = latest15m.closeTime;
    const triggerPkt = formatPktTimestamp(triggerTs);
    const isTop15 = netRR >= 5.0;
    const tierName = isTop15 ? "HIGHEST_1_TO_5_PLUS" : "QUALIFIED_1_TO_4";
    const topic = isTop15 ? "nexus_high_reward_1_5" : "nexus_qualified_1_4";
    const alertType = isTop15 ? "OPPORTUNITY_1_TO_5" : "OPPORTUNITY_1_TO_4";
    const title = isTop15
      ? `NEXUS 1:5 HIGH REWARD OPPORTUNITY • ${symbol} ${dir}`
      : `NEXUS QUALIFIED 1:4 OPPORTUNITY (1:${netRR.toFixed(2)}) • ${symbol} ${dir}`;

    candidates.push({
      symbol,
      direction: dir,
      topic,
      alertType,
      priority: isTop15 ? "HIGH" : "NORMAL",
      title,
      eventId: `hr_${symbol}_${dir}_${triggerTs}_${tierName}`,
      triggerTs,
      triggerPkt,
      expiresAtMs: nowMs + SETUP_EXPIRY_MS,
      entryPrice: price,
      stopLoss: sl,
      takeProfit: tp,
      grossRR,
      netRR,
      fundingRatePercent: fr,
      provider: data.provider,
    });
  }

  candidates.sort((a, b) => b.netRR - a.netRR);
  return candidates[0] || null;
}

async function getGoogleAccessToken(sa) {
  const nowSec = Math.floor(Date.now() / 1000);
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const claim = Buffer.from(
    JSON.stringify({
      iss: sa.client_email,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: nowSec,
      exp: nowSec + 3600,
    })
  ).toString("base64url");
  const unsigned = `${header}.${claim}`;
  const sign = crypto.createSign("RSA-SHA256");
  sign.update(unsigned);
  const signature = sign.sign(sa.private_key, "base64url");
  const jwt = `${unsigned}.${signature}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  if (!res.ok) {
    throw new Error(`OAuth token exchange failed: HTTP ${res.status}`);
  }
  const json = await res.json();
  return json.access_token;
}

async function sendFcmTopicAlert(accessToken, projectId, opp) {
  const bodyText =
    `${opp.symbol} ${opp.direction} | Trigger (PKT): ${opp.triggerPkt}\n` +
    `Entry: ${fmtPrice(opp.entryPrice)} | SL: ${fmtPrice(opp.stopLoss)} | TP: ${fmtPrice(opp.takeProfit)}\n` +
    `Net R:R 1:${opp.netRR.toFixed(2)} (Gross 1:${opp.grossRR.toFixed(2)}) | $1 risk -> $${opp.netRR.toFixed(2)} net\n` +
    `Fees 0.10% RT + Slip 0.06% RT + Funding ${opp.fundingRatePercent >= 0 ? "+" : ""}${opp.fundingRatePercent.toFixed(4)}%\n` +
    `Source: ${opp.provider} | Alert Only — Not an Executed Order`;

  const payload = {
    message: {
      topic: opp.topic,
      android: {
        priority: opp.priority,
        ttl: "10800s",
      },
      data: {
        event_id: opp.eventId,
        symbol: opp.symbol,
        direction: opp.direction,
        alert_type: opp.alertType,
        title: opp.title,
        body: bodyText,
        entry_price: fmtPrice(opp.entryPrice),
        stop_loss: fmtPrice(opp.stopLoss),
        take_profit: fmtPrice(opp.takeProfit),
        gross_rr: opp.grossRR.toFixed(2),
        net_rr: opp.netRR.toFixed(2),
        trigger_pkt: opp.triggerPkt,
        timestamp_ms: String(opp.triggerTs),
        expires_at_ms: String(opp.expiresAtMs),
        open_workflow: "true",
      },
    },
  };

  const res = await fetch(`https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });
  if (!res.ok) {
    const errBody = await res.text();
    throw new Error(`FCM send failed (${res.status}): ${errBody}`);
  }
  return res.json();
}

function loadSentCache() {
  try {
    if (!fs.existsSync(STATE_FILE)) return {};
    const parsed = JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
    const cutoff = Date.now() - 48 * 60 * 60 * 1000;
    const pruned = {};
    for (const [k, ts] of Object.entries(parsed)) {
      if (Number(ts) >= cutoff) pruned[k] = ts;
    }
    return pruned;
  } catch {
    return {};
  }
}

function saveSentCache(cache) {
  fs.mkdirSync(STATE_DIR, { recursive: true });
  fs.writeFileSync(STATE_FILE, JSON.stringify(cache, null, 2));
}

async function main() {
  const rawSecret = process.env.FIREBASE_SERVICE_ACCOUNT_JSON?.trim();
  if (!rawSecret) {
    console.log("Optional FIREBASE_SERVICE_ACCOUNT_JSON GitHub Secret is not configured. Skipping remote FCM push.");
    return;
  }

  let serviceAccount;
  try {
    serviceAccount = JSON.parse(rawSecret);
  } catch (e) {
    throw new Error(`FIREBASE_SERVICE_ACCOUNT_JSON is not valid JSON: ${e.message}`);
  }

  const projectId = serviceAccount.project_id || "gen-lang-client-0963937245";
  const nowMs = Date.now();
  const sentCache = loadSentCache();
  const opportunities = [];

  for (const symbol of WATCHLIST) {
    try {
      const marketData = await fetchPairMarketData(symbol);
      const opp = evaluateHighRewardSetup(symbol, marketData, nowMs);
      if (opp) {
        if (sentCache[opp.eventId]) {
          console.log(`[${symbol}] Duplicate event suppressed: ${opp.eventId}`);
        } else {
          opportunities.push(opp);
        }
      }
    } catch (e) {
      console.warn(`[${symbol}] Skipped due to data error: ${e.message}`);
    }
    await new Promise((r) => setTimeout(r, 150));
  }

  if (!opportunities.length) {
    console.log(`Scan completed at ${formatPktTimestamp(nowMs)}: WAIT — No new confirmed >=1:4 / >=1:5 setups.`);
    saveSentCache(sentCache);
    return;
  }

  const accessToken = await getGoogleAccessToken(serviceAccount);
  for (const opp of opportunities) {
    const resp = await sendFcmTopicAlert(accessToken, projectId, opp);
    sentCache[opp.eventId] = nowMs;
    console.log(`Sent FCM alert [${opp.alertType}] ${opp.symbol} (Net 1:${opp.netRR.toFixed(2)}, Gross 1:${opp.grossRR.toFixed(2)}) -> ${resp.name}`);
  }
  saveSentCache(sentCache);
}

main().catch((err) => {
  console.error("NEXUS FCM Scanner failed:", err.message);
  process.exit(1);
});
