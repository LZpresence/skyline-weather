/* Skyline Weather — National Weather Service forecast, no ads, no tracking.
   Data: api.weather.gov (forecast, hourly, grid, observations, alerts),
   air-quality-api.open-meteo.com (US AQI + UV; NWS publishes no air quality),
   geocoding-api.open-meteo.com (city search). Sunrise/sunset computed locally. */
'use strict';

const NWS = 'https://api.weather.gov';
const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch { /* storage unavailable */ } },
};

let state = { loc: store.get('wx.loc', null), data: null, loading: false };

/* ---------- units & helpers ---------- */
const cToF = (c) => (c == null ? null : c * 9 / 5 + 32);
const kmhToMph = (k) => (k == null ? null : k * 0.621371);
const mToMi = (m) => (m == null ? null : m / 1609.344);
const paToInHg = (p) => (p == null ? null : p / 3386.39);
const r = (x) => (x == null || isNaN(x) ? '–' : Math.round(x));
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const DIRS = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE', 'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW'];
const dirToDeg = (d) => { const i = DIRS.indexOf(d); return i < 0 ? null : i * 22.5; };
const degToDir = (g) => (g == null ? '' : DIRS[Math.round(g / 22.5) % 16]);
const windMax = (s) => { const n = String(s || '').match(/\d+/g); return n ? Math.max(...n.map(Number)) : 0; };

const fmtCache = {};
function local(date, tz) {
  const key = 'p' + tz;
  const f = fmtCache[key] || (fmtCache[key] = new Intl.DateTimeFormat('en-US', {
    timeZone: tz, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', hourCycle: 'h23', weekday: 'short',
  }));
  const o = {};
  for (const p of f.formatToParts(date)) o[p.type] = p.value;
  return { ymd: `${o.year}-${o.month}-${o.day}`, hour: Number(o.hour) % 24, wd: o.weekday };
}
function fmtTime(date, tz, withMin = true) {
  return new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric', minute: withMin ? '2-digit' : undefined }).format(date);
}
function fmtHour(date, tz) {
  return new Intl.DateTimeFormat('en-US', { timeZone: tz, hour: 'numeric' }).format(date).replace(' ', '').toLowerCase();
}

async function getJSON(url, tries = 3) {
  let last;
  for (let i = 0; i < tries; i++) {
    try {
      const res = await fetch(url);
      if (res.ok) return await res.json();
      last = new Error(`${res.status} from ${new URL(url).host}`);
      last.status = res.status;
      if (res.status < 500) break; // NWS 5xx errors are often transient; 4xx are not
    } catch (e) { last = e; }
    await new Promise((ok) => setTimeout(ok, 700 * (i + 1)));
  }
  throw last;
}

/* NWS grid values come as ISO intervals: "2026-11-07T18:00:00+00:00/PT3H" */
function durMs(d) {
  const m = d.match(/P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?)?/);
  return m ? ((+m[1] || 0) * 864e5 + (+m[2] || 0) * 36e5 + (+m[3] || 0) * 6e4) : 36e5;
}
function gridSeries(prop) {
  if (!prop || !prop.values) return [];
  return prop.values.map((v) => {
    const [s, d] = v.validTime.split('/');
    const start = Date.parse(s);
    return { start, end: start + durMs(d), value: v.value };
  });
}
function gridAt(series, t) {
  for (const s of series) if (t >= s.start && t < s.end) return s.value;
  return null;
}

/* ---------- sun position (after V. Agafonkin's SunCalc, NOAA formulas) ---------- */
function sunTimes(date, lat, lng) {
  const rad = Math.PI / 180, dayMs = 864e5, J1970 = 2440588, J2000 = 2451545, e = rad * 23.4397, J0 = 0.0009;
  const toDays = (d) => d.valueOf() / dayMs - 0.5 + J1970 - J2000;
  const fromJulian = (j) => new Date((j + 0.5 - J1970) * dayMs);
  const lw = rad * -lng, phi = rad * lat, d = toDays(date);
  const n = Math.round(d - J0 - lw / (2 * Math.PI));
  const ds = J0 + lw / (2 * Math.PI) + n;
  const M = rad * (357.5291 + 0.98560028 * ds);
  const C = rad * (1.9148 * Math.sin(M) + 0.02 * Math.sin(2 * M) + 0.0003 * Math.sin(3 * M));
  const L = M + C + rad * 102.9372 + Math.PI;
  const dec = Math.asin(Math.sin(e) * Math.sin(L));
  const Jnoon = J2000 + ds + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * L);
  const h = -0.833 * rad;
  const cosW = (Math.sin(h) - Math.sin(phi) * Math.sin(dec)) / (Math.cos(phi) * Math.cos(dec));
  if (cosW > 1 || cosW < -1) return null; // polar day or night
  const w = Math.acos(cosW);
  const a = J0 + (w + lw) / (2 * Math.PI) + n;
  const Jset = J2000 + a + 0.0053 * Math.sin(M) - 0.0069 * Math.sin(2 * L);
  return { sunrise: fromJulian(Jnoon - (Jset - Jnoon)), sunset: fromJulian(Jset), noon: fromJulian(Jnoon) };
}

