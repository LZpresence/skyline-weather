/* Radar tab.
   US places: NWS NEXRAD base reflectivity, last 50 minutes in 5-minute steps (Iowa Environmental Mesonet tiles).
   Elsewhere (UK etc.): RainViewer's recent radar frames, which include the Met Office network.
   Base map: OpenStreetMap. Leaflet loads only the first time the tab opens. */
'use strict';
(function () {
  const el = (id) => document.getElementById(id);
  const IEM = ['m50m', 'm45m', 'm40m', 'm35m', 'm30m', 'm25m', 'm20m', 'm15m', 'm10m', 'm05m', ''];
  let map = null, layers = [], labels = [], source = null, marker = null, idx = 0, timer = null, loading = null;

  function loadLeaflet() {
    if (window.L) return Promise.resolve();
    if (loading) return loading;
    loading = new Promise((ok, fail) => {
      const css = document.createElement('link');
      css.rel = 'stylesheet';
      css.href = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css';
      document.head.appendChild(css);
      const s = document.createElement('script');
      s.src = 'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js';
      s.onload = ok;
      s.onerror = () => { loading = null; fail(new Error('The radar map could not load. Check your connection and try again.')); };
      document.head.appendChild(s);
    });
    return loading;
  }

  const wantSource = () => (typeof state !== 'undefined' && state.data && state.data.pt && state.data.pt.provider ? 'rainviewer' : 'nexrad');
  const last = () => layers.length - 1;

  function init() {
    map = L.map('radarMap', { zoomControl: true, attributionControl: true, minZoom: 3, maxZoom: 11 });
    // standard OpenStreetMap tiles; dark mode dims them with a CSS filter on .basemap
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 11, className: 'basemap', attribution: '© OpenStreetMap contributors',
    }).addTo(map);
  }

  async function buildLayers() {
    const want = wantSource();
    if (want === source && layers.length) return;
    layers.forEach((l) => map.removeLayer(l));
    layers = []; labels = [];
    if (want === 'nexrad') {
      layers = IEM.map((f, i) => L.tileLayer(
        `https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913${f ? '-' + f : ''}/{z}/{x}/{y}.png`,
        { opacity: 0, maxZoom: 11, attribution: i === IEM.length - 1 ? 'Radar: NWS NEXRAD via Iowa Environmental Mesonet' : '' },
      ));
      labels = IEM.map((f, i) => { const m = (IEM.length - 1 - i) * 5; return m ? `${m} min ago` : 'Latest'; });
    } else {
      const j = await fetch('https://api.rainviewer.com/public/weather-maps.json').then((r) => r.json());
      const frames = j.radar.past;
      layers = frames.map((f, i) => L.tileLayer(`${j.host}${f.path}/256/{z}/{x}/{y}/2/1_1.png`, {
        opacity: 0, maxNativeZoom: 7, maxZoom: 11, attribution: i === frames.length - 1 ? 'Radar: RainViewer' : '',
      }));
      labels = frames.map((f, i) => {
        if (i === frames.length - 1) return 'Latest';
        return `${Math.round((frames[frames.length - 1].time - f.time) / 60)} min ago`;
      });
    }
    layers.forEach((l) => l.addTo(map));
    source = want;
    el('radarSlider').max = String(last());
  }

  function center() {
    const loc = typeof state !== 'undefined' ? state.loc : null;
    el('radarPlace').textContent = (loc && loc.label) || (loc && loc.gps ? 'My location' : 'Radar');
    if (!loc) { map.setView([39.5, -98.35], 4); return; }
    map.setView([loc.lat, loc.lon], 7);
    if (marker) marker.setLatLng([loc.lat, loc.lon]);
    else marker = L.circleMarker([loc.lat, loc.lon], { radius: 7, color: '#ffffff', weight: 2.5, fillColor: '#e08a2c', fillOpacity: 1 }).addTo(map);
  }

  function show(i) {
    idx = i;
    layers.forEach((l, j) => l.setOpacity(j === i ? 0.72 : 0));
    el('radarTime').textContent = labels[i] || '';
    el('radarSlider').value = String(i);
  }

  function stop() { clearTimeout(timer); timer = null; }
  function setPlaying(on) {
    el('radarPlay').setAttribute('aria-label', on ? 'Pause radar loop' : 'Play radar loop');
    el('radarPlay').innerHTML = on
      ? '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="5" width="4" height="14" rx="1"/><rect x="14" y="5" width="4" height="14" rx="1"/></svg>'
      : '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';
  }
  function play() {
    stop();
    setPlaying(true);
    const tick = () => {
      const next = idx >= last() ? 0 : idx + 1;
      show(next);
      timer = setTimeout(tick, next === last() ? 1800 : 500);
    };
    timer = setTimeout(tick, 600);
  }
  function pause() { stop(); setPlaying(false); }

  async function openRadar() {
    el('radarMsg').hidden = true;
    try {
      await loadLeaflet();
      if (!map) init();
      map.invalidateSize();
      await buildLayers();
    } catch (e) {
      el('radarMsg').textContent = e.message && e.message.startsWith('The radar') ? e.message : 'Radar is unavailable right now. Try again in a few minutes.';
      el('radarMsg').hidden = false;
      return;
    }
    center();
    show(last());
    if (!matchMedia('(prefers-reduced-motion: reduce)').matches) play(); else setPlaying(false);
  }

  function setTab(tab) {
    const radar = tab === 'radar';
    for (const id of ['nowHeader', 'main', 'pageFooter']) el(id).hidden = radar;
    el('radarView').hidden = !radar;
    document.querySelectorAll('.tab').forEach((b) => {
      b.classList.toggle('on', b.dataset.tab === tab);
      b.setAttribute('aria-selected', String(b.dataset.tab === tab));
    });
    if (radar) openRadar(); else { pause(); window.scrollTo(0, 0); }
  }

  document.addEventListener('click', (e) => {
    const t = e.target.closest('.tab');
    if (t) {
      const want = t.dataset.tab;
      if (want === 'radar' && location.hash !== '#radar') location.hash = 'radar';
      else if (want === 'forecast' && location.hash === '#radar') history.back();
      return;
    }
    if (e.target.closest('#radarPlay')) { timer ? pause() : play(); return; }
    if (e.target.closest('#radarRecenter') && map) center();
  });
  el('radarSlider').addEventListener('input', (e) => { pause(); show(Number(e.target.value)); });
  window.addEventListener('hashchange', () => setTab(location.hash === '#radar' ? 'radar' : 'forecast'));
  if (location.hash === '#radar') setTab('radar');

  // the forecast code calls this when the place changes
  window.radarPlaceChanged = () => { if (map && !el('radarView').hidden) openRadar(); };
})();
