/* Service Worker：静态缓存 + 后台周期同步（App 被杀后仍可拉行情并弹通知） */
var CACHE = "sm_v9";
var ASSETS = ["./", "./index.html", "./stocklist.js", "./manifest.webmanifest", "./icon.svg"];
var AUTO_FUND_MIN = 2.0;   // 自动检测：占比绝对值需≥2%
var AUTO_FUND_RAPID = 1.5; // 自动检测：占比两次刷新变动≥1.5pp = 急速

self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(ASSETS); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener("activate", function (e) {
  e.waitUntil(caches.keys().then(function (ks) {
    return Promise.all(ks.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener("fetch", function (e) {
  var u = new URL(e.request.url);
  if (u.origin !== self.location.origin) return; // 东方财富行情直接走网络，不缓存
  // 导航与 HTML：网络优先，避免 QQ 浏览器等缓存旧版导致功能不更新
  if (e.request.mode === "navigate" || u.pathname.endsWith("/") || u.pathname.endsWith("index.html")) {
    e.respondWith(
      fetch(e.request).then(function (resp) {
        var cp = resp.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, cp); });
        return resp;
      }).catch(function () { return caches.match(e.request).then(function (r) { return r || caches.match("./index.html"); }); })
    );
    return;
  }
  // 其它静态资源：缓存优先，网络兜底
  e.respondWith(
    caches.match(e.request).then(function (r) {
      if (r) return r;
      return fetch(e.request).then(function (resp) {
        var cp = resp.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, cp); });
        return resp;
      }).catch(function () { return caches.match("./index.html"); });
    })
  );
});

self.addEventListener("periodicsync", function (e) {
  if (e.tag === "stock-sync") e.waitUntil(doSync().catch(function () {}));
});
// 保底：即使不支持 periodicsync，也在 SW 激活后尝试一次
self.addEventListener("activate", function (e) {
  e.waitUntil(doSync().catch(function () {}));
});

// ---------- IndexedDB：读取网页镜像的监控列表 ----------
var DB = "sm_idx_db", STORE = "watch", KEY = "watch";
function openDB() {
  return new Promise(function (res, rej) {
    var r = indexedDB.open(DB, 1);
    r.onupgradeneeded = function () { r.result.createObjectStore(STORE); };
    r.onsuccess = function () { res(r.result); };
    r.onerror = function () { rej(r.error); };
  });
}
function idbLoad() {
  return openDB().then(function (db) {
    return new Promise(function (res) {
      var tx = db.transaction(STORE, "readonly").objectStore(STORE).get(KEY);
      tx.onsuccess = function () { res(tx.result || null); };
      tx.onerror = function () { res(null); };
    });
  }).catch(function () { return null; });
}

// ---------- 行情抓取（fetch，东方财富支持 CORS） ----------
function fetchQuotes(secids) {
  if (!secids.length) return Promise.resolve({});
  var url = "https://push2delay.eastmoney.com/api/qt/ulist.np/get?secids=" +
    encodeURIComponent(secids.join(",")) + "&fields=f2,f3,f4,f12,f14,f62,f184&pn=1&pz=200&forcedelay=1";
  return fetch(url).then(function (r) { return r.json(); }).then(function (data) {
    var map = {}, codeToSec = {};
    secids.forEach(function (s) { codeToSec[s.split(".").pop()] = s; });
    (data.data.diff || []).forEach(function (it) {
      var s = codeToSec[it.f12]; if (!s) return;
      map[s] = { price: it.f2 / 100, pct: it.f3 / 100, fundPct: it.f184 / 100 };
    });
    return map;
  }).catch(function () { return {}; });
}

// ---------- 后台同步报警 ----------
function doSync() {
  return idbLoad().then(function (st) {
    if (!st || !st.items || !st.items.length) return;
    return fetchQuotes(st.items.map(function (i) { return i.secid; })).then(function (map) {
      var prev = st.alertState || {};
      st.items.forEach(function (it) {
        var q = map[it.secid]; if (!q) return;
        var s = prev[it.secid] || { up: false, down: false, fund: false, prevFundPct: null };
        var upOk = it.up > 0 && q.pct >= it.up;
        var downOk = it.down > 0 && q.pct <= -it.down;
        // 资金：手动阈值 或 自动急速检测（占比变动≥RAPID 且绝对值≥MIN）
        var fundOk = false, fundType = "fund";
        if (q.fundPct != null) {
          if (it.fund > 0) {
            fundOk = Math.abs(q.fundPct) >= it.fund;
          } else if (s.prevFundPct != null) {
            var dpct = Math.abs(q.fundPct - s.prevFundPct);
            fundOk = Math.abs(q.fundPct) >= AUTO_FUND_MIN && dpct >= AUTO_FUND_RAPID;
            fundType = q.fundPct > 0 ? "fundIn" : "fundOut";
          }
        }
        if (upOk && !s.up) { s.up = true; notify(it, q, "up"); }
        if (!upOk) s.up = false;
        if (downOk && !s.down) { s.down = true; notify(it, q, "down"); }
        if (!downOk) s.down = false;
        if (fundOk && !s.fund) { s.fund = true; notify(it, q, fundType); }
        if (!fundOk) s.fund = false;
        s.prevFundPct = q.fundPct;
        prev[it.secid] = s;
      });
      st.alertState = prev;
      return openDB().then(function (db) {
        return new Promise(function (res) {
          var tx = db.transaction(STORE, "readwrite").objectStore(STORE).put(st, KEY);
          tx.oncomplete = function () { res(); };
          tx.onerror = function () { res(); };
        });
      });
    });
  });
}

function notify(it, q, dir) {
  var title, body;
  if (dir === "up") { title = "📈 涨幅预警"; body = it.name + " 涨幅 +" + q.pct.toFixed(2) + "%"; }
  else if (dir === "down") { title = "📉 跌幅预警"; body = it.name + " 跌幅 " + q.pct.toFixed(2) + "%"; }
  else if (dir === "fundIn") { title = "💰 资金急速流入"; body = it.name + " 主力占比 +" + q.fundPct.toFixed(2) + "%"; }
  else if (dir === "fundOut") { title = "💸 资金急速流出"; body = it.name + " 主力占比 " + q.fundPct.toFixed(2) + "%"; }
  else { title = "💰 资金流预警"; body = it.name + " 主力占比 " + (q.fundPct > 0 ? "+" : "") + q.fundPct.toFixed(2) + "%"; }
  return self.registration.showNotification(title, { body: body, tag: it.secid + dir, renotify: true });
}