/* ---------- icons ---------- */
const SUN = '<circle cx="24" cy="24" r="9" fill="var(--accent)"/><g stroke="var(--accent)" stroke-width="3" stroke-linecap="round"><path d="M24 5v5M24 38v5M5 24h5M38 24h5M10.6 10.6l3.5 3.5M33.9 33.9l3.5 3.5M10.6 37.4l3.5-3.5M33.9 14.1l3.5-3.5"/></g>';
const MOON = '<path d="M30 8a15 15 0 1 0 12 22A13 13 0 0 1 30 8z" fill="#c9d6e3"/>';
const CLOUD = (y = 0, o = 1) => `<path transform="translate(0 ${y})" d="M14 38h22a8 8 0 0 0 0-16 11 11 0 0 0-21-2 8 8 0 0 0-1 18z" fill="currentColor" opacity="${o}"/>`;
function iconFor(text, isDay = true) {
  const t = String(text || '').toLowerCase();
  const sky = isDay ? '<g transform="translate(-6 -8) scale(.8)">' + SUN + '</g>' : '<g transform="translate(-4 -6) scale(.8)">' + MOON + '</g>';
  let body;
  if (/thunder|t-storm/.test(t)) body = CLOUD(-6, .9) + '<path d="M25 30l-5 9h5l-3 8 9-11h-5l3-6z" fill="var(--accent)"/>';
  else if (/snow|flurr|sleet|ice|wintry/.test(t)) body = CLOUD(-6, .9) + '<g fill="currentColor"><circle cx="17" cy="40" r="2"/><circle cx="25" cy="44" r="2"/><circle cx="33" cy="40" r="2"/></g>';
  else if (/rain|shower|drizzle/.test(t)) body = (/chance|slight|isolated|scattered/.test(t) ? sky : '') + CLOUD(-6, .9) + '<g stroke="var(--rain)" stroke-width="2.6" stroke-linecap="round"><path d="M17 37l-2 6M25 37l-2 6M33 37l-2 6"/></g>';
  else if (/fog|haze|smoke|mist/.test(t)) body = '<g stroke="currentColor" stroke-width="3" stroke-linecap="round" opacity=".8"><path d="M9 18h30M6 26h36M10 34h28"/></g>';
  else if (/overcast|^cloudy|mostly cloudy/.test(t)) body = CLOUD(-4, .55).replace('translate(0 -4)', 'translate(6 -8) scale(.85)') + CLOUD(0, .95);
  else if (/partly|mostly sunny|mostly clear|few clouds|cloud/.test(t)) body = sky + CLOUD(2, .95);
  else if (/wind|breezy|blustery/.test(t)) body = (isDay ? '' : '') + '<g fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M6 18h24a5 5 0 1 0-5-5M6 26h32a5 5 0 1 1-5 5M6 34h16"/></g>';
  else body = isDay ? SUN : '<g transform="translate(2 2)">' + MOON + '</g>';
  return `<svg class="ic" viewBox="0 0 48 48" aria-hidden="true">${body}</svg>`;
}

/* ---------- weather words ---------- */
function aqiInfo(a) {
  if (a == null) return null;
  const cats = [
    [50, 'Good', 'var(--good)', 'Air quality is fine for everyone.'],
    [100, 'Moderate', 'var(--mod)', 'Unusually sensitive people may want to ease off long outdoor exertion.'],
    [150, 'Unhealthy for sensitive groups', 'var(--usg)', 'Kids, older adults and people with asthma or heart conditions should limit long outdoor exertion.'],
    [200, 'Unhealthy', 'var(--bad)', 'Everyone should cut back on prolonged outdoor exertion.'],
    [300, 'Very unhealthy', 'var(--vbad)', 'Avoid prolonged outdoor exertion; sensitive groups stay indoors.'],
    [Infinity, 'Hazardous', 'var(--haz)', 'Stay indoors with windows closed.'],
  ];
  const edges = [0, 50, 100, 150, 200, 300, 500];
  const i = cats.findIndex((c) => a <= c[0]);
  const pos = (i + Math.min(1, (a - edges[i]) / (edges[i + 1] - edges[i]))) / 6;
  return { name: cats[i][1], color: cats[i][2], note: cats[i][3], pos: Math.max(0, Math.min(1, pos)) };
}

function feelsLikeF(tF, rh, mph) {
  if (tF == null) return null;
  if (tF <= 50 && mph >= 3) return 35.74 + 0.6215 * tF - 35.75 * mph ** 0.16 + 0.4275 * tF * mph ** 0.16;
  if (tF >= 80 && rh != null) {
    return -42.379 + 2.04901523 * tF + 10.14333127 * rh - 0.22475541 * tF * rh - 6.83783e-3 * tF * tF
      - 5.481717e-2 * rh * rh + 1.22874e-3 * tF * tF * rh + 8.5282e-4 * tF * rh * rh - 1.99e-6 * tF * tF * rh * rh;
  }
  return tF;
}

/* Apparel rules. Input describes a stretch of time; output is a list of {icon,item,why,short}. */
function wearFor(c) {
  const out = [];
  const lo = c.feelsMin, hi = c.feelsMax;
  // keys drive Kikker's outfit (see kikker.js)
  const add = (icon, item, why, short, keys = []) => out.push({ icon, item, why, short: short || item, keys });
  if (lo == null) return out;
  if (lo < 15) add('🧥', 'Insulated winter coat', `Feels as cold as ${r(lo)}°.`, 'Winter coat', ['coat-winter']);
  else if (lo < 32) add('🧥', 'Heavy coat', `Feels like ${r(lo)}° at the coldest.`, 'Heavy coat', ['coat-heavy']);
  else if (lo < 45) add('🧥', 'Warm jacket', `Feels like ${r(lo)}° at the coldest.`, 'Warm jacket', ['coat-warm']);
  else if (lo < 58) add('🧥', 'Light jacket or fleece', `Down to about ${r(lo)}°.`, 'Light jacket', ['jacket-light']);
  else if (lo < 66) add('👕', 'Long sleeves', `Mild, around ${r(lo)}° at the coolest.`, 'Long sleeves', ['sleeves']);
  if (lo < 25) add('🧤', 'Hat, gloves and scarf', 'Exposed skin gets cold fast at these temperatures.', 'Hat & gloves', ['hat', 'gloves', 'scarf']);
  else if (lo < 38) add('🧤', 'Hat and gloves', 'Handy for the coldest part of the day.', 'Gloves', ['hat', 'gloves']);
  if (hi >= 85) add('🩳', 'Shorts and a breathable tee', `Feels like ${r(hi)}° at the warmest.${c.humid ? ' Humid, so pick moisture-wicking fabric.' : ''}`, 'Shorts & tee', ['tee', 'shorts']);
  else if (hi >= 74) add('👕', 'T-shirt', `Warms up to around ${r(hi)}°.`, 'T-shirt', ['tee']);
  else if (hi >= 66 && lo >= 58) add('👕', 'T-shirt with jeans or chinos', `Comfortable, ${r(lo)}–${r(hi)}°.`, 'T-shirt', ['tee', 'pants']);
  else if (lo < 66) add('👖', 'Long pants', '', 'Long pants', ['pants']);
  if (hi - lo >= 15) add('🧅', 'Dress in layers', `Swings ${r(hi - lo)}° from ${r(lo)}° to ${r(hi)}°.`, 'Layers');
  if (c.snow) add('🥾', 'Waterproof boots', 'Snow or ice in the forecast.', 'Boots', ['boots']);
  if (c.popMax >= 50 || c.thunder) add('☔', 'Rain jacket and umbrella', `${r(c.popMax)}% chance of ${c.snow ? 'precipitation' : 'rain'}${c.thunder ? ', with possible thunderstorms' : ''}.`, 'Umbrella', ['umbrella', 'boots']);
  else if (c.popMax >= 25) add('🌂', 'Pack a small umbrella', `${r(c.popMax)}% chance of ${c.snow ? 'precipitation' : 'rain'}.`, 'Maybe umbrella', ['umbrella-maybe']);
  if (c.windMax >= 20) add('🌬️', 'Wind-resistant outer layer', `Winds or gusts up to ${r(c.windMax)} mph.`, 'Windbreaker', ['wind']);
  if (c.uvMax >= 6 || (c.sunny && hi >= 70)) add('🕶️', 'Sunglasses and sunscreen', c.uvMax ? `UV index up to ${r(c.uvMax)}.` : 'Plenty of sun.', 'Sunscreen', ['sun']);
  if (c.aqi != null && c.aqi > 100) add('😷', 'Consider a mask outdoors', `Air quality index ${r(c.aqi)}.`, 'Mask', ['mask']);
  return out;
}

/* ---------- data load ---------- */
async function loadAll(loc) {
  const key = `wx.pt.${loc.lat.toFixed(3)},${loc.lon.toFixed(3)}`;
  let pt = store.get(key, null);
  if (!pt) {
    let p;
    try { p = await getJSON(`${NWS}/points/${loc.lat.toFixed(4)},${loc.lon.toFixed(4)}`); }
    catch (e) {
      if (e.status === 404) throw new Error('The National Weather Service only covers the United States and its territories. Pick a US location.');
      throw e;
    }
    const pp = p.properties, rl = pp.relativeLocation && pp.relativeLocation.properties;
    pt = { forecast: pp.forecast, hourly: pp.forecastHourly, grid: pp.forecastGridData, stations: pp.observationStations,
      tz: pp.timeZone, place: rl ? `${rl.city}, ${rl.state}` : null, office: pp.cwa };
    store.set(key, pt);
  }
  const lat = loc.lat.toFixed(4), lon = loc.lon.toFixed(4);
  const aqUrl = `https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${lat}&longitude=${lon}`
    + '&current=us_aqi,us_aqi_pm2_5,us_aqi_ozone,us_aqi_pm10,uv_index&hourly=us_aqi,uv_index&timezone=auto&forecast_days=2';

  const [fc, hr, grid, alerts, obs, aq] = await Promise.allSettled([
    getJSON(pt.forecast), getJSON(pt.hourly), getJSON(pt.grid),
    getJSON(`${NWS}/alerts/active?point=${lat},${lon}`, 2),
    latestObs(pt.stations), getJSON(aqUrl, 2),
  ]);
  if (hr.status !== 'fulfilled') throw new Error('The National Weather Service hourly forecast did not respond. Pull to refresh in a minute.');
  return {
    at: Date.now(), pt,
    daily: fc.status === 'fulfilled' ? fc.value.properties.periods : [],
    hourly: hr.value.properties.periods,
    grid: grid.status === 'fulfilled' ? grid.value.properties : null,
    alerts: alerts.status === 'fulfilled' ? alerts.value.features.map((f) => f.properties) : [],
    obs: obs.status === 'fulfilled' ? obs.value : null,
    aq: aq.status === 'fulfilled' ? aq.value : null,
  };
}

async function latestObs(stationsUrl) {
  const st = await getJSON(stationsUrl, 2);
  for (const f of st.features.slice(0, 3)) {
    try {
      const o = await getJSON(`${f.properties['@id'] || NWS + '/stations/' + f.properties.stationIdentifier}/observations/latest`, 1);
      const p = o.properties;
      if (p.temperature && p.temperature.value != null && Date.now() - Date.parse(p.timestamp) < 3 * 36e5) {
        return { ...p, stationName: f.properties.name, stationId: f.properties.stationIdentifier };
      }
    } catch { /* try next station */ }
  }
  return null;
}

/* ---------- derive ---------- */
function derive(d, loc) {
  const tz = d.pt.tz, now = Date.now();
  const g = d.grid || {};
  const appT = gridSeries(g.apparentTemperature), vis = gridSeries(g.visibility), gust = gridSeries(g.windGust);
  // NWS "isDaytime" means 6am–6pm; use real sunrise/sunset for day vs night icons
  const sunCache = {};
  const sunOn = (ymd) => sunCache[ymd] !== undefined ? sunCache[ymd]
    : (sunCache[ymd] = sunTimes(new Date(Date.parse(ymd + 'T12:00:00Z') - loc.lon / 15 * 36e5), loc.lat, loc.lon));
  const isDayAt = (t, ymd) => {
    const s = sunOn(ymd);
    return s ? t >= s.sunrise.getTime() && t < s.sunset.getTime() : true;
  };

  const hours = d.hourly.map((p) => {
    const t = Date.parse(p.startTime), l = local(new Date(t), tz);
    const tF = p.temperatureUnit === 'C' ? cToF(p.temperature) : p.temperature;
    const rh = p.relativeHumidity ? p.relativeHumidity.value : null;
    const ws = windMax(p.windSpeed);
    const at = gridAt(appT, t);
    return {
      t, end: Date.parse(p.endTime), ...l, temp: tF, text: p.shortForecast, isDay: isDayAt(t + 18e5, l.ymd),
      pop: (p.probabilityOfPrecipitation && p.probabilityOfPrecipitation.value) || 0, rh,
      dew: p.dewpoint ? cToF(p.dewpoint.value) : null, wind: ws, dir: p.windDirection,
      gust: kmhToMph(gridAt(gust, t)), feels: at != null ? cToF(at) : feelsLikeF(tF, rh, ws),
    };
  }).filter((h) => h.end > now);

  // UV & AQI hourly lookups keyed by local "YYYY-MM-DDTHH"
  const aqH = {}, uvH = {};
  if (d.aq && d.aq.hourly) d.aq.hourly.time.forEach((t, i) => { aqH[t.slice(0, 13)] = d.aq.hourly.us_aqi[i]; uvH[t.slice(0, 13)] = d.aq.hourly.uv_index[i]; });
  const hk = (h) => `${h.ymd}T${String(h.hour).padStart(2, '0')}`;
  hours.forEach((h) => { h.aqi = aqH[hk(h)]; h.uv = uvH[hk(h)]; });

  // current conditions: station observation if fresh, else the current forecast hour
  const h0 = hours[0];
  const o = d.obs;
  const cur = { text: h0.text, isDay: isDayAt(now, local(new Date(now), tz).ymd), temp: h0.temp, rh: h0.rh, dew: h0.dew, wind: h0.wind, dirTxt: h0.dir,
    dirDeg: dirToDeg(h0.dir), gust: h0.gust, feels: h0.feels, vis: mToMi(gridAt(vis, now)), pressure: null, source: 'forecast', at: h0.t };
  if (o) {
    const v = (x) => (x && x.value != null ? x.value : null);
    cur.source = o.stationName || o.stationId; cur.at = Date.parse(o.timestamp);
    cur.temp = cToF(v(o.temperature));
    if (v(o.relativeHumidity) != null) cur.rh = v(o.relativeHumidity);
    if (v(o.dewpoint) != null) cur.dew = cToF(v(o.dewpoint));
    if (v(o.windSpeed) != null) { cur.wind = kmhToMph(v(o.windSpeed)); }
    if (v(o.windDirection) != null) { cur.dirDeg = v(o.windDirection); cur.dirTxt = degToDir(cur.dirDeg); }
    if (v(o.windGust) != null) cur.gust = kmhToMph(v(o.windGust));
    if (v(o.visibility) != null) cur.vis = mToMi(v(o.visibility));
    if (v(o.barometricPressure) != null) cur.pressure = paToInHg(v(o.barometricPressure));
    cur.feels = v(o.heatIndex) != null ? cToF(v(o.heatIndex)) : v(o.windChill) != null ? cToF(v(o.windChill)) : feelsLikeF(cur.temp, cur.rh, cur.wind || 0);
    if (o.textDescription) cur.text = o.textDescription;
  }

  // daily: pair day/night periods by local date
  const days = [];
  for (const p of d.daily) {
    const l = local(new Date(Date.parse(p.startTime)), tz);
    let day = days.find((x) => x.ymd === l.ymd);
    if (!day) { day = { ymd: l.ymd, wd: l.wd, name: p.name.replace(/ ?Night$/, '') }; days.push(day); }
    const pop = (p.probabilityOfPrecipitation && p.probabilityOfPrecipitation.value) || 0;
    if (p.isDaytime) { day.hi = p.temperature; day.dayText = p.shortForecast; day.dayDetail = p.detailedForecast; day.dayName = p.name; day.pop = Math.max(day.pop || 0, pop); }
    else { day.lo = p.temperature; day.nightText = p.shortForecast; day.nightDetail = p.detailedForecast; day.nightName = p.name; day.pop = Math.max(day.pop || 0, pop); }
  }
  if (days[0]) days[0].name = days[0].hi == null ? 'Tonight' : 'Today';

  // morning / afternoon / evening (next three that haven't ended)
  const PARTS = [['Morning', 6, 12], ['Afternoon', 12, 17], ['Evening', 17, 22]];
  const todayYmd = local(new Date(now), tz).ymd;
  const ymds = [...new Set(hours.map((h) => h.ymd))].slice(0, 3);
  const parts = [];
  for (const ymd of ymds) {
    for (const [name, a, b] of PARTS) {
      const hs = hours.filter((h) => h.ymd === ymd && h.hour >= a && h.hour < b);
      if (!hs.length) continue;
      const lbl = ymd === todayYmd ? (name === 'Evening' ? 'This evening' : 'This ' + name.toLowerCase())
        : `${local(new Date(hs[0].t), tz).wd === local(new Date(now + 864e5), tz).wd ? 'Tomorrow' : hs[0].wd} ${name.toLowerCase()}`;
      parts.push({ name: lbl, range: `${fmtHour(new Date(hs[0].t), tz)}–${fmtHour(new Date(hs[hs.length - 1].end), tz)}`, ...summarize(hs) });
    }
  }

  // what to wear: rest of today (through 10 pm), or tomorrow's daytime if it's late
  let span = hours.filter((h) => h.ymd === todayYmd && h.hour < 22);
  let spanLabel = 'today';
  if (span.length < 3) { const tmr = ymds.find((y) => y !== todayYmd); span = hours.filter((h) => h.ymd === tmr && h.hour >= 6 && h.hour < 22); spanLabel = 'tomorrow'; }
  const spanCond = conditions(span);
  const wear = wearFor(spanCond);

  // sun
  const sun = sunOn(todayYmd);
  const sunY = sunOn(local(new Date(now - 864e5), tz).ymd);
  const aqCur = d.aq && d.aq.current ? d.aq.current : null;
  const uvMax = Math.max(0, ...hours.filter((h) => h.ymd === todayYmd && h.uv != null).map((h) => h.uv));

  return { tz, cur, hours, days, parts, wear, spanLabel, spanCond, sun, sunY, aqCur, uvMax, alerts: d.alerts, at: d.at };
}

function conditions(hs) {
  const feels = hs.map((h) => h.feels).filter((x) => x != null);
  const txt = hs.map((h) => h.text.toLowerCase()).join(' ');
  return {
    feelsMin: feels.length ? Math.min(...feels) : null, feelsMax: feels.length ? Math.max(...feels) : null,
    popMax: Math.max(0, ...hs.map((h) => h.pop)), windMax: Math.max(0, ...hs.map((h) => Math.max(h.wind || 0, h.gust || 0))),
    snow: /snow|sleet|ice|flurr|wintry/.test(txt), thunder: /thunder|t-storm/.test(txt),
    sunny: hs.filter((h) => h.isDay && /sunny|clear/.test(h.text.toLowerCase())).length >= hs.length / 2,
    humid: hs.some((h) => h.dew != null && h.dew >= 65),
    uvMax: Math.max(0, ...hs.map((h) => h.uv || 0)), aqi: Math.max(...hs.map((h) => (h.aqi == null ? -1 : h.aqi))) >= 0 ? Math.max(...hs.map((h) => h.aqi ?? -1)) : null,
  };
}

function summarize(hs) {
  const counts = {};
  hs.forEach((h) => { counts[h.text] = (counts[h.text] || 0) + 1; });
  const dominant = Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0];
  const first = hs[0].temp, last = hs[hs.length - 1].temp;
  const tmin = Math.min(...hs.map((h) => h.temp)), tmax = Math.max(...hs.map((h) => h.temp));
  const c = conditions(hs);
  const bits = [dominant + '.'];
  if (last - first >= 4) bits.push(`Warming from ${r(first)}° to ${r(last)}°.`);
  else if (first - last >= 4) bits.push(`Cooling from ${r(first)}° to ${r(last)}°.`);
  else bits.push(tmin === tmax ? `Around ${r(tmin)}°.` : `${r(tmin)}° to ${r(tmax)}°.`);
  const fAvg = c.feelsMin == null ? null : (c.feelsMin + c.feelsMax) / 2;
  if (fAvg != null && Math.abs(fAvg - (tmin + tmax) / 2) >= 5) bits.push(`Feels like ${r(fAvg)}°.`);
  if (c.popMax >= 20) bits.push(`${r(c.popMax)}% chance of ${c.snow ? 'snow' : c.thunder ? 'storms' : 'rain'}.`);
  const dirs = {};
  hs.forEach((h) => { dirs[h.dir] = (dirs[h.dir] || 0) + 1; });
  const dir = Object.entries(dirs).sort((a, b) => b[1] - a[1])[0][0];
  const wmax = Math.max(...hs.map((h) => h.wind)), gmax = Math.max(0, ...hs.map((h) => h.gust || 0));
  if (wmax >= 20 || gmax >= 30) bits.push(`Windy, ${dir} winds to ${r(wmax)} mph${gmax > wmax + 5 ? `, gusts ${r(gmax)}` : ''}.`);
  else if (wmax >= 10) bits.push(`Breezy, ${dir} winds to ${r(wmax)} mph.`);
  else bits.push('Light winds.');
  return { text: dominant, isDay: hs[Math.floor(hs.length / 2)].isDay, summary: bits.join(' '), tmin, tmax, chips: wearFor(c).map((w) => w.short) };
}

/* ---------- render ---------- */
function render() {
  const d = state.data;
  if (!d) return;
  const v = derive(d, state.loc);
  const tz = v.tz, c = v.cur;
  $('placeName').textContent = state.loc.label || d.pt.place || 'Current location';
  const today = v.days[0] || {};
  const age = Math.round((Date.now() - c.at) / 6e4);

  let alertsHtml = v.alerts.map((a, i) => `<button class="alert" data-alert="${i}" aria-expanded="false"><b>${esc(a.event)}</b>${esc(a.headline || '')}<p hidden>${esc(a.description || '')}${a.instruction ? '\n\n' + esc(a.instruction) : ''}</p></button>`).join('');

  $('nowBody').innerHTML = `
    <div class="hero">
      <div>
        <div class="temp num">${r(c.temp)}<sup>°F</sup></div>
        <div class="cond">${esc(c.text)}</div>
        <div class="sub">Feels like ${r(c.feels)}°</div>
      </div>
      <button class="kikker" id="kikkerBtn" aria-label="Kikker's outfit. Show what to wear">${kikkerFor(v)}</button>
    </div>
    <div class="hilo">
      ${today.hi != null ? `<span>High <b class="num">${r(today.hi)}°</b></span>` : ''}
      ${today.lo != null ? `<span>Low <b class="num">${r(today.lo)}°</b></span>` : ''}
      <span>Wind <b>${c.dirTxt || ''} ${r(c.wind)} mph</b></span>
      <span>Humidity <b>${r(c.rh)}%</b></span>
    </div>
    <div class="stamp">${c.source === 'forecast' ? 'From the NWS hourly forecast' : `Observed at ${esc(c.source)}, ${age <= 1 ? 'just now' : age + ' min ago'}`} · Updated ${fmtTime(new Date(v.at), tz)}</div>
    ${alertsHtml}`;

  const partsHtml = v.parts.slice(0, 3).map((p) => `
    <article class="part">
      <div class="pic">${iconFor(p.text, p.isDay)}</div>
      <div><h3>${esc(p.name)} <span class="num">${r(p.tmin)}°–${r(p.tmax)}°</span></h3>
      <p>${esc(p.summary)}</p></div>
      <div class="chips">${p.chips.map((x) => `<span class="chip">${esc(x)}</span>`).join('')}</div>
    </article>`).join('');

  const wearHtml = v.wear.length ? `<ul>${v.wear.map((w) => `<li><i aria-hidden="true">${w.icon}</i><div><b>${esc(w.item)}</b>${w.why ? `<span>${esc(w.why)}</span>` : ''}</div></li>`).join('')}</ul>` : '<p>No forecast hours left to plan around.</p>';

  // details
  const dewWord = c.dew == null ? '' : c.dew < 55 ? 'Comfortable' : c.dew < 65 ? 'A bit sticky' : 'Muggy';
  const visTxt = c.vis == null ? '–' : c.vis >= 9.9 ? '10+' : c.vis >= 1 ? c.vis.toFixed(c.vis < 3 ? 1 : 0) : c.vis.toFixed(2);
  const visWord = c.vis == null ? 'No report nearby' : c.vis >= 9.9 ? 'Clear' : c.vis >= 5 ? 'Good' : c.vis >= 1 ? 'Reduced, haze or precipitation' : 'Poor, likely fog';
  const aqi = v.aqCur ? v.aqCur.us_aqi : null, ai = aqiInfo(aqi);
  let pollutant = '';
  if (v.aqCur) {
    const cand = [['PM2.5', v.aqCur.us_aqi_pm2_5], ['Ozone', v.aqCur.us_aqi_ozone], ['PM10', v.aqCur.us_aqi_pm10]].filter((x) => x[1] != null).sort((a, b) => b[1] - a[1]);
    if (cand.length) pollutant = `Main pollutant: ${cand[0][0]}`;
  }
  const uvNow = v.aqCur ? v.aqCur.uv_index : null;
  const uvWord = (u) => (u < 3 ? 'Low' : u < 6 ? 'Moderate' : u < 8 ? 'High' : u < 11 ? 'Very high' : 'Extreme');

  let sunHtml = '<div class="n">Sun does not rise or set today at this latitude.</div>';
  if (v.sun) {
    const len = v.sun.sunset - v.sun.sunrise, lenY = v.sunY ? v.sunY.sunset - v.sunY.sunrise : len;
    const diff = Math.round((len - lenY) / 6e4 * 10) / 10;
    const frac = Math.max(0, Math.min(1, (Date.now() - v.sun.sunrise) / len));
    const up = Date.now() > v.sun.sunrise && Date.now() < v.sun.sunset;
    const ang = Math.PI * (1 - frac), sx = 100 + 84 * Math.cos(ang), sy = 92 - 84 * Math.sin(ang);
    sunHtml = `
      <svg class="sunarc" viewBox="0 0 200 104" aria-hidden="true">
        <path d="M16 92a84 84 0 0 1 168 0" fill="none" stroke="var(--line)" stroke-width="2" stroke-dasharray="4 5"/>
        <line x1="4" y1="92" x2="196" y2="92" stroke="var(--line)" stroke-width="1.5"/>
        ${up ? `<circle cx="${sx.toFixed(1)}" cy="${sy.toFixed(1)}" r="8" fill="var(--accent)"/>` : ''}
      </svg>
      <div class="sunrow"><div>Sunrise<br><b>${fmtTime(v.sun.sunrise, tz)}</b></div><div style="text-align:right">Sunset<br><b>${fmtTime(v.sun.sunset, tz)}</b></div></div>
      <div class="n">${Math.floor(len / 36e5)} h ${Math.round(len % 36e5 / 6e4)} min of daylight, ${Math.abs(diff)} min ${diff < 0 ? 'shorter' : 'longer'} than yesterday.</div>`;
  }

  const detailsHtml = `
    <div class="grid">
      <div class="tile"><div class="k">Humidity</div><div class="v num">${r(c.rh)}<small>%</small></div><div class="n">Dew point ${r(c.dew)}°. ${dewWord}</div></div>
      <div class="tile"><div class="k">Wind</div><div class="v num">${r(c.wind)}<small>mph</small></div>
        <div class="n">${c.dirDeg != null ? `<span class="compass" style="transform:rotate(${c.dirDeg + 180}deg)">↑</span> ` : ''}From the ${c.dirTxt || '–'}${c.gust && c.gust > (c.wind || 0) + 2 ? `, gusts ${r(c.gust)}` : ''}</div></div>
      <div class="tile"><div class="k">Visibility</div><div class="v num">${visTxt}<small>mi</small></div><div class="n">${visWord}</div></div>
      <div class="tile"><div class="k">Feels like</div><div class="v num">${r(c.feels)}<small>°F</small></div><div class="n">${c.feels != null && c.temp != null ? (c.feels < c.temp - 2 ? 'Wind makes it feel colder' : c.feels > c.temp + 2 ? 'Humidity makes it feel warmer' : 'Close to the actual temperature') : ''}</div></div>
      <div class="tile wide"><div class="k">Air quality index</div>
        ${ai ? `<div class="v num" style="display:flex;align-items:baseline;gap:10px">${r(aqi)} <small style="color:${ai.color};font-family:var(--body);font-size:15px;font-weight:700">${ai.name}</small></div>
        <div class="aqi-bar"><i style="left:${(ai.pos * 100).toFixed(1)}%"></i></div>
        <div class="n" style="margin-top:8px">${ai.note} ${pollutant}.</div>` : '<div class="n">Air quality data is unavailable right now.</div>'}</div>
      <div class="tile"><div class="k">UV index</div><div class="v num">${uvNow == null ? '–' : r(uvNow)}</div><div class="n">${uvNow == null ? '' : uvWord(uvNow) + ' now'}${v.uvMax ? `, peak ${r(v.uvMax)} today` : ''}</div></div>
      <div class="tile"><div class="k">Pressure</div><div class="v num">${c.pressure ? c.pressure.toFixed(2) : '–'}<small>inHg</small></div><div class="n">${c.pressure ? 'At the nearest station' : 'Not reported nearby'}</div></div>
      <div class="tile wide"><div class="k">Sun</div>${sunHtml}</div>
    </div>`;

  // hourly
  const H = v.hours.slice(0, 48);
  const hourlyHtml = `<div class="hscroll"><div class="hinner" id="hinner">${H.map((h, i) => `
    <div class="hcol${i === 0 ? ' day0' : ''}${h.hour === 0 && i ? ' newday' : ''}">
      <div class="t">${i === 0 ? 'Now' : h.hour === 0 ? h.wd : fmtHour(new Date(h.t), tz)}</div>
      ${iconFor(h.text, h.isDay)}
      <div class="tp num">${r(h.temp)}°</div>
      <div class="pp">${h.pop >= 10 ? r(h.pop) + '%' : ''}</div>
      <div class="ws">${r(h.wind)} ${esc(h.dir || '')}</div>
      <div class="ws">${r(h.rh)}% RH</div>
    </div>`).join('')}${curveSvg(H)}</div></div>`;

  // daily
  const ds = v.days.slice(0, 7);
  const lows = ds.map((x) => x.lo).filter((x) => x != null), highs = ds.map((x) => x.hi).filter((x) => x != null);
  const wmin = Math.min(...lows, ...highs), wmax = Math.max(...lows, ...highs), span = Math.max(1, wmax - wmin);
  const dailyHtml = `<div class="days">${ds.map((x, i) => {
    const lo = x.lo ?? x.hi, hi = x.hi ?? x.lo;
    return `<div class="day">
      <button class="dayrow" data-day="${i}" aria-expanded="false">
        <span class="dn">${i === 0 ? esc(x.name) : x.wd}</span>
        ${iconFor(x.dayText || x.nightText, !!x.dayText)}
        <span class="pp">${x.pop >= 10 ? r(x.pop) + '%' : ''}</span>
        <span class="range num"><span class="lo">${x.lo != null ? r(x.lo) + '°' : ''}</span>
          <span class="track"><i style="left:${((lo - wmin) / span * 100).toFixed(1)}%;right:${(100 - (hi - wmin) / span * 100).toFixed(1)}%"></i></span>
          <span>${x.hi != null ? r(x.hi) + '°' : ''}</span></span>
      </button>
      <div class="daydetail" hidden>
        ${x.dayDetail ? `<p><b>${esc(x.dayName)}.</b> ${esc(x.dayDetail)}</p>` : ''}
        ${x.nightDetail ? `<p><b>${esc(x.nightName)}.</b> ${esc(x.nightDetail)}</p>` : ''}
      </div></div>`;
  }).join('')}</div>`;

  $('main').innerHTML = `
    <section><h2>The day ahead</h2><div class="parts">${partsHtml}</div></section>
    <section id="wearSection"><h2>What Kikker recommends <small>${v.spanLabel}</small></h2><div class="wear">${wearHtml}</div></section>
    <section><h2>Next 48 hours <small>rain chance · wind mph · humidity</small></h2>${hourlyHtml}</section>
    <section><h2>7-day forecast <small>tap a day for details</small></h2>${dailyHtml}</section>
    <section><h2>Right now</h2>${detailsHtml}</section>`;
}

/* Kikker wears the outfit for the rest of the day; the scene shows that stretch's weather. */
function kikkerFor(v) {
  if (typeof kikkerSvg !== 'function') return iconFor(v.cur.text, v.cur.isDay);
  const keys = new Set(v.wear.flatMap((w) => w.keys || []));
  const c = v.spanCond;
  const t = (v.cur.text || '').toLowerCase();
  const precip = c.snow && (c.popMax >= 30 || /snow|flurr/.test(t)) ? 'snow'
    : (c.popMax >= 50 || /rain|shower|drizzle|thunder/.test(t)) ? 'rain' : null;
  const mood = c.feelsMin != null && c.feelsMin < 32 ? 'cold'
    : (!precip && c.feelsMax >= 60 && c.feelsMax <= 85) ? 'happy' : 'meh';
  return kikkerSvg(keys, { precip, thunder: c.thunder, sunny: c.sunny, night: !v.cur.isDay, windy: c.windMax >= 20, mood });
}

function curveSvg(H) {
  if (H.length < 2) return '';
  const W = 58, h = 50, ts = H.map((x) => x.temp), lo = Math.min(...ts), hi = Math.max(...ts), sp = Math.max(1, hi - lo);
  const pts = ts.map((t, i) => [i * W + W / 2, 4 + (1 - (t - lo) / sp) * (h - 8)]);
  let dPath = `M${pts[0][0]},${pts[0][1].toFixed(1)}`;
  for (let i = 1; i < pts.length; i++) {
    const [x0, y0] = pts[i - 1], [x1, y1] = pts[i], cx = (x0 + x1) / 2;
    dPath += ` C${cx},${y0.toFixed(1)} ${cx},${y1.toFixed(1)} ${x1},${y1.toFixed(1)}`;
  }
  const area = `${dPath} L${pts[pts.length - 1][0]},${h} L${pts[0][0]},${h} Z`;
  return `<svg class="hcurve" width="${H.length * W}" viewBox="0 0 ${H.length * W} ${h}" aria-hidden="true">
    <defs><linearGradient id="tg" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="var(--warm)" stop-opacity=".22"/><stop offset="1" stop-color="var(--warm)" stop-opacity="0"/></linearGradient></defs>
    <path d="${area}" fill="url(#tg)"/><path d="${dPath}" fill="none" stroke="var(--warm)" stroke-width="2.2"/>
    ${pts.map((p, i) => (i === 0 ? `<circle cx="${p[0]}" cy="${p[1].toFixed(1)}" r="4" fill="var(--warm)"/>` : '')).join('')}
  </svg>`;
}

/* ---------- interactions ---------- */
document.addEventListener('click', (e) => {
  const a = e.target.closest('[data-alert]');
  if (a) { const p = a.querySelector('p'); p.hidden = !p.hidden; a.setAttribute('aria-expanded', String(!p.hidden)); return; }
  if (e.target.closest('#kikkerBtn')) { $('wearSection').scrollIntoView({ behavior: 'smooth', block: 'start' }); return; }
  const dr = e.target.closest('[data-day]');
  if (dr) { const det = dr.nextElementSibling; det.hidden = !det.hidden; dr.setAttribute('aria-expanded', String(!det.hidden)); }
});

function setBusy(b) { state.loading = b; $('refreshBtn').classList.toggle('spin', b); }

function showError(msg) {
  const box = `<div class="err" role="alert">${esc(msg)}</div>`;
  if (!state.data) { $('nowBody').innerHTML = `<div class="msg" style="color:var(--on-sky-dim)">No forecast loaded yet.</div>`; $('main').innerHTML = `<section style="margin-top:18px">${box}</section>`; }
  else { $('main').insertAdjacentHTML('afterbegin', `<section style="margin-top:18px">${box}</section>`); }
}

async function refresh() {
  if (!state.loc || state.loading) return;
  setBusy(true);
  try {
    state.data = await loadAll(state.loc);
    if (!state.loc.label && state.data.pt.place) state.loc.label = state.data.pt.place;
    store.set('wx.loc', state.loc);
    store.set('wx.cache', { loc: state.loc, data: state.data });
    render();
  } catch (e) {
    showError(e.message || 'Could not reach the National Weather Service. Check your connection and try again.');
  } finally { setBusy(false); }
}

function useGps() {
  return new Promise((ok) => {
    if (!navigator.geolocation) { showError('This browser cannot share your location. Search for a city instead.'); return ok(false); }
    $('gpsBtn').classList.add('spin');
    navigator.geolocation.getCurrentPosition((pos) => {
      $('gpsBtn').classList.remove('spin');
      state.loc = { lat: pos.coords.latitude, lon: pos.coords.longitude, label: null, gps: true };
      $('placeName').textContent = 'Current location';
      ok(true); refresh();
    }, (err) => {
      $('gpsBtn').classList.remove('spin');
      showError(err.code === 1 ? 'Location permission is off. Allow location for this app in your phone settings, or search for a city.' : 'Could not get your location. Try again outdoors, or search for a city.');
      ok(false);
    }, { enableHighAccuracy: false, timeout: 12000, maximumAge: 10 * 60e3 });
  });
}

/* location sheet */
function openSheet() { $('sheet').hidden = false; renderSaved(); $('results').innerHTML = ''; setTimeout(() => $('searchInput').focus(), 50); }
function closeSheet() { $('sheet').hidden = true; }
function renderSaved() {
  const saved = store.get('wx.saved', []);
  $('saved').innerHTML = saved.length ? `<h2 style="margin-top:18px">Saved places</h2>${saved.map((s, i) => `<button class="btn" data-saved="${i}">${esc(s.label)}<span class="x" data-del="${i}" aria-label="Remove ${esc(s.label)}">✕</span></button>`).join('')}` : '';
}
function pick(loc) {
  state.loc = loc; state.data = null;
  const saved = store.get('wx.saved', []);
  if (!loc.gps && !saved.some((s) => s.label === loc.label)) { saved.unshift(loc); store.set('wx.saved', saved.slice(0, 8)); }
  $('placeName').textContent = loc.label;
  $('nowBody').innerHTML = '<div class="msg" style="color:var(--on-sky-dim)">Loading the forecast…</div>';
  $('main').innerHTML = '';
  closeSheet(); refresh();
}

$('locBtn').addEventListener('click', openSheet);
$('gpsBtn').addEventListener('click', useGps);
$('refreshBtn').addEventListener('click', refresh);
$('sheetGps').addEventListener('click', () => { closeSheet(); useGps(); });
$('sheet').addEventListener('click', (e) => {
  if (e.target === $('sheet')) return closeSheet();
  const del = e.target.closest('[data-del]');
  if (del) { e.stopPropagation(); const s = store.get('wx.saved', []); s.splice(+del.dataset.del, 1); store.set('wx.saved', s); return renderSaved(); }
  const sv = e.target.closest('[data-saved]');
  if (sv) return pick(store.get('wx.saved', [])[+sv.dataset.saved]);
  const rs = e.target.closest('[data-res]');
  if (rs) return pick(JSON.parse(rs.dataset.res));
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('sheet').hidden) closeSheet(); });
$('searchForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const q = $('searchInput').value.trim();
  if (!q) return;
  $('results').innerHTML = '<div class="msg">Searching…</div>';
  try {
    const j = await getJSON(`https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(q)}&count=8&language=en&format=json&countryCode=US`, 2);
    const res = (j.results || []);
    $('results').innerHTML = res.length ? res.map((x) => {
      const loc = { lat: x.latitude, lon: x.longitude, label: `${x.name}, ${x.admin1 || ''}`.replace(/, $/, ''), gps: false };
      return `<button class="btn" data-res='${esc(JSON.stringify(loc))}'>${esc(loc.label)}<small>${esc(x.admin2 || '')}</small></button>`;
    }).join('') : '<div class="msg">No US places matched. Try a city name like "Asheville".</div>';
  } catch { $('results').innerHTML = '<div class="msg">Search is unavailable. Check your connection.</div>'; }
});

/* refresh when the app comes back to the foreground after 10+ minutes */
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && state.data && Date.now() - state.data.at > 10 * 60e3) {
    if (state.loc && state.loc.gps) useGps(); else refresh();
  }
});

/* ---------- start ---------- */
(function start() {
  const cache = store.get('wx.cache', null);
  if (cache && state.loc && cache.loc && cache.loc.lat === state.loc.lat && cache.loc.lon === state.loc.lon) {
    state.data = cache.data;
    try { render(); } catch { state.data = null; }
  }
  if (!state.loc || state.loc.gps) {
    useGps().then((ok) => { if (!ok && !state.loc) openSheet(); });
  } else {
    $('placeName').textContent = state.loc.label;
    refresh();
  }
  if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
